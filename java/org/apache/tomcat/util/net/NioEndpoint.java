/*
 * Apache许可证声明：该文件遵循Apache License 2.0协议，允许在合规条件下使用、修改和分发
 */
package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLEngine;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.compat.JrePlatform;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.Acceptor.AcceptorState;
import org.apache.tomcat.util.net.jsse.JSSESupport;

/**
 * NIO端点实现类：基于Java NIO的非阻塞I/O网络处理组件
 * @author Mladen Turk
 */
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel,SocketChannel> {

    // -------------------------------------------------------------- 常量定义
    private static final Log log = LogFactory.getLog(NioEndpoint.class);
    // 证书相关日志（单独分类便于过滤）
    private static final Log logCertificate = LogFactory.getLog(NioEndpoint.class.getName() + ".certificate");
    // 握手过程日志（单独分类便于调试SSL/TLS）
    private static final Log logHandshake = LogFactory.getLog(NioEndpoint.class.getName() + ".handshake");

    // 自定义选择键操作：用于注册新连接
    public static final int OP_REGISTER = 0x100;

    // ----------------------------------------------------------------- 字段
    /** 服务器套接字通道（监听客户端连接） */
    private volatile ServerSocketChannel serverSock = null;

    /** 停止锁存器：用于等待Poller线程停止 */
    private volatile CountDownLatch stopLatch = null;

    /** Poller事件缓存：避免频繁创建对象 */
    private SynchronizedStack<PollerEvent> eventCache;

    /** NIO通道缓存：复用NioChannel实例，提升性能 */
    private SynchronizedStack<NioChannel> nioChannels;

    /** 上一次接受的套接字远程地址（用于防重复连接攻击） */
    private SocketAddress previousAcceptedSocketRemoteAddress = null;
    /** 上一次接受连接的时间戳（配合地址防止短时间内重复连接） */
    private long previousAcceptedSocketNanoTime = 0;

    // ------------------------------------------------------------- 属性
    /** 是否使用JVM继承的通道（如通过System.inheritedChannel获取） */
    private boolean useInheritedChannel = false;

    public void setUseInheritedChannel(boolean useInheritedChannel) {
        this.useInheritedChannel = useInheritedChannel;
    }

    public boolean getUseInheritedChannel() {
        return useInheritedChannel;
    }

    /** Unix域套接字路径（用于本地进程间通信） */
    private String unixDomainSocketPath = null;

    public String getUnixDomainSocketPath() {
        return this.unixDomainSocketPath;
    }

    public void setUnixDomainSocketPath(String unixDomainSocketPath) {
        this.unixDomainSocketPath = unixDomainSocketPath;
    }

    /** Unix域套接字权限（创建时设置） */
    private String unixDomainSocketPathPermissions = null;

    public String getUnixDomainSocketPathPermissions() {
        return this.unixDomainSocketPathPermissions;
    }

    public void setUnixDomainSocketPathPermissions(String unixDomainSocketPathPermissions) {
        this.unixDomainSocketPathPermissions = unixDomainSocketPathPermissions;
    }

    /** Poller线程优先级（默认NORM_PRIORITY） */
    private int pollerThreadPriority = Thread.NORM_PRIORITY;

    public void setPollerThreadPriority(int pollerThreadPriority) {
        this.pollerThreadPriority = pollerThreadPriority;
    }

    public int getPollerThreadPriority() {
        return pollerThreadPriority;
    }

    /** 选择器超时时间（毫秒，用于select操作） */
    private long selectorTimeout = 1000;

    public void setSelectorTimeout(long timeout) {
        this.selectorTimeout = timeout;
    }

    public long getSelectorTimeout() {
        return this.selectorTimeout;
    }

    /** 轮询器实例：处理I/O事件的核心线程 */
    private Poller poller = null;

    // --------------------------------------------------------- 公共方法
    /**
     * 获取保持活动状态的套接字数量
     * @return 处于keep-alive状态的套接字数量
     */
    public int getKeepAliveCount() {
        if (poller == null) return 0;
        return poller.getKeyCount(); // 通过Poller获取活动键数量
    }

    /**
     * 获取端点ID（用于JMX等场景）
     * @return 基于配置的唯一标识
     */
    @Override
    public String getId() {
        if (getUseInheritedChannel()) {
            return "JVMInheritedChannel"; // 继承通道的标识
        } else if (getUnixDomainSocketPath() != null) {
            return getUnixDomainSocketPath(); // Unix域套接字路径作为标识
        } else {
            return null;
        }
    }

    // ----------------------------------------------- 生命周期方法
    /**
     * 绑定网络端口：初始化服务器套接字
     */
    @Override
    public void bind() throws Exception {
        initServerSocket(); // 初始化服务器套接字
        setStopLatch(new CountDownLatch(1)); // 初始化停止锁存器
        initialiseSsl(); // 初始化SSL/TLS支持
    }

    /**
     * 初始化服务器套接字（分离为独立方法便于子类扩展）
     */
    protected void initServerSocket() throws Exception {
        if (getUseInheritedChannel()) {
            // 获取JVM继承的通道（如通过System.inheritedChannel）
            Channel ic = System.inheritedChannel();
            if (ic instanceof ServerSocketChannel) {
                serverSock = (ServerSocketChannel) ic;
            }
            if (serverSock == null) {
                throw new IllegalArgumentException(sm.getString("endpoint.init.bind.inherited"));
            }
        } else if (getUnixDomainSocketPath() != null) {
            // 创建Unix域套接字通道
            SocketAddress sa = JreCompat.getInstance().getUnixDomainSocketAddress(getUnixDomainSocketPath());
            serverSock = JreCompat.getInstance().openUnixDomainServerSocketChannel();
            serverSock.bind(sa, getAcceptCount());
            // 设置套接字权限
            if (getUnixDomainSocketPathPermissions() != null) {
                Path path = Paths.get(getUnixDomainSocketPath());
                Set<PosixFilePermission> permissions =
                    PosixFilePermissions.fromString(getUnixDomainSocketPathPermissions());
                if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    // POSIX系统使用文件属性设置权限
                    FileAttribute<Set<PosixFilePermission>> attrs =
                        PosixFilePermissions.asFileAttribute(permissions);
                    Files.setAttribute(path, attrs.name(), attrs.value());
                } else {
                    // 非POSIX系统使用文件API设置权限
                    File file = path.toFile();
                    if (permissions.contains(PosixFilePermission.OTHERS_READ) && !file.setReadable(true, false)) {
                        log.warn(sm.getString("endpoint.nio.perms.readFail", file.getPath()));
                    }
                    if (permissions.contains(PosixFilePermission.OTHERS_WRITE) && !file.setWritable(true, false)) {
                        log.warn(sm.getString("endpoint.nio.perms.writeFail", file.getPath()));
                    }
                }
            }
        } else {
            // 常规TCP套接字初始化
            serverSock = ServerSocketChannel.open();
            socketProperties.setProperties(serverSock.socket()); // 应用套接字属性
            InetSocketAddress addr = new InetSocketAddress(getAddress(), getPortWithOffset());
            serverSock.bind(addr, getAcceptCount()); // 绑定地址和端口
        }
        serverSock.configureBlocking(true); // 临时设置为阻塞模式（模拟APR行为）
    }

    /**
     * 启动NIO端点：创建Acceptor和Poller线程
     */
    @Override
    public void startInternal() throws Exception {
        if (!running) {
            running = true;
            paused = false;

            // 初始化处理器缓存（复用处理器实例）
            if (socketProperties.getProcessorCache() != 0) {
                processorCache = new SynchronizedStack<>(
                    SynchronizedStack.DEFAULT_SIZE, socketProperties.getProcessorCache());
            }
            // 初始化事件缓存（复用PollerEvent实例）
            if (socketProperties.getEventCache() != 0) {
                eventCache = new SynchronizedStack<>(
                    SynchronizedStack.DEFAULT_SIZE, socketProperties.getEventCache());
            }
            // 初始化NIO通道缓存（根据SSL状态调整缓存大小）
            int actualBufferPool = socketProperties.getActualBufferPool(
                isSSLEnabled() ? getSniParseLimit() * 2 : 0);
            if (actualBufferPool != 0) {
                nioChannels = new SynchronizedStack<>(
                    SynchronizedStack.DEFAULT_SIZE, actualBufferPool);
            }

            // 创建执行器（若未配置）
            if (getExecutor() == null) {
                createExecutor();
            }

            initializeConnectionLatch(); // 初始化连接计数器

            // 启动Poller线程（处理I/O事件）
            poller = new Poller();
            Thread pollerThread = new Thread(poller, getName() + "-Poller");
            pollerThread.setPriority(threadPriority);
            pollerThread.setDaemon(true);
            pollerThread.start();

            startAcceptorThread(); // 启动Acceptor线程（接受新连接）
        }
    }

    /**
     * 停止NIO端点：终止所有处理线程
     */
    @Override
    public void stopInternal() {
        if (!paused) {
            pause(); // 先暂停处理
        }
        if (running) {
            running = false;
            // 等待Acceptor解锁（设置合理超时）
            int acceptorWaitMilliSeconds = 100 + 2 * getSocketProperties().getUnlockTimeout();
            acceptor.stopMillis(acceptorWaitMilliSeconds);
            if (poller != null) {
                poller.destroy(); // 销毁Poller
                poller = null;
            }
            // 等待Poller线程停止
            try {
                if (!getStopLatch().await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
                    log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
                }
            } catch (InterruptedException e) {
                log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
            }
            shutdownExecutor(); // 关闭执行器
            // 清理缓存资源
            if (eventCache != null) {
                eventCache.clear();
                eventCache = null;
            }
            if (nioChannels != null) {
                NioChannel socket;
                while ((socket = nioChannels.pop()) != null) {
                    socket.free(); // 释放通道资源
                }
                nioChannels = null;
            }
            if (processorCache != null) {
                processorCache.clear();
                processorCache = null;
            }
        }
    }

    /**
     * 释放NIO资源：关闭服务器套接字
     */
    @Override
    public void unbind() throws Exception {
        if (log.isTraceEnabled()) {
            log.trace("Destroy initiated for " + new InetSocketAddress(getAddress(), getPortWithOffset()));
        }
        if (running) {
            stop(); // 先停止端点
        }
        try {
            doCloseServerSocket(); // 关闭服务器套接字
        } catch (IOException ioe) {
            getLog().warn(sm.getString("endpoint.serverSocket.closeFailed", getName()), ioe);
        }
        destroySsl(); // 销毁SSL上下文
        super.unbind();
        if (getHandler() != null) {
            getHandler().recycle(); // 回收处理器
        }
        if (log.isTraceEnabled()) {
            log.trace("Destroy completed for " + new InetSocketAddress(getAddress(), getPortWithOffset()));
        }
    }

    /**
     * 关闭服务器套接字（分离为独立方法便于子类扩展）
     */
    @Override
    protected void doCloseServerSocket() throws IOException {
        try {
            if (!getUseInheritedChannel() && serverSock != null) {
                serverSock.close(); // 关闭服务器套接字通道
            }
            serverSock = null;
        } finally {
            // 清理Unix域套接字文件
            if (getUnixDomainSocketPath() != null && getBindState().wasBound()) {
                Files.delete(Paths.get(getUnixDomainSocketPath()));
            }
        }
    }

    // ------------------------------------------------------ 保护方法
    /**
     * 解锁Acceptor（针对Unix域套接字的特殊处理）
     */
    @Override
    protected void unlockAccept() {
        if (getUnixDomainSocketPath() == null) {
            super.unlockAccept(); // 常规TCP套接字解锁逻辑
        } else {
            // Unix域套接字解锁逻辑
            if (acceptor == null || acceptor.getState() != AcceptorState.RUNNING) {
                return;
            }
            try {
                SocketAddress sa = JreCompat.getInstance().getUnixDomainSocketAddress(getUnixDomainSocketPath());
                try (SocketChannel socket = JreCompat.getInstance().openUnixDomainSocketChannel()) {
                    socket.connect(sa); // 连接Unix域套接字以触发Acceptor解锁
                }
                // 等待Acceptor线程解锁（最多1000ms）
                long waitLeft = 1000;
                while (waitLeft > 0 && acceptor.getState() == AcceptorState.RUNNING) {
                    Thread.sleep(5);
                    waitLeft -= 5;
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                if (getLog().isDebugEnabled()) {
                    getLog().debug(sm.getString("endpoint.debug.unlock.fail", String.valueOf(getPortWithOffset())), t);
                }
            }
        }
    }

    /** 获取NIO通道缓存 */
    protected SynchronizedStack<NioChannel> getNioChannels() {
        return nioChannels;
    }

    /** 获取Poller实例 */
    protected Poller getPoller() {
        return poller;
    }

    /** 获取停止锁存器 */
    protected CountDownLatch getStopLatch() {
        return stopLatch;
    }

    /** 设置停止锁存器 */
    protected void setStopLatch(CountDownLatch stopLatch) {
        this.stopLatch = stopLatch;
    }

    /**
     * 设置套接字选项：配置新接受的连接
     * @param socket 套接字通道
     * @return 是否配置成功（成功则继续处理，失败则关闭套接字）
     */
    @Override
    protected boolean setSocketOptions(SocketChannel socket) {
        NioSocketWrapper socketWrapper = null;
        try {
            // 从缓存获取NioChannel实例（或新建）
            NioChannel channel = null;
            if (nioChannels != null) {
                channel = nioChannels.pop();
            }
            if (channel == null) {
                // 创建新的通道处理器
                SocketBufferHandler bufhandler = new SocketBufferHandler(
                    socketProperties.getAppReadBufSize(),
                    socketProperties.getAppWriteBufSize(),
                    socketProperties.getDirectBuffer());
                if (isSSLEnabled()) {
                    channel = new SecureNioChannel(bufhandler, this); // SSL通道
                } else {
                    channel = new NioChannel(bufhandler); // 普通通道
                }
            }
            // 创建套接字包装器
            NioSocketWrapper newWrapper = new NioSocketWrapper(channel, this);
            channel.reset(socket, newWrapper);
            connections.put(socket, newWrapper); // 保存到连接映射
            socketWrapper = newWrapper;

            // 配置套接字为非阻塞模式
            socket.configureBlocking(false);
            if (getUnixDomainSocketPath() == null) {
                socketProperties.setProperties(socket.socket()); // 应用套接字属性
            }

            // 设置超时和保持活动参数
            socketWrapper.setReadTimeout(getConnectionTimeout());
            socketWrapper.setWriteTimeout(getConnectionTimeout());
            socketWrapper.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            poller.register(socketWrapper); // 注册到Poller
            return true;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error(sm.getString("endpoint.socketOptionsError"), t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            }
            if (socketWrapper == null) {
                destroySocket(socket); // 销毁失败的套接字
            }
        }
        return false;
    }

    /**
     * 销毁套接字：关闭通道并释放资源
     */
    @Override
    protected void destroySocket(SocketChannel socket) {
        countDownConnection(); // 减少连接计数
        try {
            socket.close(); // 关闭套接字通道
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.err.close"), ioe);
            }
        }
    }

    /** 获取服务器套接字通道 */
    @Override
    protected NetworkChannel getServerSocket() {
        return serverSock;
    }

    /**
     * 接受服务器套接字连接（添加防重复连接检查）
     */
    @Override
    protected SocketChannel serverSocketAccept() throws Exception {
        SocketChannel result = serverSock.accept(); // 接受新连接

        // 防重复连接攻击检查（Windows和Unix域套接字除外）
        if (!JrePlatform.IS_WINDOWS && getUnixDomainSocketPath() == null) {
            SocketAddress currentRemoteAddress = result.getRemoteAddress();
            long currentNanoTime = System.nanoTime();
            if (currentRemoteAddress.equals(previousAcceptedSocketRemoteAddress) &&
                currentNanoTime - previousAcceptedSocketNanoTime < 1000) {
                throw new IOException(sm.getString("endpoint.err.duplicateAccept"));
            }
            previousAcceptedSocketRemoteAddress = currentRemoteAddress;
            previousAcceptedSocketNanoTime = currentNanoTime;
        }

        return result;
    }

    /** 获取常规日志实例 */
    @Override
    protected Log getLog() {
        return log;
    }

    /** 获取证书相关日志实例 */
    @Override
    protected Log getLogCertificate() {
        return logCertificate;
    }

    /** 创建套接字处理器（处理请求逻辑） */
    @Override
    protected SocketProcessorBase<NioChannel> createSocketProcessor(
        SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
        return new SocketProcessor(socketWrapper, event);
    }

    // ----------------------------------------------------- Poller内部类
    /**
     * Poller事件类：缓存事件对象避免GC压力
     */
    public static class PollerEvent {
        private NioSocketWrapper socketWrapper; // 套接字包装器
        private int interestOps; // 感兴趣的操作（读/写等）

        public PollerEvent(NioSocketWrapper socketWrapper, int intOps) {
            reset(socketWrapper, intOps);
        }

        public void reset(NioSocketWrapper socketWrapper, int intOps) {
            this.socketWrapper = socketWrapper;
            interestOps = intOps;
        }

        public NioSocketWrapper getSocketWrapper() { return socketWrapper; }
        public int getInterestOps() { return interestOps; }
        public void reset() { reset(null, 0); }

        @Override
        public String toString() {
            return "Poller event: socket [" + socketWrapper.getSocket() + "], socketWrapper [" + socketWrapper +
                "], interestOps [" + interestOps + "]";
        }
    }

    /**
     * Poller类：NIO事件轮询器（核心I/O事件处理线程）
     */
    public class Poller implements Runnable {
        private final Selector selector; // NIO选择器，监听I/O事件
        private final SynchronizedQueue<PollerEvent> events = new SynchronizedQueue<>(); // 事件队列

        private volatile boolean close = false; // 关闭标志
        private long nextExpiration = 0; // 下一次超时检查时间

        private final AtomicLong wakeupCounter = new AtomicLong(0); // 唤醒计数器
        private volatile int keyCount = 0; // 活动键数量

        public Poller() throws IOException {
            this.selector = Selector.open(); // 打开选择器
        }

        public int getKeyCount() { return keyCount; }
        public Selector getSelector() { return selector; }

        /** 销毁Poller：关闭选择器并停止线程 */
        protected void destroy() {
            close = true; // 设置关闭标志
            selector.wakeup(); // 唤醒选择器以退出阻塞
        }

        /** 添加事件到队列并唤醒选择器 */
        private void addEvent(PollerEvent event) {
            events.offer(event);
            if (wakeupCounter.incrementAndGet() == 0) {
                selector.wakeup(); // 唤醒选择器处理事件
            }
        }

        /** 创建或获取PollerEvent实例（从缓存获取或新建） */
        private PollerEvent createPollerEvent(NioSocketWrapper socketWrapper, int interestOps) {
            PollerEvent r = null;
            if (eventCache != null) {
                r = eventCache.pop();
            }
            if (r == null) {
                r = new PollerEvent(socketWrapper, interestOps);
            } else {
                r.reset(socketWrapper, interestOps);
            }
            return r;
        }

        /** 向Poller添加套接字事件 */
        public void add(NioSocketWrapper socketWrapper, int interestOps) {
            PollerEvent pollerEvent = createPollerEvent(socketWrapper, interestOps);
            addEvent(pollerEvent);
            if (close) {
                processSocket(socketWrapper, SocketEvent.STOP, false); // 关闭时处理停止事件
            }
        }

        /** 处理事件队列中的事件 */
        public boolean events() {
            boolean result = false;
            PollerEvent pe;
            // 处理队列中所有事件
            for (int i = 0, size = events.size(); i < size && (pe = events.poll()) != null; i++) {
                result = true;
                NioSocketWrapper socketWrapper = pe.getSocketWrapper();
                SocketChannel sc = socketWrapper.getSocket().getIOChannel();
                int interestOps = pe.getInterestOps();
                if (sc == null) {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("endpoint.nio.nullSocketChannel"));
                    }
                    socketWrapper.close(); // 空通道直接关闭
                } else if (interestOps == OP_REGISTER) {
                    // 注册新连接事件
                    try {
                        sc.register(getSelector(), SelectionKey.OP_READ, socketWrapper);
                    } catch (Exception x) {
                        log.error(sm.getString("endpoint.nio.registerFail"), x);
                    }
                } else {
                    // 更新已注册键的感兴趣操作
                    final SelectionKey key = sc.keyFor(getSelector());
                    if (key == null) {
                        // 键已取消（如套接字关闭）
                        socketWrapper.close();
                    } else {
                        final NioSocketWrapper attachment = (NioSocketWrapper) key.attachment();
                        if (attachment != null) {
                            // 更新感兴趣的操作
                            int ops = key.interestOps() | interestOps;
                            attachment.interestOps(ops);
                            key.interestOps(ops);
                        } else {
                            socketWrapper.close();
                        }
                    }
                }
                // 回收事件对象（若运行中）
                if (running && eventCache != null) {
                    pe.reset();
                    eventCache.push(pe);
                }
            }
            return result;
        }

        /** 注册新套接字到Poller */
        public void register(final NioSocketWrapper socketWrapper) {
            socketWrapper.interestOps(SelectionKey.OP_READ); // 设置为读事件
            PollerEvent pollerEvent = createPollerEvent(socketWrapper, OP_REGISTER);
            addEvent(pollerEvent);
        }

        /**
         * Poller线程主循环：监听I/O事件并分发处理
         */
        @Override
        public void run() {
            while (true) {
                boolean hasEvents = false;
                try {
                    if (!close) {
                        hasEvents = events(); // 先处理事件队列
                        if (wakeupCounter.getAndSet(-1) > 0) {
                            // 有唤醒请求，非阻塞选择
                            keyCount = selector.selectNow();
                        } else {
                            // 阻塞选择（带超时）
                            keyCount = selector.select(selectorTimeout);
                        }
                        wakeupCounter.set(0);
                    }
                    if (close) {
                        events(); // 关闭前处理所有剩余事件
                        timeout(0, false); // 处理超时
                        try {
                            selector.close(); // 关闭选择器
                        } catch (IOException ioe) {
                            log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    }
                    // 选择器返回后处理事件（超时或有事件）
                    if (keyCount == 0) {
                        hasEvents = (hasEvents | events());
                    }
                } catch (Throwable x) {
                    ExceptionUtils.handleThrowable(x);
                    log.error(sm.getString("endpoint.nio.selectorLoopError"), x);
                    continue;
                }

                // 处理选择键事件
                Iterator<SelectionKey> iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey sk = iterator.next();
                    iterator.remove(); // 从集合中移除键
                    NioSocketWrapper socketWrapper = (NioSocketWrapper) sk.attachment();
                    if (socketWrapper != null) {
                        processKey(sk, socketWrapper); // 处理键事件
                    }
                }

                // 处理超时检查
                timeout(keyCount, hasEvents);
            }
            getStopLatch().countDown(); // 通知停止锁存器
        }

        /** 处理选择键事件（读/写操作） */
        protected void processKey(SelectionKey sk, NioSocketWrapper socketWrapper) {
            try {
                if (close) {
                    socketWrapper.close(); // 关闭时直接关闭套接字
                } else if (sk.isValid()) {
                    if (sk.isReadable() || sk.isWritable()) {
                        if (socketWrapper.getSendfileData() != null) {
                            // 处理文件发送
                            processSendfile(sk, socketWrapper, false);
                        } else {
                            // 取消注册当前操作
                            unreg(sk, socketWrapper, sk.readyOps());
                            boolean closeSocket = false;
                            // 先处理读事件
                            if (sk.isReadable()) {
                                if (socketWrapper.readOperation != null) {
                                    if (!socketWrapper.readOperation.process()) {
                                        closeSocket = true;
                                    }
                                } else if (socketWrapper.readBlocking) {
                                    // 唤醒读阻塞线程
                                    synchronized (socketWrapper.readLock) {
                                        socketWrapper.readBlocking = false;
                                        socketWrapper.readLock.notify();
                                    }
                                } else if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                    closeSocket = true;
                                }
                            }
                            // 再处理写事件
                            if (!closeSocket && sk.isWritable()) {
                                if (socketWrapper.writeOperation != null) {
                                    if (!socketWrapper.writeOperation.process()) {
                                        closeSocket = true;
                                    }
                                } else if (socketWrapper.writeBlocking) {
                                    // 唤醒写阻塞线程
                                    synchronized (socketWrapper.writeLock) {
                                        socketWrapper.writeBlocking = false;
                                        socketWrapper.writeLock.notify();
                                    }
                                } else if (!processSocket(socketWrapper, SocketEvent.OPEN_WRITE, true)) {
                                    closeSocket = true;
                                }
                            }
                            if (closeSocket) {
                                socketWrapper.close(); // 异常时关闭套接字
                            }
                        }
                    }
                } else {
                    // 无效键直接关闭套接字
                    socketWrapper.close();
                }
            } catch (CancelledKeyException ckx) {
                socketWrapper.close();
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("endpoint.nio.keyProcessingError"), t);
            }
        }

        /** 处理文件发送（sendfile操作） */
        public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper, boolean calledByProcessor) {
            NioChannel sc = null;
            try {
                unreg(sk, socketWrapper, sk.readyOps()); // 取消注册当前操作
                SendfileData sd = socketWrapper.getSendfileData(); // 获取文件发送数据

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                if (sd.fchannel == null) {
                    // 初始化文件通道
                    File f = new File(sd.fileName);
                    @SuppressWarnings("resource") // 由finally块关闭
                    FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                // 配置输出通道（处理SSL/TLS场景）
                sc = socketWrapper.getSocket();
                WritableByteChannel wc = ((sc instanceof SecureNioChannel) ? sc : sc.getIOChannel());

                // 先处理出站缓冲区剩余数据
                if (sc.getOutboundRemaining() > 0) {
                    if (sc.flushOutbound()) {
                        socketWrapper.updateLastWrite();
                    }
                } else {
                    // 从文件通道传输数据到输出通道
                    long written = sd.fchannel.transferTo(sd.pos, sd.length, wc);
                    if (written > 0) {
                        sd.pos += written;
                        sd.length -= written;
                        socketWrapper.updateLastWrite();
                    }
                }

                // 检查是否传输完成
                if (sd.length <= 0 && sc.getOutboundRemaining() <= 0) {
                    if (log.isTraceEnabled()) {
                        log.trace("Send file complete for: " + sd.fileName);
                    }
                    socketWrapper.setSendfileData(null);
                    try {
                        sd.fchannel.close(); // 关闭文件通道
                    } catch (Exception ignore) {}
                    // 根据保持活动状态处理后续操作
                    if (!calledByProcessor) {
                        switch (sd.keepAliveState) {
                            case NONE:
                                socketWrapper.close(); // 无保持活动则关闭连接
                                break;
                            case PIPELINED:
                                // 处理流水线请求
                                if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                    socketWrapper.close();
                                }
                                break;
                            case OPEN:
                                // 保持活动，重新注册读事件
                                reg(sk, socketWrapper, SelectionKey.OP_READ);
                                break;
                        }
                    }
                    return SendfileState.DONE;
                } else {
                    // 未完成传输，注册写事件
                    if (log.isTraceEnabled()) {
                        log.trace("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (calledByProcessor) {
                        add(socketWrapper, SelectionKey.OP_WRITE);
                    } else {
                        reg(sk, socketWrapper, SelectionKey.OP_WRITE);
                    }
                    return SendfileState.PENDING;
                }
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.sendfile.error"), e);
                }
                if (!calledByProcessor && sc != null) {
                    socketWrapper.close();
                }
                return SendfileState.ERROR;
            } catch (Throwable t) {
                log.error(sm.getString("endpoint.sendfile.error"), t);
                if (!calledByProcessor && sc != null) {
                    socketWrapper.close();
                }
                return SendfileState.ERROR;
            }
        }

        /** 取消注册操作 */
        protected void unreg(SelectionKey sk, NioSocketWrapper socketWrapper, int readyOps) {
            reg(sk, socketWrapper, sk.interestOps() & (~readyOps)); // 清除已就绪操作
        }

        /** 注册操作 */
        protected void reg(SelectionKey sk, NioSocketWrapper socketWrapper, int intops) {
            sk.interestOps(intops); // 更新选择键感兴趣的操作
            socketWrapper.interestOps(intops); // 更新包装器感兴趣的操作
        }

        /** 处理超时检查 */
        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // 优化超时检查频率：仅在必要时执行
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            int keycount = 0;
            try {
                // 遍历所有注册的键检查超时
                for (SelectionKey key : selector.keys()) {
                    keycount++;
                    NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
                    try {
                        if (socketWrapper == null) {
                            // 无附件的键直接取消
                            if (key.isValid()) {
                                key.cancel();
                            }
                        } else if (close) {
                            // 关闭时清除操作并关闭套接字
                            key.interestOps(0);
                            socketWrapper.interestOps(0);
                            socketWrapper.close();
                        } else if (socketWrapper.interestOpsHas(SelectionKey.OP_READ) ||
                            socketWrapper.interestOpsHas(SelectionKey.OP_WRITE)) {
                            // 检查读/写超时
                            boolean readTimeout = false;
                            boolean writeTimeout = false;
                            if (socketWrapper.interestOpsHas(SelectionKey.OP_READ)) {
                                long delta = now - socketWrapper.getLastRead();
                                long timeout = socketWrapper.getReadTimeout();
                                if (timeout > 0 && delta > timeout) {
                                    readTimeout = true;
                                }
                            }
                            if (!readTimeout && socketWrapper.interestOpsHas(SelectionKey.OP_WRITE)) {
                                long delta = now - socketWrapper.getLastWrite();
                                long timeout = socketWrapper.getWriteTimeout();
                                if (timeout > 0 && delta > timeout) {
                                    writeTimeout = true;
                                }
                            }
                            if (readTimeout || writeTimeout) {
                                // 超时处理：清除操作并触发错误事件
                                key.interestOps(0);
                                socketWrapper.interestOps(0);
                                socketWrapper.setError(new SocketTimeoutException());
                                if (readTimeout && socketWrapper.readOperation != null) {
                                    if (!socketWrapper.readOperation.process()) {
                                        socketWrapper.close();
                                    }
                                } else if (writeTimeout && socketWrapper.writeOperation != null) {
                                    if (!socketWrapper.writeOperation.process()) {
                                        socketWrapper.close();
                                    }
                                } else if (!processSocket(socketWrapper, SocketEvent.ERROR, true)) {
                                    socketWrapper.close();
                                }
                            }
                        }
                    } catch (CancelledKeyException ckx) {
                        if (socketWrapper != null) {
                            socketWrapper.close();
                        }
                    }
                }
            } catch (ConcurrentModificationException cme) {
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            // 更新下一次超时检查时间
            long prevExp = nextExpiration;
            nextExpiration = System.currentTimeMillis() + socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount + "; now=" + now + "; nextExpiration=" +
                    prevExp + "; keyCount=" + keyCount + "; hasEvents=" + hasEvents + "; eval=" +
                    ((now < prevExp) && (keyCount > 0 || hasEvents) && (!close)));
            }
        }
    }

    // --------------------------------------------------- 套接字包装器类
    public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> {
        private final SynchronizedStack<NioChannel> nioChannels; // NIO通道缓存
        private final Poller poller; // Poller实例

        private int interestOps = 0; // 感兴趣的操作
        private volatile SendfileData sendfileData = null; // 文件发送数据
        private volatile long lastRead = System.currentTimeMillis(); // 最后读时间
        private volatile long lastWrite = lastRead; // 最后写时间

        private final Object readLock; // 读操作锁
        private volatile boolean readBlocking = false; // 读阻塞标志
        private final Object writeLock; // 写操作锁
        private volatile boolean writeBlocking = false; // 写阻塞标志

        public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
            super(channel, endpoint);
            if (endpoint.getUnixDomainSocketPath() != null) {
                // Unix域套接字设置为本地地址（兼容性处理）
                localAddr = "127.0.0.1";
                localName = "localhost";
                localPort = 0;
                remoteAddr = "127.0.0.1";
                remoteHost = "localhost";
                remotePort = 0;
            }
            nioChannels = endpoint.getNioChannels();
            poller = endpoint.getPoller();
            socketBufferHandler = channel.getBufHandler();
            readLock = (readPending == null) ? new Object() : readPending;
            writeLock = (writePending == null) ? new Object() : writePending;
        }

        public Poller getPoller() { return poller; }
        public int interestOps() { return interestOps; }
        public int interestOps(int ops) { this.interestOps = ops; return ops; }
        public boolean interestOpsHas(int targetOp) { return (interestOps() & targetOp) == targetOp; }

        public void setSendfileData(SendfileData sf) { this.sendfileData = sf; }
        public SendfileData getSendfileData() { return this.sendfileData; }

        public void updateLastWrite() { lastWrite = System.currentTimeMillis(); }
        public long getLastWrite() { return lastWrite; }
        public void updateLastRead() { lastRead = System.currentTimeMillis(); }
        public long getLastRead() { return lastRead; }

        /** 检查是否准备好读取数据 */
        @Override
        public boolean isReadyForRead() throws IOException {
            socketBufferHandler.configureReadBufferForRead();
            if (socketBufferHandler.getReadBuffer().remaining() > 0) {
                return true;
            }
            fillReadBuffer(false); // 非阻塞填充读缓冲区
            return socketBufferHandler.getReadBuffer().position() > 0;
        }

        /** 从套接字读取数据（字节数组） */
        @Override
        public int read(boolean block, byte[] b, int off, int len) throws IOException {
            int nRead = populateReadBuffer(b, off, len);
            if (nRead > 0) {
                return nRead;
            }
            // 阻塞或非阻塞填充读缓冲区
            nRead = fillReadBuffer(block);
            updateLastRead();
            // 从缓冲区转移数据到目标数组
            if (nRead > 0) {
                socketBufferHandler.configureReadBufferForRead();
                nRead = Math.min(nRead, len);
                socketBufferHandler.getReadBuffer().get(b, off, nRead);
            }
            return nRead;
        }

        /** 从套接字读取数据（ByteBuffer） */
        @Override
        public int read(boolean block, ByteBuffer to) throws IOException {
            int nRead = populateReadBuffer(to);
            if (nRead > 0) {
                return nRead;
            }
            int limit = socketBufferHandler.getReadBuffer().capacity();
            if (to.remaining() >= limit) {
                // 直接从套接字读取（适合大缓冲区）
                to.limit(to.position() + limit);
                nRead = fillReadBuffer(block, to);
                updateLastRead();
            } else {
                // 先填充缓冲区再转移（适合小缓冲区）
                nRead = fillReadBuffer(block);
                if (nRead > 0) {
                    nRead = populateReadBuffer(to);
                }
            }
            return nRead;
        }

        /** 关闭套接字（释放资源） */
        @Override
        protected void doClose() {
            if (log.isTraceEnabled()) {
                log.trace("Calling [" + getEndpoint() + "].closeSocket([" + this + "])");
            }
            try {
                getEndpoint().connections.remove(getSocket().getIOChannel());
                if (getSocket().isOpen()) {
                    getSocket().close(true); // 关闭通道
                }
                // 回收通道到缓存
                if (getEndpoint().running) {
                    if (nioChannels == null || !nioChannels.push(getSocket())) {
                        getSocket().free();
                    }
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.debug.channelCloseFail"), e);
                }
            } finally {
                socketBufferHandler = SocketBufferHandler.EMPTY;
                nonBlockingWriteBuffer.clear();
                reset(NioChannel.CLOSED_NIO_CHANNEL);
            }
            // 关闭文件发送通道（如有）
            try {
                SendfileData data = getSendfileData();
                if (data != null && data.fchannel != null && data.fchannel.isOpen()) {
                    data.fchannel.close();
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.sendfile.closeError"), e);
                }
            }
        }

        /** 阻塞或非阻塞填充读缓冲区 */
        private int fillReadBuffer(boolean block) throws IOException {
            socketBufferHandler.configureReadBufferForWrite();
            return fillReadBuffer(block, socketBufferHandler.getReadBuffer());
        }

        /** 填充读缓冲区（核心读操作） */
        private int fillReadBuffer(boolean block, ByteBuffer buffer) throws IOException {
            int n;
            if (getSocket() == NioChannel.CLOSED_NIO_CHANNEL) {
                throw new ClosedChannelException();
            }
            if (block) {
                // 阻塞读操作（带超时处理）
                long timeout = getReadTimeout();
                long startNanos = 0;
                do {
                    if (startNanos > 0) {
                        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                        if (elapsedMillis == 0) elapsedMillis = 1;
                        timeout -= elapsedMillis;
                        if (timeout <= 0) {
                            throw new SocketTimeoutException();
                        }
                    }
                    synchronized (readLock) {
                        n = getSocket().read(buffer);
                        if (n == -1) {
                            throw new EOFException();
                        } else if (n == 0) {
                            // 无数据可读，注册读事件并等待
                            if (!readBlocking) {
                                readBlocking = true;
                                registerReadInterest();
                            }
                            try {
                                if (timeout > 0) {
                                    startNanos = System.nanoTime();
                                    readLock.wait(timeout);
                                } else {
                                    readLock.wait();
                                }
                            } catch (InterruptedException e) {}
                        }
                    }
                } while (n == 0); // TLS可能返回0但仍有数据
            } else {
                // 非阻塞读操作
                n = getSocket().read(buffer);
                if (n == -1) {
                    throw new EOFException();
                }
            }
            return n;
        }

        /** 刷新非阻塞写操作 */
        @Override
        protected boolean flushNonBlocking() throws IOException {
            boolean dataLeft = socketOrNetworkBufferHasDataLeft(); // 检查缓冲区是否有数据

            // 先写入套接字（如有数据）
            if (dataLeft) {
                doWrite(false);
                dataLeft = socketOrNetworkBufferHasDataLeft();
            }

            if (!dataLeft && !nonBlockingWriteBuffer.isEmpty()) {
                // 写入非阻塞缓冲区数据
                dataLeft = nonBlockingWriteBuffer.write(this, false);
                if (!dataLeft && socketOrNetworkBufferHasDataLeft()) {
                    doWrite(false);
                    dataLeft = socketOrNetworkBufferHasDataLeft();
                }
            }
            return dataLeft;
        }

        /** 检查套接字或网络缓冲区是否有剩余数据 */
        private boolean socketOrNetworkBufferHasDataLeft() {
            return !socketBufferHandler.isWriteBufferEmpty() || getSocket().getOutboundRemaining() > 0;
        }

        /** 写操作（阻塞或非阻塞） */
        @Override
        protected void doWrite(boolean block, ByteBuffer buffer) throws IOException {
            int n;
            if (getSocket() == NioChannel.CLOSED_NIO_CHANNEL) {
                throw new ClosedChannelException();
            }
            if (block) {
                // 阻塞写操作（带超时处理）
                if (previousIOException != null) {
                    throw new IOException(previousIOException); // 处理之前的IO异常
                }
                long timeout = getWriteTimeout();
                long startNanos = 0;
                do {
                    if (startNanos > 0) {
                        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                        if (elapsedMillis == 0) elapsedMillis = 1;
                        timeout -= elapsedMillis;
                        if (timeout <= 0) {
                            previousIOException = new SocketTimeoutException();
                            throw previousIOException;
                        }
                    }
                    synchronized (writeLock) {
                        n = getSocket().write(buffer);
                        // 处理部分写入或等待写入完成
                        if (n == 0 && (buffer.hasRemaining() || getSocket().getOutboundRemaining() > 0)) {
                            if (!writeBlocking) {
                                writeBlocking = true;
                                registerWriteInterest();
                            }
                            try {
                                if (timeout > 0) {
                                    startNanos = System.nanoTime();
                                    writeLock.wait(timeout);
                                } else {
                                    writeLock.wait();
                                }
                            } catch (InterruptedException e) {}
                        } else if (startNanos > 0) {
                            timeout = getWriteTimeout(); // 重置超时
                            startNanos = 0;
                        }
                    }
                } while (buffer.hasRemaining() || getSocket().getOutboundRemaining() > 0);
            } else {
                // 非阻塞写操作
                do {
                    n = getSocket().write(buffer);
                } while (n > 0 && buffer.hasRemaining());
            }
            updateLastWrite(); // 更新最后写时间
        }

        /** 注册读事件 */
        @Override
        public void registerReadInterest() {
            if (log.isTraceEnabled()) {
                log.trace(sm.getString("endpoint.debug.registerRead", this));
            }
            getPoller().add(this, SelectionKey.OP_READ);
        }

        /** 注册写事件 */
        @Override
        public void registerWriteInterest() {
            if (log.isTraceEnabled()) {
                log.trace(sm.getString("endpoint.debug.registerWrite", this));
            }
            getPoller().add(this, SelectionKey.OP_WRITE);
        }

        /** 创建文件发送数据对象 */
        @Override
        public SendfileDataBase createSendfileData(String filename, long pos, long length) {
            return new SendfileData(filename, pos, length);
        }

        /** 处理文件发送操作 */
        @Override
        public SendfileState processSendfile(SendfileDataBase sendfileData) {
            setSendfileData((SendfileData) sendfileData);
            SelectionKey key = getSocket().getIOChannel().keyFor(getPoller().getSelector());
            if (key == null) {
                return SendfileState.ERROR;
            } else {
                // 在当前线程执行首次写操作
                return getPoller().processSendfile(key, this, true);
            }
        }

        /** 填充远程地址信息 */
        @Override
        protected void populateRemoteAddr() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getInetAddress();
                if (inetAddr != null) {
                    remoteAddr = inetAddr.getHostAddress();
                }
            }
        }

        /** 填充远程主机名 */
        @Override
        protected void populateRemoteHost() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getInetAddress();
                if (inetAddr != null) {
                    remoteHost = inetAddr.getHostName();
                    if (remoteAddr == null) {
                        remoteAddr = inetAddr.getHostAddress();
                    }
                }
            }
        }

        /** 填充远程端口 */
        @Override
        protected void populateRemotePort() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                remotePort = sc.socket().getPort();
            }
        }

        /** 填充本地主机名 */
        @Override
        protected void populateLocalName() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getLocalAddress();
                if (inetAddr != null) {
                    localName = inetAddr.getHostName();
                }
            }
        }

        /** 填充本地地址 */
        @Override
        protected void populateLocalAddr() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getLocalAddress();
                if (inetAddr != null) {
                    localAddr = inetAddr.getHostAddress();
                }
            }
        }

        /** 填充本地端口 */
        @Override
        protected void populateLocalPort() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                localPort = sc.socket().getLocalPort();
            }
        }

        /** 获取SSL支持实例 */
        @Override
        public SSLSupport getSslSupport() {
            if (getSocket() instanceof SecureNioChannel) {
                SecureNioChannel ch = (SecureNioChannel) getSocket();
                return ch.getSSLSupport();
            }
            return null;
        }

        /** 执行客户端认证 */
        @Override
        public void doClientAuth(SSLSupport sslSupport) throws IOException {
            SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
            SSLEngine engine = sslChannel.getSslEngine();
            if (!engine.getNeedClientAuth()) {
                // 需要重新协商SSL连接
                engine.setNeedClientAuth(true);
                sslChannel.rehandshake(getEndpoint().getConnectionTimeout());
                ((JSSESupport) sslSupport).setSession(engine.getSession());
            }
        }

        /** 设置应用读缓冲区处理器 */
        @Override
        public void setAppReadBufHandler(ApplicationBufferHandler handler) {
            getSocket().setAppReadBufHandler(handler);
        }

        /** 创建操作状态对象（用于异步I/O） */
        @Override
        protected <A> OperationState<A> newOperationState(boolean read, ByteBuffer[] buffers, int offset, int length,
                                                          BlockingMode block, long timeout, TimeUnit unit, A attachment, CompletionCheck check,
                                                          CompletionHandler<Long,? super A> handler, Semaphore semaphore,
                                                          VectoredIOCompletionHandler<A> completion) {
            return new NioOperationState<>(read, buffers, offset, length, block, timeout, unit, attachment, check,
                handler, semaphore, completion);
        }

        /** NIO操作状态内部类（处理异步I/O操作） */
        private class NioOperationState<A> extends OperationState<A> {
            private volatile boolean inline = true; // 是否内联处理

            private NioOperationState(boolean read, ByteBuffer[] buffers, int offset, int length, BlockingMode block,
                                      long timeout, TimeUnit unit, A attachment, CompletionCheck check,
                                      CompletionHandler<Long,? super A> handler, Semaphore semaphore,
                                      VectoredIOCompletionHandler<A> completion) {
                super(read, buffers, offset, length, block, timeout, unit, attachment, check, handler, semaphore,
                    completion);
            }

            @Override
            protected boolean isInline() { return inline; }
            @Override
            protected boolean hasOutboundRemaining() { return getSocket().getOutboundRemaining() > 0; }

            @Override
            public void run() {
                long nBytes = 0;
                if (getError() == null) {
                    try {
                        synchronized (this) {
                            if (!completionDone) {
                                // 避免并发处理同一事件
                                if (log.isTraceEnabled()) {
                                    log.trace("Skip concurrent " + (read ? "read" : "write") + " notification");
                                }
                                return;
                            }
                            if (read) {
                                // 先读取主缓冲区数据
                                if (!socketBufferHandler.isReadBufferEmpty()) {
                                    socketBufferHandler.configureReadBufferForRead();
                                    for (int i = 0; i < length && !socketBufferHandler.isReadBufferEmpty(); i++) {
                                        nBytes += transfer(socketBufferHandler.getReadBuffer(), buffers[offset + i]);
                                    }
                                }
                                if (nBytes == 0) {
                                    nBytes = getSocket().read(buffers, offset, length); // 从套接字读取
                                    updateLastRead();
                                }
                            } else {
                                boolean doWrite = true;
                                // 先写入主缓冲区数据
                                if (socketOrNetworkBufferHasDataLeft()) {
                                    socketBufferHandler.configureWriteBufferForRead();
                                    do {
                                        nBytes = getSocket().write(socketBufferHandler.getWriteBuffer());
                                    } while (socketOrNetworkBufferHasDataLeft() && nBytes > 0);
                                    if (socketOrNetworkBufferHasDataLeft()) {
                                        doWrite = false;
                                    }
                                    if (nBytes > 0) nBytes = 0; // 重置为0（非错误）
                                }
                                if (doWrite) {
                                    // 直接写入目标缓冲区
                                    long n;
                                    do {
                                        n = getSocket().write(buffers, offset, length);
                                        if (n == -1) {
                                            nBytes = n;
                                        } else {
                                            nBytes += n;
                                        }
                                    } while (n > 0);
                                    updateLastWrite();
                                }
                            }
                            if (nBytes != 0 || (!buffersArrayHasRemaining(buffers, offset, length) &&
                                (read || !socketOrNetworkBufferHasDataLeft()))) {
                                completionDone = false;
                            }
                        }
                    } catch (IOException e) {
                        setError(e);
                    }
                }
                if (nBytes > 0 || (nBytes == 0 && !buffersArrayHasRemaining(buffers, offset, length) &&
                    (read || !socketOrNetworkBufferHasDataLeft()))) {
                    // 操作完成
                    completion.completed(Long.valueOf(nBytes), this);
                } else if (nBytes < 0 || getError() != null) {
                    // 操作失败
                    IOException error = getError();
                    if (error == null) {
                        error = new EOFException();
                    }
                    completion.failed(error, this);
                } else {
                    // 需要继续等待（切换为非内联模式）
                    inline = false;
                    if (read) {
                        registerReadInterest();
                    } else {
                        registerWriteInterest();
                    }
                }
            }
        }
    }

    // ---------------------------------------------- 套接字处理器内部类
    /**
     * 套接字处理器：处理请求逻辑的工作线程
     */
    protected class SocketProcessor extends SocketProcessorBase<NioChannel> {
        public SocketProcessor(SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
            super(socketWrapper, event);
        }

        /** 执行请求处理逻辑 */
        @Override
        protected void doRun() {
            Poller poller = NioEndpoint.this.poller;
            if (poller == null) {
                socketWrapper.close();
                return;
            }
            try {
                int handshake;
                try {
                    if (socketWrapper.getSocket().isHandshakeComplete()) {
                        // TLS握手已完成，直接处理请求
                        handshake = 0;
                    } else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT ||
                        event == SocketEvent.ERROR) {
                        // 握手失败
                        handshake = -1;
                    } else {
                        // 执行TLS握手
                        handshake = socketWrapper.getSocket().handshake(event == SocketEvent.OPEN_READ,
                            event == SocketEvent.OPEN_WRITE);
                        event = SocketEvent.OPEN_READ; // 握手后统一为读事件
                    }
                } catch (IOException x) {
                    handshake = -1;
                    if (logHandshake.isDebugEnabled()) {
                        logHandshake.debug(sm.getString("endpoint.err.handshake", socketWrapper.getRemoteAddr(),
                            Integer.toString(socketWrapper.getRemotePort())), x);
                    }
                } catch (CancelledKeyException ckx) {
                    handshake = -1;
                }
                if (handshake == 0) {
                    // 握手成功，处理请求
                    SocketState state = getHandler().process(socketWrapper,
                        Objects.requireNonNullElse(event, SocketEvent.OPEN_READ));
                    if (state == SocketState.CLOSED) {
                        socketWrapper.close();
                    }
                } else if (handshake == -1) {
                    // 握手失败，处理连接失败事件
                    getHandler().process(socketWrapper, SocketEvent.CONNECT_FAIL);
                    socketWrapper.close();
                } else if (handshake == SelectionKey.OP_READ) {
                    // 注册读事件
                    socketWrapper.registerReadInterest();
                } else if (handshake == SelectionKey.OP_WRITE) {
                    // 注册写事件
                    socketWrapper.registerWriteInterest();
                }
            } catch (CancelledKeyException cx) {
                socketWrapper.close();
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            } catch (Throwable t) {
                log.error(sm.getString("endpoint.processing.fail"), t);
                socketWrapper.close();
            } finally {
                // 回收处理器实例
                if (running && processorCache != null) {
                    processorCache.push(this);
                }
            }
        }
    }

    // ----------------------------------------------- 文件发送数据类
    /**
     * 文件发送数据类：存储sendfile操作的上下文
     */
    public static class SendfileData extends SendfileDataBase {
        public SendfileData(String filename, long pos, long length) {
            super(filename, pos, length);
        }
        protected volatile FileChannel fchannel; // 文件通道
    }
}