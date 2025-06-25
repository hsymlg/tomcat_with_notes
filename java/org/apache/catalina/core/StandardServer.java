/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 详细说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.catalina.core;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.AccessControlException;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.catalina.mbeans.MBeanFactory;
import org.apache.catalina.startup.Catalina;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.catalina.util.ServerInfo;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.StringCache;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.TaskThreadFactory;


/**
 * Tomcat服务器的标准实现类，作为顶层容器管理所有Service组件
 * 负责初始化、启动、停止整个Tomcat服务器及内部组件
 *
 * StandardServer包含一个Service数组，用于管理所有服务实例
 * StandardService通过setServer(Server server)方法与StandardServer建立关联
 * 这种关联关系在 Tomcat 启动过程中由配置文件和 Digester 框架自动建立
 *
 * <Server port="8005" shutdown="SHUTDOWN">
 *     <Service name="Catalina">
 *         <!-- 服务配置 -->
 *     </Service>
 * </Server>
 *
 * 生命周期关联：
 * 启动过程：StandardServer.start()会遍历所有StandardService并调用service.start()
 * 停止过程：StandardServer.stop()会遍历所有StandardService并调用service.stop()
 * 组件管理：StandardServer负责管理StandardService的生命周期，而StandardService负责管理自己的组件（Connector、Engine 等）
 *
 * 数据流向关联：
 * 当请求到达 Tomcat 时：
 * StandardServer接收请求并转发给对应的StandardService
 * StandardService通过Connector接收请求
 * StandardService将请求交给关联的Engine处理
 * Engine进一步将请求分发到具体的 Web 应用
 *
 * @author Craig R. McClanahan
 */
public final class StandardServer extends LifecycleMBeanBase implements Server {

    // 日志记录器，用于输出服务器运行时日志
    private static final Log log = LogFactory.getLog(StandardServer.class);
    // 字符串资源管理器，用于获取国际化提示信息
    private static final StringManager sm = StringManager.getManager(StandardServer.class);


    // ------------------------------------------------------------ 构造方法
    /**
     * 初始化StandardServer实例
     * 配置全局JNDI资源和命名上下文监听器
     */
    public StandardServer() {
        super(); // 调用父类LifecycleMBeanBase的构造方法
        // 初始化全局JNDI资源上下文实现类
        globalNamingResources = new NamingResourcesImpl();
        // 将当前Server设置为JNDI资源的所属容器
        globalNamingResources.setContainer(this);
        // 检查是否启用JNDI功能
        if (isUseNaming()) {
            // 创建命名上下文监听器，监听生命周期事件以管理JNDI资源
            namingContextListener = new NamingContextListener();
            // 添加监听器到生命周期事件处理器中
            addLifecycleListener(namingContextListener);
        } else {
            namingContextListener = null; // 不启用JNDI时设为null
        }
    }


    /**
     * volatile 适用场景：
     * 变量被多线程读写，且写操作会影响读操作的结果。
     * 作为状态标志控制线程行为（如 stopAwait）。
     * 防止指令重排序导致的问题（如双重检查锁定）。
     * 替代方案：
     * 使用锁（synchronized、ReentrantLock）保证可见性和原子性。
     * 使用原子类（如 AtomicBoolean）替代 volatile + 原子操作。
     * 不可变对象（final）和线程封闭（Thread-local）避免共享变量。
     */
    // ----------------------------------------------------- 实例变量
    /** 全局JNDI上下文，存储服务器级命名资源（如数据库连接池） */
    private javax.naming.Context globalNamingContext = null;
    /** 全局JNDI资源实现类，管理JNDI资源的注册与查找 */
    private NamingResourcesImpl globalNamingResources;
    /** JNDI上下文监听器，监听服务器生命周期事件以管理JNDI资源 */
    private final NamingContextListener namingContextListener;
    /** 服务器监听关闭命令的端口（默认8005） */
    private int port = 8005;
    /** 端口偏移量（多实例部署时调整端口） */
    private int portOffset = 0;
    /** 关闭命令监听地址（默认localhost） */
    private String address = "localhost";
    /** 随机数生成器，用于防止关闭命令的DoS攻击 */
    private Random random = null;
    /** 服务器管理的Service数组，每个Service包含一组Connector和一个Container */
    private Service[] services = new Service[0];
    /** Service数组的读写锁，保证多线程环境下的安全访问 */
    private final ReentrantReadWriteLock servicesLock = new ReentrantReadWriteLock();
    /** 读锁引用，用于读取Service数组时的并发控制 */
    private final Lock servicesReadLock = servicesLock.readLock();
    /** 写锁引用，用于修改Service数组时的并发控制 */
    private final Lock servicesWriteLock = servicesLock.writeLock();
    /** 服务器关闭命令字符串（默认"SHUTDOWN"） */
    private String shutdown = "SHUTDOWN";
    /** 属性变更支持类，用于通知监听器属性变化 */
    final PropertyChangeSupport support = new PropertyChangeSupport(this);
    /** 关闭等待标志，用于控制await()方法的退出 */
    private volatile boolean stopAwait = false;
    /** Catalina实例引用，连接服务器与核心启动逻辑 */
    private Catalina catalina = null;
    /** 服务器类加载器的父加载器 */
    private ClassLoader parentClassLoader = null;
    /** 等待关闭命令的线程引用 */
    private volatile Thread awaitThread = null;
    /** 关闭命令监听套接字 */
    private volatile ServerSocket awaitSocket = null;
    /** Catalina主目录（CATALINA_HOME） */
    private File catalinaHome = null;
    /** Catalina基础目录（CATALINA_BASE） */
    private File catalinaBase = null;
    /** JNDI命名令牌，用于资源隔离 */
    private final Object namingToken = new Object();
    /** 后台任务处理线程池大小 */
    private int utilityThreads = 2;
    /** 后台线程是否为守护线程（默认true） */
    private boolean utilityThreadsAsDaemon = false;
    /** 后台任务执行器（处理定时任务，如会话超时检查） */
    private ScheduledThreadPoolExecutor utilityExecutor = null;
    /** 后台任务执行器的锁对象，保证线程安全配置 */
    private final Object utilityExecutorLock = new Object();
    /** 后台任务执行器的包装器，提供统一接口 */
    private ScheduledExecutorService utilityExecutorWrapper = null;
    /** 周期性生命周期事件的调度未来任务 */
    private ScheduledFuture<?> periodicLifecycleEventFuture = null;
    /** 监控未来任务，用于周期性检查 */
    private ScheduledFuture<?> monitorFuture;
    /** 周期性生命周期事件的间隔时间（秒） */
    private int periodicEventDelay = 10;


    // ------------------------------------------------------------- 属性访问方法
    @Override
    public Object getNamingToken() { return namingToken; } // 返回JNDI命名令牌
    @Override
    public javax.naming.Context getGlobalNamingContext() { return globalNamingContext; } // 获取全局JNDI上下文
    /** 设置全局JNDI上下文 */
    public void setGlobalNamingContext(javax.naming.Context globalNamingContext) {
        this.globalNamingContext = globalNamingContext;
    }
    @Override
    public NamingResourcesImpl getGlobalNamingResources() { return globalNamingResources; } // 获取全局JNDI资源
    /** 设置全局JNDI资源并通知属性变更 */
    @Override
    public void setGlobalNamingResources(NamingResourcesImpl globalNamingResources) {
        NamingResourcesImpl old = this.globalNamingResources;
        this.globalNamingResources = globalNamingResources;
        this.globalNamingResources.setContainer(this);
        // 触发属性变更事件，通知监听器
        support.firePropertyChange("globalNamingResources", old, this.globalNamingResources);
    }
    /** 返回Tomcat服务器版本信息（如"Apache Tomcat/10.1.0"） */
    public String getServerInfo() { return ServerInfo.getServerInfo(); }
    /** 返回服务器构建时间戳 */
    public String getServerBuilt() { return ServerInfo.getServerBuilt(); }
    /** 返回服务器版本号 */
    public String getServerNumber() { return ServerInfo.getServerNumber(); }
    @Override
    public int getPort() { return port; } // 获取关闭命令端口
    @Override
    public void setPort(int port) { this.port = port; } // 设置关闭命令端口
    @Override
    public int getPortOffset() { return portOffset; } // 获取端口偏移量
    /** 设置端口偏移量（不允许负数） */
    @Override
    public void setPortOffset(int portOffset) {
        if (portOffset < 0) {
            throw new IllegalArgumentException(sm.getString("standardServer.portOffset.invalid", portOffset));
        }
        this.portOffset = portOffset;
    }
    /** 返回实际生效的端口（原端口+偏移量，非正数端口不应用偏移） */
    @Override
    public int getPortWithOffset() {
        int port = getPort();
        return port > 0 ? port + getPortOffset() : port;
    }
    @Override
    public String getAddress() { return address; } // 获取关闭命令监听地址
    @Override
    public void setAddress(String address) { this.address = address; } // 设置关闭命令监听地址
    @Override
    public String getShutdown() { return shutdown; } // 获取关闭命令字符串
    @Override
    public void setShutdown(String shutdown) { this.shutdown = shutdown; } // 设置关闭命令字符串
    @Override
    public Catalina getCatalina() { return catalina; } // 获取Catalina实例
    @Override
    public void setCatalina(Catalina catalina) { this.catalina = catalina; } // 设置Catalina实例
    @Override
    public int getUtilityThreads() { return utilityThreads; } // 获取后台线程数
    /** 处理后台线程数的特殊值（负数时基于CPU核心数计算） */
    private static int getUtilityThreadsInternal(int utilityThreads) {
        int result = utilityThreads;
        if (result <= 0) {
            result = Runtime.getRuntime().availableProcessors() + result;
            if (result < 2) result = 2; // 保证最少2个线程
        }
        return result;
    }
    /** 设置后台线程数并重新配置执行器（线程数不能小于原值） */
    @Override
    public void setUtilityThreads(int utilityThreads) {
        int old = this.utilityThreads;
        if (getUtilityThreadsInternal(utilityThreads) < getUtilityThreadsInternal(old)) return;
        this.utilityThreads = utilityThreads;
        synchronized (utilityExecutorLock) {
            if (old != utilityThreads && utilityExecutor != null) {
                reconfigureUtilityExecutor(getUtilityThreadsInternal(utilityThreads));
            }
        }
    }
    /** 重新配置后台任务执行器的线程数 */
    private void reconfigureUtilityExecutor(int threads) {
        if (utilityExecutor != null) {
            utilityExecutor.setCorePoolSize(threads); // 调整核心线程数
        } else {
            // 创建新的定时线程池执行器
            ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(threads,
                new TaskThreadFactory("Catalina-utility-", utilityThreadsAsDaemon, Thread.MIN_PRIORITY));
            exec.setKeepAliveTime(10, TimeUnit.SECONDS); // 空闲线程存活时间
            exec.setRemoveOnCancelPolicy(true); // 取消任务时移除
            exec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false); // 关闭后不执行延迟任务
            utilityExecutor = exec;
            utilityExecutorWrapper = new org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor(utilityExecutor);
        }
    }
    /** 获取后台线程是否为守护线程 */
    public boolean getUtilityThreadsAsDaemon() { return utilityThreadsAsDaemon; }
    /** 设置后台线程是否为守护线程（默认true） */
    public void setUtilityThreadsAsDaemon(boolean utilityThreadsAsDaemon) {
        this.utilityThreadsAsDaemon = utilityThreadsAsDaemon;
    }
    /** 获取周期性生命周期事件的间隔时间（秒） */
    public int getPeriodicEventDelay() { return periodicEventDelay; }
    /** 设置周期性事件间隔（负数或零禁用事件） */
    public void setPeriodicEventDelay(int periodicEventDelay) {
        this.periodicEventDelay = periodicEventDelay;
    }


    // --------------------------------------------------------- Server核心方法
    /**
     * 添加Service组件到服务器
     * @param service 要添加的Service实例
     */
    @Override
    public void addService(Service service) {
        service.setServer(this); // 设置Service所属的Server
        servicesWriteLock.lock(); // 获取写锁，保证线程安全
        try {
            // 扩容Service数组并添加新Service
            Service[] results = new Service[services.length + 1];
            System.arraycopy(services, 0, results, 0, services.length);
            results[services.length] = service;
            services = results;
        } finally {
            servicesWriteLock.unlock(); // 释放写锁
        }
        // 如果服务器已启动，直接启动该Service
        if (getState().isAvailable()) {
            try { service.start(); } catch (LifecycleException e) {}
        }
        // 触发属性变更事件，通知监听器
        support.firePropertyChange("service", null, service);
    }

    /** 停止等待关闭命令，中断等待线程 */
    public void stopAwait() {
        stopAwait = true; // 设置停止标志
        Thread t = awaitThread;
        if (t != null) {
            ServerSocket s = awaitSocket;
            if (s != null) {
                awaitSocket = null;
                try { s.close(); } catch (IOException e) {} // 关闭监听套接字
            }
            t.interrupt(); // 中断等待线程
            try { t.join(1000); } catch (InterruptedException e) {} // 等待线程结束
        }
    }

    /**
     * 阻塞等待关闭命令，监听指定端口
     * 接收到正确命令后关闭服务器
     */
    @Override
    public void await() {
        // 处理特殊端口值：-2表示不监听端口（嵌入式场景，由外部控制生命周期）
        if (getPortWithOffset() == -2) {
            // 未正式文档化的特性，用于嵌入式应用保持运行但不监听关闭端口
            return;
        }

        // 记录当前执行线程，用于后续中断和清理
        Thread currentThread = Thread.currentThread();

        // 处理特殊端口值：-1表示进入无限循环等待（不实际监听端口）
        if (getPortWithOffset() == -1) {
            try {
                awaitThread = currentThread;  // 标记当前线程为等待线程
                while (!stopAwait) {          // 循环等待stopAwait标志被设置
                    try {
                        Thread.sleep(10000);  // 每次休眠10秒后重新检查标志
                    } catch (InterruptedException ex) {
                        // 被中断时不处理，继续循环检查stopAwait标志
                    }
                }
            } finally {
                awaitThread = null;  // 无论如何，退出前清除等待线程引用
            }
            return;
        }

        // 创建关闭命令监听套接字
        try {
            // 在指定地址和端口上创建ServerSocket，backlog设为1
            awaitSocket = new ServerSocket(getPortWithOffset(), 1, InetAddress.getByName(address));
        } catch (IOException e) {
            // 记录错误信息，包含地址、端口等上下文信息
            log.error(sm.getString("standardServer.awaitSocket.fail", address,
                String.valueOf(getPortWithOffset()), String.valueOf(getPort()), String.valueOf(getPortOffset())), e);
            return;  // 初始化失败时直接返回
        }

        try {
            awaitThread = currentThread;  // 标记当前线程为等待线程

            // 主循环：持续监听关闭命令，直到stopAwait标志被设置
            while (!stopAwait) {
                ServerSocket serverSocket = awaitSocket;  // 获取当前的ServerSocket引用
                if (serverSocket == null) {
                    break;  // ServerSocket被关闭时退出循环
                }

                // 等待客户端连接并处理关闭命令
                Socket socket = null;
                StringBuilder command = new StringBuilder();  // 存储接收到的命令

                try {
                    InputStream stream;
                    long acceptStartTime = System.currentTimeMillis();  // 记录连接开始时间

                    // 接受客户端连接并设置超时（防止长时间阻塞）
                    try {
                        socket = serverSocket.accept();  // 阻塞等待新连接
                        socket.setSoTimeout(10 * 1000);  // 设置读取超时为10秒
                        stream = socket.getInputStream();  // 获取输入流
                    } catch (SocketTimeoutException ste) {
                        // 理论上不会发生，因为accept()本身没有超时，但Bug 56684显示可能出现
                        log.warn(sm.getString("standardServer.accept.timeout",
                            Long.valueOf(System.currentTimeMillis() - acceptStartTime)), ste);
                        continue;  // 忽略异常，继续等待下一个连接
                    } catch (AccessControlException ace) {
                        // 安全管理器拒绝访问时的处理
                        log.warn(sm.getString("standardServer.accept.security"), ace);
                        continue;  // 忽略异常，继续等待下一个连接
                    } catch (IOException e) {
                        if (stopAwait) {
                            break;  // 主动停止时（如调用stopAwait()）退出循环
                        }
                        log.error(sm.getString("standardServer.accept.error"), e);
                        break;  // 其他IO异常时退出循环
                    }

                    // 从socket读取关闭命令，限制最大长度防止DoS攻击
                    int expected = 1024;  // 初始期望读取长度
                    // 动态调整期望长度，防止固定长度被利用
                    while (expected < shutdown.length()) {
                        if (random == null) {
                            random = new Random();  // 初始化随机数生成器
                        }
                        expected += random.nextInt() % 1024;  // 随机增加长度
                    }

                    // 循环读取字符，直到遇到控制字符或达到预期长度
                    while (expected > 0) {
                        int ch;
                        try {
                            ch = stream.read();  // 读取一个字符
                        } catch (IOException e) {
                            log.warn(sm.getString("standardServer.accept.readError"), e);
                            ch = -1;  // 读取失败时标记为EOF
                        }

                        // 控制字符（如换行符）或EOF终止读取
                        if (ch < 32 || ch == 127) {
                            break;
                        }

                        command.append((char) ch);  // 追加有效字符到命令缓冲区
                        expected--;  // 减少剩余期望读取长度
                    }
                } finally {
                    // 确保无论是否成功读取命令，都关闭socket释放资源
                    try {
                        if (socket != null) {
                            socket.close();  // 关闭socket连接
                        }
                    } catch (IOException e) {
                        // 忽略关闭异常
                    }
                }

                // 验证接收到的命令是否与预设的shutdown命令匹配
                boolean match = command.toString().equals(shutdown);
                if (match) {
                    log.info(sm.getString("standardServer.shutdownViaPort"));
                    break;  // 匹配成功时退出主循环，触发服务器关闭流程
                } else {
                    // 记录无效命令，继续等待下一个连接
                    log.warn(sm.getString("standardServer.invalidShutdownCommand", command.toString()));
                }
            }
        } finally {
            // 清理资源，确保服务器停止时释放所有资源
            ServerSocket serverSocket = awaitSocket;
            awaitThread = null;  // 清除等待线程引用
            awaitSocket = null;  // 清除ServerSocket引用

            // 关闭ServerSocket，停止监听关闭命令端口
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    // 忽略关闭异常
                }
            }
        }
    }

    /** 根据名称查找Service组件 */
    @Override
    public Service findService(String name) {
        if (name == null) return null;
        servicesReadLock.lock(); // 获取读锁
        try {
            for (Service service : services) {
                if (name.equals(service.getName())) return service;
            }
        } finally {
            servicesReadLock.unlock(); // 释放读锁
        }
        return null;
    }

    /** 返回所有Service组件的克隆数组 */
    @Override
    public Service[] findServices() {
        servicesReadLock.lock(); // 获取读锁
        try {
            return services.clone(); // 返回数组克隆，避免外部修改
        } finally {
            servicesReadLock.unlock(); // 释放读锁
        }
    }

    /** 返回所有Service的JMX对象名称数组 */
    public ObjectName[] getServiceNames() {
        servicesReadLock.lock(); // 获取读锁
        try {
            ObjectName[] onames = new ObjectName[services.length];
            for (int i = 0; i < services.length; i++) {
                onames[i] = ((StandardService) services[i]).getObjectName();
            }
            return onames;
        } finally {
            servicesReadLock.unlock(); // 释放读锁
        }
    }

    /** 从服务器中移除Service组件 */
    @Override
    public void removeService(Service service) {
        servicesWriteLock.lock(); // 获取写锁
        try {
            // 找到Service在数组中的位置并移除
            int j = -1;
            for (int i = 0; i < services.length; i++) {
                if (service == services[i]) {
                    j = i;
                    break;
                }
            }
            if (j < 0) return;
            int k = 0;
            Service[] results = new Service[services.length - 1];
            for (int i = 0; i < services.length; i++) {
                if (i != j) results[k++] = services[i];
            }
            services = results;
        } finally {
            servicesWriteLock.unlock(); // 释放写锁
        }
        // 停止该Service（会级联停止其内部组件）
        try { service.stop(); } catch (LifecycleException e) {}
        // 触发属性变更事件，通知监听器
        support.firePropertyChange("service", service, null);
    }

    /** 获取Catalina基础目录（优先使用设置值，否则使用主目录） */
    @Override
    public File getCatalinaBase() {
        if (catalinaBase != null) return catalinaBase;
        catalinaBase = getCatalinaHome();
        return catalinaBase;
    }
    @Override
    public void setCatalinaBase(File catalinaBase) { this.catalinaBase = catalinaBase; } // 设置基础目录
    @Override
    public File getCatalinaHome() { return catalinaHome; } // 获取主目录
    @Override
    public void setCatalinaHome(File catalinaHome) { this.catalinaHome = catalinaHome; } // 设置主目录


    // --------------------------------------------------------- 公共方法
    /** 添加属性变更监听器 */
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        support.addPropertyChangeListener(listener);
    }
    /** 移除属性变更监听器 */
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        support.removePropertyChangeListener(listener);
    }
    /** 返回服务器字符串表示（包含端口信息） */
    @Override
    public String toString() {
        return "StandardServer[" + getPort() + ']';
    }

    /** 存储服务器配置到server.xml（通过JMX实现） */
    public synchronized void storeConfig() throws InstanceNotFoundException, MBeanException {
        try {
            ObjectName sname = new ObjectName("Catalina:type=StoreConfig");
            MBeanServer server = Registry.getRegistry(null).getMBeanServer();
            if (server.isRegistered(sname)) {
                server.invoke(sname, "storeConfig", null, null); // 调用存储配置方法
            } else {
                log.error(sm.getString("standardServer.storeConfig.notAvailable", sname));
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("standardServer.storeConfig.error"), t);
        }
    }

    /** 存储指定Context的配置（通过JMX实现） */
    public synchronized void storeContext(Context context) throws InstanceNotFoundException, MBeanException {
        try {
            ObjectName sname = new ObjectName("Catalina:type=StoreConfig");
            MBeanServer server = Registry.getRegistry(null).getMBeanServer();
            if (server.isRegistered(sname)) {
                // 调用存储Context配置的方法
                server.invoke(sname, "store", new Object[] { context }, new String[] { "java.lang.String" });
            } else {
                log.error(sm.getString("standardServer.storeConfig.notAvailable", sname));
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("standardServer.storeConfig.contextError", context.getName()), t);
        }
    }

    /** 检查是否启用JNDI功能（通过系统属性判断） */
    private boolean isUseNaming() {
        boolean useNaming = true;
        String prop = System.getProperty("catalina.useNaming");
        if (prop != null && prop.equals("false")) {
            useNaming = false;
        }
        return useNaming;
    }


    // --------------------------------------------------------- 生命周期管理方法
    /** 服务器启动的核心逻辑 */
    @Override
    protected void startInternal() throws LifecycleException {
        fireLifecycleEvent(CONFIGURE_START_EVENT, null); // 触发配置开始事件
        setState(LifecycleState.STARTING); // 设置生命周期状态为STARTING
        // 初始化后台任务执行器
        synchronized (utilityExecutorLock) {
            reconfigureUtilityExecutor(getUtilityThreadsInternal(utilityThreads));
            register(utilityExecutor, "type=UtilityExecutor"); // 注册JMX管理
        }
        globalNamingResources.start(); // 启动全局JNDI资源
        // 启动所有Service（关键：触发Connector和Container初始化）
        for (Service service : findServices()) {
            service.start();
        }
        // 配置周期性生命周期事件（如会话超时检查）
        if (periodicEventDelay > 0) {
            monitorFuture = getUtilityExecutor().scheduleWithFixedDelay(
                this::startPeriodicLifecycleEvent, 0, 60, TimeUnit.SECONDS);
        }
    }

    /** 启动周期性生命周期事件调度 */
    private void startPeriodicLifecycleEvent() {
        if (periodicLifecycleEventFuture == null || periodicLifecycleEventFuture.isDone()) {
            if (periodicLifecycleEventFuture != null && periodicLifecycleEventFuture.isDone()) {
                // 处理调度异常
                try {
                    periodicLifecycleEventFuture.get();
                } catch (InterruptedException | ExecutionException e) {
                    log.error(sm.getString("standardServer.periodicEventError"), e);
                }
            }
            // 重新调度周期性事件
            periodicLifecycleEventFuture = getUtilityExecutor().scheduleAtFixedRate(
                () -> fireLifecycleEvent(PERIODIC_EVENT, null),
                periodicEventDelay, periodicEventDelay, TimeUnit.SECONDS);
        }
    }

    /** 服务器停止的核心逻辑 */
    @Override
    protected void stopInternal() throws LifecycleException {
        setState(LifecycleState.STOPPING); // 设置生命周期状态为STOPPING
        // 停止周期性事件调度
        if (monitorFuture != null) {
            monitorFuture.cancel(true);
            monitorFuture = null;
        }
        if (periodicLifecycleEventFuture != null) {
            periodicLifecycleEventFuture.cancel(false);
            periodicLifecycleEventFuture = null;
        }
        fireLifecycleEvent(CONFIGURE_STOP_EVENT, null); // 触发配置停止事件
        // 停止所有Service（会级联停止请求处理组件）
        for (Service service : findServices()) {
            service.stop();
        }
        // 释放后台任务执行器资源
        synchronized (utilityExecutorLock) {
            if (utilityExecutor != null) {
                utilityExecutor.shutdownNow(); // 立即关闭执行器
                unregister("type=UtilityExecutor"); // 取消JMX注册
                utilityExecutor = null;
            }
        }
        globalNamingResources.stop(); // 停止全局JNDI资源
        stopAwait(); // 停止等待关闭命令
    }

    /** 服务器初始化的核心逻辑 */
    @Override
    protected void initInternal() throws LifecycleException {
        super.initInternal(); // 调用父类初始化方法
        // 注册全局字符串缓存（提高字符串处理效率）
        onameStringCache = register(new StringCache(), "type=StringCache");
        // 注册MBean工厂（用于JMX管理）
        MBeanFactory factory = new MBeanFactory();
        factory.setContainer(this);
        onameMBeanFactory = register(factory, "type=MBeanFactory");
        // 初始化全局JNDI资源
        globalNamingResources.init();
        // 初始化所有Service（配置Connector和Container）
        for (Service service : findServices()) {
            service.init();
        }
    }

    /** 服务器销毁的核心逻辑 */
    @Override
    protected void destroyInternal() throws LifecycleException {
        // 销毁所有Service
        for (Service service : findServices()) {
            service.destroy();
        }
        globalNamingResources.destroy(); // 销毁全局JNDI资源
        unregister(onameMBeanFactory); // 取消MBean工厂注册
        unregister(onameStringCache); // 取消字符串缓存注册
        super.destroyInternal(); // 调用父类销毁方法
    }

    /** 获取父类加载器（优先使用设置值，否则使用Catalina或系统加载器） */
    @Override
    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null) return parentClassLoader;
        if (catalina != null) return catalina.getParentClassLoader();
        return ClassLoader.getSystemClassLoader();
    }
    /** 设置父类加载器并通知属性变更 */
    @Override
    public void setParentClassLoader(ClassLoader parent) {
        ClassLoader old = this.parentClassLoader;
        this.parentClassLoader = parent;
        support.firePropertyChange("parentClassLoader", old, this.parentClassLoader);
    }

    // JMX相关对象名称
    private ObjectName onameStringCache;
    private ObjectName onameMBeanFactory;

    /** 获取MBean域名（优先使用第一个Engine的名称，否则使用第一个Service的名称） */
    @Override
    protected String getDomainInternal() {
        String domain = null;
        Service[] services = findServices();
        if (services.length > 0) {
            Service service = services[0];
            if (service != null) {
                domain = service.getDomain();
            }
        }
        return domain;
    }

    /** 获取JMX对象名称的关键属性 */
    @Override
    protected String getObjectNameKeyProperties() {
        return "type=Server";
    }

    /** 返回后台任务执行器的包装器 */
    @Override
    public ScheduledExecutorService getUtilityExecutor() {
        return utilityExecutorWrapper;
    }
}