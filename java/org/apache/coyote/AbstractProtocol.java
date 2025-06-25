/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.coyote;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistration;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.juli.logging.Log;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.apache.tomcat.util.res.StringManager;

/**
 * 协议处理器的抽象基类
 * 实现了ProtocolHandler接口和MBeanRegistration接口
 * 提供了协议处理的基本框架和通用功能
 */
public abstract class AbstractProtocol<S> implements ProtocolHandler, MBeanRegistration {

    /** 字符串资源管理器，用于获取国际化提示信息 */
    private static final StringManager sm = StringManager.getManager(AbstractProtocol.class);

    /** 用于生成自动绑定端口的连接器的唯一JMX名称的计数器 */
    private static final AtomicInteger nameCounter = new AtomicInteger(0);

    /** 连接器的唯一ID，仅在连接器配置为使用随机端口时使用 */
    private int nameIndex = 0;

    /** 提供底层网络I/O的端点，必须与ProtocolHandler实现匹配 */
    private final AbstractEndpoint<S,?> endpoint;

    /** 处理器的处理程序 */
    private Handler<S> handler;

    /** 等待处理的处理器集合 */
    private final Set<Processor> waitingProcessors = ConcurrentHashMap.newKeySet();

    /** 超时调度的未来任务 */
    private ScheduledFuture<?> timeoutFuture = null;
    private ScheduledFuture<?> monitorFuture;

    /**
     * 构造函数
     * @param endpoint 关联的端点实例
     */
    public AbstractProtocol(AbstractEndpoint<S,?> endpoint) {
        this.endpoint = endpoint;
        ConnectionHandler<S> cHandler = new ConnectionHandler<>(this);
        getEndpoint().setHandler(cHandler);
        setHandler(cHandler);
        setConnectionLinger(Constants.DEFAULT_CONNECTION_LINGER);
        setTcpNoDelay(Constants.DEFAULT_TCP_NO_DELAY);
    }

    // ----------------------------------------------- 通用属性处理

    /**
     * 通用属性设置方法，由Digester使用
     * @param name 属性名称
     * @param value 属性值（字符串形式）
     * @return 如果属性设置成功返回true，否则返回false
     */
    public boolean setProperty(String name, String value) {
        return endpoint.setProperty(name, value);
    }

    /**
     * 通用属性获取方法，由Digester使用
     * @param name 属性名称
     * @return 属性值的字符串表示
     */
    public String getProperty(String name) {
        return endpoint.getProperty(name);
    }

    // ------------------------------- 由ProtocolHandler管理的属性

    /** 全局请求处理器的MBean名称 */
    protected ObjectName rgOname = null;

    public ObjectName getGlobalRequestProcessorMBeanName() {
        return rgOname;
    }

    /** 提供ProtocolHandler和连接器之间的链接的适配器 */
    protected Adapter adapter;

    @Override
    public void setAdapter(Adapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public Adapter getAdapter() {
        return adapter;
    }

    /**
     * 处理器缓存中保留的空闲处理器的最大数量
     * 默认值为200，-1表示无限制
     */
    protected int processorCache = 200;

    public int getProcessorCache() {
        return this.processorCache;
    }

    public void setProcessorCache(int processorCache) {
        this.processorCache = processorCache;
    }

    /** 客户端证书提供者名称 */
    private String clientCertProvider = null;

    /**
     * 获取客户端证书提供者名称
     * @return JSSE提供者名称
     */
    public String getClientCertProvider() {
        return clientCertProvider;
    }

    public void setClientCertProvider(String s) {
        this.clientCertProvider = s;
    }

    /** 最大头部数量 */
    private int maxHeaderCount = 100;

    public int getMaxHeaderCount() {
        return maxHeaderCount;
    }

    public void setMaxHeaderCount(int maxHeaderCount) {
        this.maxHeaderCount = maxHeaderCount;
    }

    @Override
    public boolean isSendfileSupported() {
        return endpoint.getUseSendfile();
    }

    @Override
    public String getId() {
        return endpoint.getId();
    }

    // ---------------------- 传递给Endpoint的属性

    @Override
    public Executor getExecutor() {
        return endpoint.getExecutor();
    }

    @Override
    public void setExecutor(Executor executor) {
        endpoint.setExecutor(executor);
    }

    @Override
    public ScheduledExecutorService getUtilityExecutor() {
        return endpoint.getUtilityExecutor();
    }

    @Override
    public void setUtilityExecutor(ScheduledExecutorService utilityExecutor) {
        endpoint.setUtilityExecutor(utilityExecutor);
    }

    public int getMaxThreads() {
        return endpoint.getMaxThreads();
    }

    public void setMaxThreads(int maxThreads) {
        endpoint.setMaxThreads(maxThreads);
    }

    public int getMaxConnections() {
        return endpoint.getMaxConnections();
    }

    public void setMaxConnections(int maxConnections) {
        endpoint.setMaxConnections(maxConnections);
    }

    public int getMinSpareThreads() {
        return endpoint.getMinSpareThreads();
    }

    public void setMinSpareThreads(int minSpareThreads) {
        endpoint.setMinSpareThreads(minSpareThreads);
    }

    public int getThreadPriority() {
        return endpoint.getThreadPriority();
    }

    public void setThreadPriority(int threadPriority) {
        endpoint.setThreadPriority(threadPriority);
    }

    public int getMaxQueueSize() {
        return endpoint.getMaxQueueSize();
    }

    public void setMaxQueueSize(int maxQueueSize) {
        endpoint.setMaxQueueSize(maxQueueSize);
    }

    public int getAcceptCount() {
        return endpoint.getAcceptCount();
    }

    public void setAcceptCount(int acceptCount) {
        endpoint.setAcceptCount(acceptCount);
    }

    public boolean getTcpNoDelay() {
        return endpoint.getTcpNoDelay();
    }

    public void setTcpNoDelay(boolean tcpNoDelay) {
        endpoint.setTcpNoDelay(tcpNoDelay);
    }

    public int getConnectionLinger() {
        return endpoint.getConnectionLinger();
    }

    public void setConnectionLinger(int connectionLinger) {
        endpoint.setConnectionLinger(connectionLinger);
    }

    /**
     * Tomcat等待后续请求的时间，默认值为getConnectionTimeout()
     * @return 超时时间（毫秒）
     */
    public int getKeepAliveTimeout() {
        return endpoint.getKeepAliveTimeout();
    }

    public void setKeepAliveTimeout(int keepAliveTimeout) {
        endpoint.setKeepAliveTimeout(keepAliveTimeout);
    }

    public InetAddress getAddress() {
        return endpoint.getAddress();
    }

    public void setAddress(InetAddress ia) {
        endpoint.setAddress(ia);
    }

    public int getPort() {
        return endpoint.getPort();
    }

    public void setPort(int port) {
        endpoint.setPort(port);
    }

    public int getPortOffset() {
        return endpoint.getPortOffset();
    }

    public void setPortOffset(int portOffset) {
        endpoint.setPortOffset(portOffset);
    }

    public int getPortWithOffset() {
        return endpoint.getPortWithOffset();
    }

    public int getLocalPort() {
        return endpoint.getLocalPort();
    }

    /**
     * 当Tomcat期望从客户端获取数据时，等待数据到达的时间
     * @return 超时时间（毫秒）
     */
    public int getConnectionTimeout() {
        return endpoint.getConnectionTimeout();
    }

    public void setConnectionTimeout(int timeout) {
        endpoint.setConnectionTimeout(timeout);
    }

    public long getConnectionCount() {
        return endpoint.getConnectionCount();
    }

    public void setAcceptorThreadPriority(int threadPriority) {
        endpoint.setAcceptorThreadPriority(threadPriority);
    }

    public int getAcceptorThreadPriority() {
        return endpoint.getAcceptorThreadPriority();
    }

    // ---------------------------------------------------------- 公共方法

    /**
     * 获取名称索引
     * @return 名称索引
     */
    public synchronized int getNameIndex() {
        if (nameIndex == 0) {
            nameIndex = nameCounter.incrementAndGet();
        }
        return nameIndex;
    }

    /**
     * 获取此协议实例的名称，适合在ObjectName中使用
     * @return 经过适当转义的名称
     */
    public String getName() {
        return ObjectName.quote(getNameInternal());
    }

    /**
     * 获取内部名称
     * @return 内部名称
     */
    private String getNameInternal() {
        StringBuilder name = new StringBuilder(getNamePrefix());
        name.append('-');
        String id = getId();
        if (id != null) {
            name.append(id);
        } else {
            if (getAddress() != null) {
                name.append(getAddress().getHostAddress());
                name.append('-');
            }
            int port = getPortWithOffset();
            if (port == 0) {
                name.append("auto-");
                name.append(getNameIndex());
                port = getLocalPort();
                if (port != -1) {
                    name.append('-');
                    name.append(port);
                }
            } else {
                name.append(port);
            }
        }
        return name.toString();
    }

    /**
     * 添加等待处理的处理器
     * @param processor 等待处理的处理器
     */
    public void addWaitingProcessor(Processor processor) {
        if (getLog().isTraceEnabled()) {
            getLog().trace(sm.getString("abstractProtocol.waitingProcessor.add", processor));
        }
        waitingProcessors.add(processor);
    }

    /**
     * 移除等待处理的处理器
     * @param processor 等待处理的处理器
     */
    public void removeWaitingProcessor(Processor processor) {
        boolean result = waitingProcessors.remove(processor);
        if (getLog().isTraceEnabled()) {
            getLog().trace(sm.getString("abstractProtocol.waitingProcessor.remove", processor, Boolean.valueOf(result)));
        }
    }

    /**
     * 获取等待处理的处理器数量
     * @return 等待处理的处理器数量
     */
    public int getWaitingProcessorCount() {
        return waitingProcessors.size();
    }

    // ----------------------------------------------- 子类访问方法

    protected AbstractEndpoint<S,?> getEndpoint() {
        return endpoint;
    }

    public Handler<S> getHandler() {
        return handler;
    }

    protected void setHandler(Handler<S> handler) {
        this.handler = handler;
    }

    // -------------------------------------------------------- 抽象方法

    /**
     * 具体实现需要提供对其日志记录器的访问
     * @return 日志记录器
     */
    protected abstract Log getLog();

    /**
     * 获取用于构造此协议处理器名称的前缀
     * @return 名称前缀
     */
    protected abstract String getNamePrefix();

    /**
     * 获取协议名称
     * @return 协议名称
     */
    protected abstract String getProtocolName();

    /**
     * 查找适合网络层协商协议的处理器
     * @param name 请求的协商协议名称
     * @return 与请求协议匹配的UpgradeProtocol实例
     */
    protected abstract UpgradeProtocol getNegotiatedProtocol(String name);

    /**
     * 查找适合升级协议名称的处理器
     * @param name 请求的升级协议名称
     * @return 与请求协议匹配的UpgradeProtocol实例
     */
    protected abstract UpgradeProtocol getUpgradeProtocol(String name);

    /**
     * 创建并配置当前协议实现的新处理器实例
     * @return 完全配置好的处理器实例
     */
    protected abstract Processor createProcessor();

    /**
     * 创建升级处理器
     * @param socket 套接字包装器
     * @param upgradeToken 升级令牌
     * @return 升级处理器实例
     */
    protected abstract Processor createUpgradeProcessor(SocketWrapperBase<?> socket, UpgradeToken upgradeToken);

    // ----------------------------------------------------- JMX相关方法

    protected String domain;
    protected ObjectName oname;
    protected MBeanServer mserver;

    public ObjectName getObjectName() {
        return oname;
    }

    public String getDomain() {
        return domain;
    }

    @Override
    public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
        oname = name;
        mserver = server;
        domain = name.getDomain();
        return name;
    }

    @Override
    public void postRegister(Boolean registrationDone) {
        // 无操作
    }

    @Override
    public void preDeregister() throws Exception {
        // 无操作
    }

    @Override
    public void postDeregister() {
        // 无操作
    }

    /**
     * 创建ObjectName
     * @return ObjectName实例
     * @throws MalformedObjectNameException 如果ObjectName格式错误
     */
    private ObjectName createObjectName() throws MalformedObjectNameException {
        domain = getAdapter().getDomain();
        if (domain == null) {
            return null;
        }
        StringBuilder name = new StringBuilder(getDomain());
        name.append(":type=ProtocolHandler,port=");
        int port = getPortWithOffset();
        if (port > 0) {
            name.append(port);
        } else {
            name.append("auto-");
            name.append(getNameIndex());
        }
        InetAddress address = getAddress();
        if (address != null) {
            name.append(",address=");
            name.append(ObjectName.quote(address.getHostAddress()));
        }
        return new ObjectName(name.toString());
    }

    // ------------------------------------------------------- 生命周期方法

    @Override
    public void init() throws Exception {
        if (getLog().isInfoEnabled()) {
            getLog().info(sm.getString("abstractProtocolHandler.init", getName()));
            logPortOffset();
        }
        if (oname == null) {
            oname = createObjectName();
            if (oname != null) {
                Registry.getRegistry(null).registerComponent(this, oname, null);
            }
        }
        if (this.domain != null) {
            ObjectName rgOname = new ObjectName(domain + ":type=GlobalRequestProcessor,name=" + getName());
            this.rgOname = rgOname;
            Registry.getRegistry(null).registerComponent(getHandler().getGlobal(), rgOname, null);
        }
        String endpointName = getName();
        endpoint.setName(endpointName.substring(1, endpointName.length() - 1));
        endpoint.setDomain(domain);
        endpoint.init();
    }

    @Override
    public void start() throws Exception {
        if (getLog().isInfoEnabled()) {
            getLog().info(sm.getString("abstractProtocolHandler.start", getName()));
            logPortOffset();
        }
        endpoint.start();
        monitorFuture = getUtilityExecutor().scheduleWithFixedDelay(this::startAsyncTimeout, 0, 60, TimeUnit.SECONDS);
    }

    /**
     * 启动异步超时调度
     */
    protected void startAsyncTimeout() {
        if (timeoutFuture == null || timeoutFuture.isDone()) {
            if (timeoutFuture != null && timeoutFuture.isDone()) {
                try {
                    timeoutFuture.get();
                } catch (InterruptedException | ExecutionException e) {
                    getLog().error(sm.getString("abstractProtocolHandler.asyncTimeoutError"), e);
                }
            }
            timeoutFuture = getUtilityExecutor().scheduleAtFixedRate(() -> {
                long now = System.currentTimeMillis();
                for (Processor processor : waitingProcessors) {
                    processor.timeoutAsync(now);
                }
            }, 1, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * 停止异步超时调度
     */
    protected void stopAsyncTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    @Override
    public void pause() throws Exception {
        if (getLog().isInfoEnabled()) {
            getLog().info(sm.getString("abstractProtocolHandler.pause", getName()));
        }
        endpoint.pause();
    }

    public boolean isPaused() {
        return endpoint.isPaused();
    }

    @Override
    public void resume() throws Exception {
        if (getLog().isInfoEnabled()) {
            getLog().info(sm.getString("abstractProtocolHandler.resume", getName()));
        }
        endpoint.resume();
    }

    @Override
    public void stop() throws Exception {
        if (getLog().isInfoEnabled()) {
            getLog().info(sm.getString("abstractProtocolHandler.stop", getName()));
            logPortOffset();
        }
        if (monitorFuture != null) {
            monitorFuture.cancel(true);
            monitorFuture = null;
        }
        stopAsyncTimeout();
        for (Processor processor : waitingProcessors) {
            processor.timeoutAsync(-1);
        }
        endpoint.stop();
    }

    @Override
    public void destroy() throws Exception {
        if (getLog().isInfoEnabled()) {
            getLog().info(sm.getString("abstractProtocolHandler.destroy", getName()));
            logPortOffset();
        }
        try {
            endpoint.destroy();
        } finally {
            if (oname != null) {
                if (mserver == null) {
                    Registry.getRegistry(null).unregisterComponent(oname);
                } else {
                    try {
                        mserver.unregisterMBean(oname);
                    } catch (MBeanRegistrationException | InstanceNotFoundException e) {
                        getLog().info(sm.getString("abstractProtocol.mbeanDeregistrationFailed", oname, mserver));
                    }
                }
            }
            ObjectName rgOname = getGlobalRequestProcessorMBeanName();
            if (rgOname != null) {
                Registry.getRegistry(null).unregisterComponent(rgOname);
            }
        }
    }

    @Override
    public void closeServerSocketGraceful() {
        endpoint.closeServerSocketGraceful();
    }

    @Override
    public long awaitConnectionsClose(long waitMillis) {
        getLog().info(sm.getString("abstractProtocol.closeConnectionsAwait", Long.valueOf(waitMillis), getName()));
        return endpoint.awaitConnectionsClose(waitMillis);
    }

    /**
     * 记录端口偏移信息
     */
    private void logPortOffset() {
        if (getPort() != getPortWithOffset()) {
            getLog().info(sm.getString("abstractProtocolHandler.portOffset", getName(), String.valueOf(getPort()),
                String.valueOf(getPortOffset())));
        }
    }

    // ------------------------------------------- 连接处理程序基类

    /**
     * 连接处理程序类，处理套接字连接和请求分发
     */
    protected static class ConnectionHandler<S> implements AbstractEndpoint.Handler<S> {

        private final AbstractProtocol<S> proto;
        private final RequestGroupInfo global = new RequestGroupInfo();
        private final AtomicLong registerCount = new AtomicLong(0);
        private final RecycledProcessors recycledProcessors = new RecycledProcessors(this);

        public ConnectionHandler(AbstractProtocol<S> proto) {
            this.proto = proto;
        }

        protected AbstractProtocol<S> getProtocol() {
            return proto;
        }

        protected Log getLog() {
            return getProtocol().getLog();
        }

        @Override
        public Object getGlobal() {
            return global;
        }

        @Override
        public void recycle() {
            recycledProcessors.clear();
        }

        @Override
        public SocketState process(SocketWrapperBase<S> wrapper, SocketEvent status) {
            if (getLog().isTraceEnabled()) {
                getLog().trace(sm.getString("abstractConnectionHandler.process", wrapper.getSocket(), status));
            }
            if (wrapper == null) {
                return SocketState.CLOSED;
            }
            S socket = wrapper.getSocket();
            Processor processor = (Processor) wrapper.takeCurrentProcessor();
            if (getLog().isTraceEnabled()) {
                getLog().trace(sm.getString("abstractConnectionHandler.connectionsGet", processor, socket));
            }
            if (SocketEvent.TIMEOUT == status && (processor == null || !processor.isAsync() && !processor.isUpgrade() ||
                processor.isAsync() && !processor.checkAsyncTimeoutGeneration())) {
                return SocketState.OPEN;
            }
            if (processor != null) {
                proto.removeWaitingProcessor(processor);
            } else if (status == SocketEvent.DISCONNECT || status == SocketEvent.ERROR) {
                return SocketState.CLOSED;
            }
            try {
                if (processor == null) {
                    String negotiatedProtocol = wrapper.getNegotiatedProtocol();
                    if (negotiatedProtocol != null && !negotiatedProtocol.isEmpty()) {
                        UpgradeProtocol upgradeProtocol = proto.getNegotiatedProtocol(negotiatedProtocol);
                        if (upgradeProtocol != null) {
                            processor = upgradeProtocol.getProcessor(wrapper, proto.getAdapter());
                            if (getLog().isTraceEnabled()) {
                                getLog().trace(sm.getString("abstractConnectionHandler.processorCreate", processor));
                            }
                        } else if (negotiatedProtocol.equals("http/1.1")) {
                            // 处理默认协议
                        } else {
                            if (getLog().isDebugEnabled()) {
                                getLog().debug(sm.getString("abstractConnectionHandler.negotiatedProcessor.fail",
                                    negotiatedProtocol));
                            }
                            return SocketState.CLOSED;
                        }
                    }
                }
                if (processor == null) {
                    processor = recycledProcessors.pop();
                    if (getLog().isTraceEnabled()) {
                        getLog().trace(sm.getString("abstractConnectionHandler.processorPop", processor));
                    }
                }
                if (processor == null) {
                    processor = proto.createProcessor();
                    register(processor);
                    if (getLog().isTraceEnabled()) {
                        getLog().trace(sm.getString("abstractConnectionHandler.processorCreate", processor));
                    }
                }
                processor.setSslSupport(wrapper.getSslSupport());
                SocketState state;
                do {
                    state = processor.process(wrapper, status);
                    if (state == SocketState.UPGRADING) {
                        UpgradeToken upgradeToken = processor.getUpgradeToken();
                        ByteBuffer leftOverInput = processor.getLeftoverInput();
                        wrapper.unRead(leftOverInput);
                        if (upgradeToken == null) {
                            UpgradeProtocol upgradeProtocol = proto.getUpgradeProtocol("h2c");
                            if (upgradeProtocol != null) {
                                release(processor);
                                processor = upgradeProtocol.getProcessor(wrapper, proto.getAdapter());
                            } else {
                                if (getLog().isDebugEnabled()) {
                                    getLog().debug(sm.getString("abstractConnectionHandler.negotiatedProcessor.fail", "h2c"));
                                }
                                state = SocketState.CLOSED;
                            }
                        } else {
                            HttpUpgradeHandler httpUpgradeHandler = upgradeToken.getHttpUpgradeHandler();
                            release(processor);
                            processor = proto.createUpgradeProcessor(wrapper, upgradeToken);
                            if (getLog().isTraceEnabled()) {
                                getLog().trace(sm.getString("abstractConnectionHandler.upgradeCreate", processor, wrapper));
                            }
                            if (upgradeToken.getInstanceManager() == null) {
                                httpUpgradeHandler.init((WebConnection) processor);
                            } else {
                                ClassLoader oldCL = upgradeToken.getContextBind().bind(false, null);
                                try {
                                    httpUpgradeHandler.init((WebConnection) processor);
                                } finally {
                                    upgradeToken.getContextBind().unbind(false, oldCL);
                                }
                            }
                            if (httpUpgradeHandler instanceof InternalHttpUpgradeHandler) {
                                if (((InternalHttpUpgradeHandler) httpUpgradeHandler).hasAsyncIO()) {
                                    state = SocketState.ASYNC_IO;
                                }
                            }
                        }
                    }
                } while (state == SocketState.UPGRADING);
                if (state == SocketState.LONG) {
                    longPoll(wrapper, processor);
                    if (processor.isAsync()) {
                        proto.addWaitingProcessor(processor);
                    }
                } else if (state == SocketState.OPEN) {
                    release(processor);
                    processor = null;
                    wrapper.registerReadInterest();
                } else if (state == SocketState.SENDFILE) {
                    // 处理sendfile
                } else if (state == SocketState.UPGRADED) {
                    if (status != SocketEvent.OPEN_WRITE) {
                        longPoll(wrapper, processor);
                        proto.addWaitingProcessor(processor);
                    }
                } else if (state == SocketState.ASYNC_IO) {
                    if (status != SocketEvent.OPEN_WRITE) {
                        proto.addWaitingProcessor(processor);
                    }
                } else if (state == SocketState.SUSPENDED) {
                    // 处理挂起状态
                } else {
                    if (processor.isUpgrade()) {
                        UpgradeToken upgradeToken = processor.getUpgradeToken();
                        HttpUpgradeHandler httpUpgradeHandler = upgradeToken.getHttpUpgradeHandler();
                        InstanceManager instanceManager = upgradeToken.getInstanceManager();
                        if (instanceManager == null) {
                            httpUpgradeHandler.destroy();
                        } else {
                            ClassLoader oldCL = upgradeToken.getContextBind().bind(false, null);
                            try {
                                httpUpgradeHandler.destroy();
                            } finally {
                                try {
                                    instanceManager.destroyInstance(httpUpgradeHandler);
                                } catch (Throwable e) {
                                    ExceptionUtils.handleThrowable(e);
                                    getLog().error(sm.getString("abstractConnectionHandler.error"), e);
                                }
                                upgradeToken.getContextBind().unbind(false, oldCL);
                            }
                        }
                    }
                    release(processor);
                    processor = null;
                }
                if (processor != null) {
                    wrapper.setCurrentProcessor(processor);
                }
                return state;
            } catch (SocketException e) {
                getLog().debug(sm.getString("abstractConnectionHandler.socketexception.debug"), e);
            } catch (IOException e) {
                getLog().debug(sm.getString("abstractConnectionHandler.ioexception.debug"), e);
            } catch (ProtocolException e) {
                getLog().debug(sm.getString("abstractConnectionHandler.protocolexception.debug"), e);
            } catch (OutOfMemoryError oome) {
                getLog().error(sm.getString("abstractConnectionHandler.oome"), oome);
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                getLog().error(sm.getString("abstractConnectionHandler.error"), e);
            }
            release(processor);
            return SocketState.CLOSED;
        }

        /**
         * 处理长轮询
         * @param socket 套接字包装器
         * @param processor 处理器
         */
        protected void longPoll(SocketWrapperBase<?> socket, Processor processor) {
            if (!processor.isAsync()) {
                socket.registerReadInterest();
            }
        }

        /**
         * 释放处理器
         * @param processor 要释放的处理器
         */
        private void release(Processor processor) {
            if (processor != null) {
                processor.recycle();
                if (processor.isUpgrade()) {
                    proto.removeWaitingProcessor(processor);
                } else {
                    recycledProcessors.push(processor);
                    if (getLog().isTraceEnabled()) {
                        getLog().trace("Pushed Processor [" + processor + "]");
                    }
                }
            }
        }

        @Override
        public void release(SocketWrapperBase<S> socketWrapper) {
            Processor processor = (Processor) socketWrapper.takeCurrentProcessor();
            release(processor);
        }

        /**
         * 注册处理器
         * @param processor 要注册的处理器
         */
        protected void register(Processor processor) {
            if (proto.getDomain() != null) {
                synchronized (this) {
                    try {
                        long count = registerCount.incrementAndGet();
                        RequestInfo rp = processor.getRequest().getRequestProcessor();
                        rp.setGlobalProcessor(global);
                        ObjectName rpName = new ObjectName(
                            proto.getDomain() + ":type=RequestProcessor,worker=" + proto.getName() +
                                ",name=" + proto.getProtocolName() + "Request" + count);
                        if (getLog().isTraceEnabled()) {
                            getLog().trace("Register [" + processor + "] as [" + rpName + "]");
                        }
                        Registry.getRegistry(null).registerComponent(rp, rpName, null);
                        rp.setRpName(rpName);
                    } catch (Exception e) {
                        getLog().warn(sm.getString("abstractProtocol.processorRegisterError"), e);
                    }
                }
            }
        }

        /**
         * 注销处理器
         * @param processor 要注销的处理器
         */
        protected void unregister(Processor processor) {
            if (proto.getDomain() != null) {
                synchronized (this) {
                    try {
                        Request r = processor.getRequest();
                        if (r == null) {
                            return;
                        }
                        RequestInfo rp = r.getRequestProcessor();
                        rp.setGlobalProcessor(null);
                        ObjectName rpName = rp.getRpName();
                        if (getLog().isTraceEnabled()) {
                            getLog().trace("Unregister [" + rpName + "]");
                        }
                        Registry.getRegistry(null).unregisterComponent(rpName);
                        rp.setRpName(null);
                    } catch (Exception e) {
                        getLog().warn(sm.getString("abstractProtocol.processorUnregisterError"), e);
                    }
                }
            }
        }

        @Override
        public final void pause() {
            for (SocketWrapperBase<S> wrapper : proto.getEndpoint().getConnections()) {
                Processor processor = (Processor) wrapper.getCurrentProcessor();
                if (processor != null) {
                    processor.pause();
                }
            }
        }
    }

    /**
     * 回收处理器类，管理处理器的回收和重用
     */
    protected static class RecycledProcessors extends SynchronizedStack<Processor> {

        private final transient ConnectionHandler<?> handler;
        protected final AtomicInteger size = new AtomicInteger(0);

        public RecycledProcessors(ConnectionHandler<?> handler) {
            this.handler = handler;
        }

        @Override
        public boolean push(Processor processor) {
            int cacheSize = handler.getProtocol().getProcessorCache();
            boolean offer = cacheSize == -1 || size.get() < cacheSize;
            boolean result = false;
            if (offer) {
                result = super.push(processor);
                if (result) {
                    size.incrementAndGet();
                }
            }
            if (!result) {
                handler.unregister(processor);
            }
            return result;
        }

        @Override
        public Processor pop() {
            Processor result = super.pop();
            if (result != null) {
                size.decrementAndGet();
            }
            return result;
        }

        @Override
        public synchronized void clear() {
            Processor next = pop();
            while (next != null) {
                handler.unregister(next);
                next = pop();
            }
            super.clear();
            size.set(0);
        }
    }
}