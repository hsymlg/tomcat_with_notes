/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.apache.juli.logging.Log;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.buf.HexUtils;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.net.Acceptor.AcceptorState;
import org.apache.tomcat.util.net.SSLHostConfigCertificate.StoreType;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.LimitLatch;
import org.apache.tomcat.util.threads.ResizableExecutor;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.TaskThreadFactory;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;

/**
 * 抽象网络端点类，提供网络连接处理的基本框架
 *
 * @param <S> 套接字包装器类型，可能与U相同
 * @param <U> 底层套接字类型，可能与S相同
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public abstract class AbstractEndpoint<S, U> {

    // -------------------------------------------------------------- 常量定义

    protected static final StringManager sm = StringManager.getManager(AbstractEndpoint.class);

    /**
     * 处理器接口，定义套接字处理逻辑
     *
     * @param <S> 套接字包装器类型
     */
    public interface Handler<S> {

        /**
         * 套接字状态枚举
         * OPEN: 打开状态
         * CLOSED: 关闭状态
         * LONG: 长连接状态
         * ASYNC_END: 异步结束状态
         * SENDFILE: 文件发送状态
         * UPGRADING: 协议升级中
         * UPGRADED: 协议已升级
         * ASYNC_IO: 异步IO状态
         * SUSPENDED: 挂起状态
         */
        enum SocketState {
            OPEN,
            CLOSED,
            LONG,
            ASYNC_END,
            SENDFILE,
            UPGRADING,
            UPGRADED,
            ASYNC_IO,
            SUSPENDED
        }

        /**
         * 处理套接字及其当前状态
         *
         * @param socket 要处理的套接字包装器
         * @param status 当前套接字状态
         * @return 处理后的套接字状态
         */
        SocketState process(SocketWrapperBase<S> socket, SocketEvent status);

        /**
         * 获取与处理器关联的全局请求处理器
         *
         * @return 全局请求处理器对象
         */
        Object getGlobal();

        /**
         * 释放与套接字包装器关联的资源
         *
         * @param socketWrapper 要释放资源的套接字包装器
         */
        void release(SocketWrapperBase<S> socketWrapper);

        /**
         * 通知处理器端点已停止接受新连接
         */
        void pause();

        /**
         * 回收与处理器关联的资源
         */
        void recycle();
    }

    /**
     * 绑定状态枚举
     * UNBOUND: 未绑定
     * BOUND_ON_INIT: 初始化时绑定
     * BOUND_ON_START: 启动时绑定
     * SOCKET_CLOSED_ON_STOP: 停止时套接字关闭
     */
    protected enum BindState {
        UNBOUND(false, false),
        BOUND_ON_INIT(true, true),
        BOUND_ON_START(true, true),
        SOCKET_CLOSED_ON_STOP(false, true);

        private final boolean bound;
        private final boolean wasBound;

        BindState(boolean bound, boolean wasBound) {
            this.bound = bound;
            this.wasBound = wasBound;
        }

        public boolean isBound() {
            return bound;
        }

        public boolean wasBound() {
            return wasBound;
        }
    }

    /**
     * 将超时时间转换为内部使用的格式（<=0时使用Long.MAX_VALUE）
     *
     * @param timeout 超时时间（毫秒）
     * @return 转换后的超时时间
     */
    public static long toTimeout(long timeout) {
        return (timeout > 0) ? timeout : Long.MAX_VALUE;
    }

    // ----------------------------------------------------------------- 字段定义

    /** 端点运行状态 */
    protected volatile boolean running = false;

    /** 端点暂停状态 */
    protected volatile boolean paused = false;

    /** 是否使用内部线程池 */
    protected volatile boolean internalExecutor = true;

    /** 连接数限制 latch */
    private volatile LimitLatch connectionLimitLatch = null;

    /** 套接字属性配置 */
    protected final SocketProperties socketProperties = new SocketProperties();

    public SocketProperties getSocketProperties() {
        return socketProperties;
    }

    /** 接受连接的线程 */
    protected Acceptor<U> acceptor;

    /** 套接字处理器缓存 */
    protected SynchronizedStack<SocketProcessorBase<S>> processorCache;

    /** JMX 对象名称 */
    private ObjectName oname = null;

    /** 当前连接映射（套接字到包装器） */
    protected Map<U, SocketWrapperBase<S>> connections = new ConcurrentHashMap<>();

    // ----------------------------------------------------------------- 属性方法

    private String defaultSSLHostConfigName = SSLHostConfig.DEFAULT_SSL_HOST_NAME;

    /**
     * 获取默认SSL主机配置名称（始终为小写）
     *
     * @return 默认SSL主机配置名称
     */
    public String getDefaultSSLHostConfigName() {
        return defaultSSLHostConfigName;
    }

    public void setDefaultSSLHostConfigName(String defaultSSLHostConfigName) {
        this.defaultSSLHostConfigName = defaultSSLHostConfigName.toLowerCase(Locale.ENGLISH);
    }

    /** SSL主机配置映射 */
    protected ConcurrentMap<String, SSLHostConfig> sslHostConfigs = new ConcurrentHashMap<>();

    /**
     * 添加SSL主机配置
     *
     * @param sslHostConfig 要添加的SSL主机配置
     * @throws IllegalArgumentException 如果主机名无效或已存在
     */
    public void addSslHostConfig(SSLHostConfig sslHostConfig) throws IllegalArgumentException {
        addSslHostConfig(sslHostConfig, false);
    }

    /**
     * 添加SSL主机配置（可选择替换现有配置）
     *
     * @param sslHostConfig 要添加的SSL主机配置
     * @param replace 是否允许替换现有配置
     * @throws IllegalArgumentException 如果主机名无效或不允许替换时已存在
     */
    public void addSslHostConfig(SSLHostConfig sslHostConfig, boolean replace) throws IllegalArgumentException {
        String key = sslHostConfig.getHostName();
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException(sm.getString("endpoint.noSslHostName"));
        }
        if (bindState != BindState.UNBOUND && bindState != BindState.SOCKET_CLOSED_ON_STOP && isSSLEnabled()) {
            try {
                createSSLContext(sslHostConfig);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }
        if (replace) {
            SSLHostConfig previous = sslHostConfigs.put(key, sslHostConfig);
            if (previous != null) {
                unregisterJmx(sslHostConfig);
            }
            registerJmx(sslHostConfig);
        } else {
            SSLHostConfig duplicate = sslHostConfigs.putIfAbsent(key, sslHostConfig);
            if (duplicate != null) {
                releaseSSLContext(sslHostConfig);
                throw new IllegalArgumentException(sm.getString("endpoint.duplicateSslHostName", key));
            }
            registerJmx(sslHostConfig);
        }
    }

    /**
     * 移除指定主机的SSL配置
     *
     * @param hostName 要移除的主机名
     * @return 被移除的SSL主机配置（如果存在）
     */
    public SSLHostConfig removeSslHostConfig(String hostName) {
        if (hostName == null) {
            return null;
        }
        String hostNameLower = hostName.toLowerCase(Locale.ENGLISH);
        if (hostNameLower.equals(getDefaultSSLHostConfigName())) {
            throw new IllegalArgumentException(sm.getString("endpoint.removeDefaultSslHostConfig", hostName));
        }
        SSLHostConfig sslHostConfig = sslHostConfigs.remove(hostNameLower);
        unregisterJmx(sslHostConfig);
        return sslHostConfig;
    }

    /**
     * 重新加载指定主机的SSL配置
     *
     * @param hostName 要重新加载的主机名
     */
    public void reloadSslHostConfig(String hostName) {
        SSLHostConfig sslHostConfig = sslHostConfigs.get(hostName.toLowerCase(Locale.ENGLISH));
        if (sslHostConfig == null) {
            throw new IllegalArgumentException(sm.getString("endpoint.unknownSslHostName", hostName));
        }
        addSslHostConfig(sslHostConfig, true);
    }

    /**
     * 重新加载所有SSL主机配置
     */
    public void reloadSslHostConfigs() {
        for (String hostName : sslHostConfigs.keySet()) {
            reloadSslHostConfig(hostName);
        }
    }

    public SSLHostConfig[] findSslHostConfigs() {
        return sslHostConfigs.values().toArray(new SSLHostConfig[0]);
    }

    /**
     * 为指定SSL主机配置创建SSL上下文（由子类实现）
     *
     * @param sslHostConfig SSL主机配置
     * @throws Exception 如果创建SSL上下文失败
     */
    protected abstract void createSSLContext(SSLHostConfig sslHostConfig) throws Exception;

    /**
     * 记录证书信息
     *
     * @param certificate 证书对象
     */
    protected void logCertificate(SSLHostConfigCertificate certificate) {
        SSLHostConfig sslHostConfig = certificate.getSSLHostConfig();
        String certificateInfo;

        if (certificate.getStoreType() == StoreType.PEM) {
            certificateInfo = sm.getString("endpoint.tls.info.cert.pem", certificate.getCertificateKeyFile(),
                certificate.getCertificateFile(), certificate.getCertificateChainFile());
        } else {
            String keyAlias = certificate.getCertificateKeyAlias();
            if (keyAlias == null) {
                keyAlias = SSLUtilBase.DEFAULT_KEY_ALIAS;
            }
            certificateInfo = sm.getString("endpoint.tls.info.cert.keystore", certificate.getCertificateKeystoreFile(), keyAlias);
        }

        String trustStoreSource = sslHostConfig.getTruststoreFile();
        if (trustStoreSource == null) {
            trustStoreSource = sslHostConfig.getCaCertificateFile();
        }
        if (trustStoreSource == null) {
            trustStoreSource = sslHostConfig.getCaCertificatePath();
        }

        getLogCertificate().info(sm.getString("endpoint.tls.info", getName(), sslHostConfig.getHostName(),
            certificate.getType(), certificateInfo, trustStoreSource));

        if (getLogCertificate().isDebugEnabled()) {
            String alias = certificate.getCertificateKeyAlias();
            if (alias == null) {
                alias = SSLUtilBase.DEFAULT_KEY_ALIAS;
            }
            X509Certificate[] x509Certificates = certificate.getSslContext().getCertificateChain(alias);
            if (x509Certificates != null && x509Certificates.length > 0) {
                getLogCertificate().debug(generateCertificateDebug(x509Certificates[0]));
            } else {
                getLogCertificate().debug(sm.getString("endpoint.tls.cert.noCerts"));
            }
        }
    }

    /**
     * 生成证书调试信息
     *
     * @param certificate X.509证书
     * @return 证书调试信息字符串
     */
    protected String generateCertificateDebug(X509Certificate certificate) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n[");
        try {
            byte[] certBytes = certificate.getEncoded();
            // SHA-256指纹
            sb.append("\nSHA-256 fingerprint: ");
            MessageDigest sha512Digest = MessageDigest.getInstance("SHA-256");
            sha512Digest.update(certBytes);
            sb.append(HexUtils.toHexString(sha512Digest.digest()));
            // SHA-1指纹
            sb.append("\nSHA-1 fingerprint: ");
            MessageDigest sha1Digest = MessageDigest.getInstance("SHA-1");
            sha1Digest.update(certBytes);
            sb.append(HexUtils.toHexString(sha1Digest.digest()));
        } catch (CertificateEncodingException e) {
            getLogCertificate().warn(sm.getString("endpoint.tls.cert.encodingError"), e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        sb.append("\n");
        sb.append(certificate);
        sb.append("\n]");
        return sb.toString();
    }

    /**
     * 销毁SSL相关资源
     *
     * @throws Exception 如果销毁失败
     */
    protected void destroySsl() throws Exception {
        if (isSSLEnabled()) {
            for (SSLHostConfig sslHostConfig : sslHostConfigs.values()) {
                releaseSSLContext(sslHostConfig);
            }
        }
    }

    /**
     * 释放与SSL主机配置关联的SSL上下文
     *
     * @param sslHostConfig SSL主机配置
     */
    protected void releaseSSLContext(SSLHostConfig sslHostConfig) {
        for (SSLHostConfigCertificate certificate : sslHostConfig.getCertificates()) {
            if (certificate.getSslContext() != null) {
                SSLContext sslContext = certificate.getSslContextGenerated();
                if (sslContext != null) {
                    sslContext.destroy();
                }
            }
        }
    }

    /**
     * 根据SNI主机名查找SSL主机配置
     * 查找顺序：精确匹配 -> 通配符匹配 -> 默认配置
     *
     * @param sniHostName SNI主机名（小写）
     * @return 匹配的SSL主机配置
     */
    protected SSLHostConfig getSSLHostConfig(String sniHostName) {
        SSLHostConfig result = null;

        if (sniHostName != null) {
            // 首选：直接匹配
            result = sslHostConfigs.get(sniHostName);
            if (result != null) {
                return result;
            }
            // 次选：通配符匹配
            int indexOfDot = sniHostName.indexOf('.');
            if (indexOfDot > -1) {
                result = sslHostConfigs.get("*" + sniHostName.substring(indexOfDot));
            }
        }

        // 回退：使用默认配置
        if (result == null) {
            result = sslHostConfigs.get(getDefaultSSLHostConfigName());
        }
        if (result == null) {
            throw new IllegalStateException();
        }
        return result;
    }

    /**
     * 是否启用sendfile特性
     */
    private boolean useSendfile = true;

    public boolean getUseSendfile() {
        return useSendfile;
    }

    public void setUseSendfile(boolean useSendfile) {
        this.useSendfile = useSendfile;
    }

    /**
     * 内部线程池终止超时时间（毫秒）
     */
    private long executorTerminationTimeoutMillis = 5000;

    public long getExecutorTerminationTimeoutMillis() {
        return executorTerminationTimeoutMillis;
    }

    public void setExecutorTerminationTimeoutMillis(long executorTerminationTimeoutMillis) {
        this.executorTerminationTimeoutMillis = executorTerminationTimeoutMillis;
    }

    /**
     * 接受线程优先级
     */
    protected int acceptorThreadPriority = Thread.NORM_PRIORITY;

    public void setAcceptorThreadPriority(int acceptorThreadPriority) {
        this.acceptorThreadPriority = acceptorThreadPriority;
    }

    public int getAcceptorThreadPriority() {
        return acceptorThreadPriority;
    }

    /**
     * 最大连接数
     */
    private int maxConnections = 8 * 1024;

    public void setMaxConnections(int maxCon) {
        this.maxConnections = maxCon;
        LimitLatch latch = this.connectionLimitLatch;
        if (latch != null) {
            if (maxCon == -1) {
                releaseConnectionLatch();
            } else {
                latch.setLimit(maxCon);
            }
        } else if (maxCon > 0) {
            initializeConnectionLatch();
        }
    }

    public int getMaxConnections() {
        return this.maxConnections;
    }

    /**
     * 获取当前连接数（用于JMX监控）
     *
     * @return 连接数（-1表示未限制）
     */
    public long getConnectionCount() {
        LimitLatch latch = connectionLimitLatch;
        if (latch != null) {
            return latch.getCount();
        }
        return -1;
    }

    /**
     * 外部线程池
     */
    private Executor executor = null;

    public void setExecutor(Executor executor) {
        this.executor = executor;
        this.internalExecutor = (executor == null);
    }

    public Executor getExecutor() {
        return executor;
    }

    /**
     * 是否使用虚拟线程
     */
    private boolean useVirtualThreads = false;

    public void setUseVirtualThreads(boolean useVirtualThreads) {
        this.useVirtualThreads = useVirtualThreads;
    }

    public boolean getUseVirtualThreads() {
        return useVirtualThreads;
    }

    /**
     * 工具任务的调度线程池
     */
    private ScheduledExecutorService utilityExecutor = null;

    public void setUtilityExecutor(ScheduledExecutorService utilityExecutor) {
        this.utilityExecutor = utilityExecutor;
    }

    public ScheduledExecutorService getUtilityExecutor() {
        if (utilityExecutor == null) {
            getLog().warn(sm.getString("endpoint.warn.noUtilityExecutor"));
            utilityExecutor = new ScheduledThreadPoolExecutor(1);
        }
        return utilityExecutor;
    }

    /**
     * 服务器端口
     */
    private int port = -1;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    /**
     * 端口偏移量
     */
    private int portOffset = 0;

    public int getPortOffset() {
        return portOffset;
    }

    public void setPortOffset(int portOffset) {
        if (portOffset < 0) {
            throw new IllegalArgumentException(sm.getString("endpoint.portOffset.invalid", Integer.valueOf(portOffset)));
        }
        this.portOffset = portOffset;
    }

    /**
     * 获取带偏移量的端口
     */
    public int getPortWithOffset() {
        int port = getPort();
        if (port > 0) {
            return port + getPortOffset();
        }
        return port;
    }

    /**
     * 获取本地端口
     */
    public final int getLocalPort() {
        try {
            InetSocketAddress localAddress = getLocalAddress();
            if (localAddress == null) {
                return -1;
            }
            return localAddress.getPort();
        } catch (IOException ioe) {
            return -1;
        }
    }

    /**
     * 服务器绑定地址
     */
    private InetAddress address;

    public InetAddress getAddress() {
        return address;
    }

    public void setAddress(InetAddress address) {
        this.address = address;
    }

    /**
     * 获取服务器套接字绑定的本地地址（由子类实现）
     *
     * @return 本地地址
     * @throws IOException 如果获取失败
     */
    protected abstract InetSocketAddress getLocalAddress() throws IOException;

    /**
     * 服务器套接字接受队列长度
     */
    private int acceptCount = 100;

    public void setAcceptCount(int acceptCount) {
        if (acceptCount > 0) {
            this.acceptCount = acceptCount;
        }
    }

    public int getAcceptCount() {
        return acceptCount;
    }

    /**
     * 是否在初始化时绑定端口
     */
    private boolean bindOnInit = true;

    public boolean getBindOnInit() {
        return bindOnInit;
    }

    public void setBindOnInit(boolean b) {
        this.bindOnInit = b;
    }

    /**
     * 当前绑定状态
     */
    private volatile BindState bindState = BindState.UNBOUND;

    protected BindState getBindState() {
        return bindState;
    }

    /**
     * 保持活动连接超时时间（未设置时使用连接超时时间）
     */
    private Integer keepAliveTimeout = null;

    public int getKeepAliveTimeout() {
        if (keepAliveTimeout == null) {
            return getConnectionTimeout();
        } else {
            return keepAliveTimeout.intValue();
        }
    }

    public void setKeepAliveTimeout(int keepAliveTimeout) {
        this.keepAliveTimeout = Integer.valueOf(keepAliveTimeout);
    }

    /**
     * 是否启用TCP_NODELAY
     */
    public boolean getTcpNoDelay() {
        return socketProperties.getTcpNoDelay();
    }

    public void setTcpNoDelay(boolean tcpNoDelay) {
        socketProperties.setTcpNoDelay(tcpNoDelay);
    }

    /**
     * 套接字 linger 时间
     */
    public int getConnectionLinger() {
        return socketProperties.getSoLingerTime();
    }

    public void setConnectionLinger(int connectionLinger) {
        socketProperties.setSoLingerTime(connectionLinger);
        socketProperties.setSoLingerOn(connectionLinger >= 0);
    }

    /**
     * 套接字超时时间
     */
    public int getConnectionTimeout() {
        return socketProperties.getSoTimeout();
    }

    public void setConnectionTimeout(int soTimeout) {
        socketProperties.setSoTimeout(soTimeout);
    }

    /**
     * 是否启用SSL
     */
    private boolean SSLEnabled = false;

    public boolean isSSLEnabled() {
        return SSLEnabled;
    }

    public void setSSLEnabled(boolean SSLEnabled) {
        this.SSLEnabled = SSLEnabled;
    }

    /**
     * 线程池最小空闲线程数
     */
    private int minSpareThreads = 10;

    public void setMinSpareThreads(int minSpareThreads) {
        this.minSpareThreads = minSpareThreads;
        Executor executor = this.executor;
        if (internalExecutor && executor instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) executor).setCorePoolSize(minSpareThreads);
        }
    }

    public int getMinSpareThreads() {
        return Math.min(getMinSpareThreadsInternal(), getMaxThreads());
    }

    private int getMinSpareThreadsInternal() {
        if (internalExecutor) {
            return minSpareThreads;
        } else {
            return -1;
        }
    }

    /**
     * 线程池最大线程数
     */
    private int maxThreads = 200;

    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
        Executor executor = this.executor;
        if (internalExecutor && executor instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) executor).setMaximumPoolSize(maxThreads);
        }
    }

    public int getMaxThreads() {
        if (internalExecutor) {
            return maxThreads;
        } else {
            return -1;
        }
    }

    /**
     * 线程池任务队列最大容量
     */
    private int maxQueueSize = Integer.MAX_VALUE;

    public void setMaxQueueSize(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
    }

    public int getMaxQueueSize() {
        if (internalExecutor) {
            return maxQueueSize;
        } else {
            return -1;
        }
    }

    /**
     * 空闲线程最大存活时间（毫秒）
     */
    private int threadsMaxIdleTime = 60000;

    public void setThreadsMaxIdleTime(int threadsMaxIdleTime) {
        this.threadsMaxIdleTime = threadsMaxIdleTime;
        Executor executor = this.executor;
        if (internalExecutor && executor instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) executor).setKeepAliveTime(threadsMaxIdleTime, TimeUnit.MILLISECONDS);
        }
    }

    public int getThreadsMaxIdleTime() {
        if (internalExecutor) {
            return threadsMaxIdleTime;
        } else {
            return -1;
        }
    }

    /**
     * 工作线程优先级
     */
    protected int threadPriority = Thread.NORM_PRIORITY;

    public void setThreadPriority(int threadPriority) {
        this.threadPriority = threadPriority;
    }

    public int getThreadPriority() {
        if (internalExecutor) {
            return threadPriority;
        } else {
            return -1;
        }
    }

    /**
     * 最大保持活动请求数
     */
    private int maxKeepAliveRequests = 100;

    public int getMaxKeepAliveRequests() {
        if (bindState.isBound()) {
            return maxKeepAliveRequests;
        } else {
            return 1;
        }
    }

    public void setMaxKeepAliveRequests(int maxKeepAliveRequests) {
        this.maxKeepAliveRequests = maxKeepAliveRequests;
    }

    /**
     * 线程池名称
     */
    private String name = "TP";

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * JMX域名
     */
    private String domain;

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getDomain() {
        return domain;
    }

    /**
     * 线程是否为守护线程
     */
    private boolean daemon = true;

    public void setDaemon(boolean b) {
        daemon = b;
    }

    public boolean getDaemon() {
        return daemon;
    }

    /**
     * 是否启用异步IO
     */
    private boolean useAsyncIO = true;

    public void setUseAsyncIO(boolean useAsyncIO) {
        this.useAsyncIO = useAsyncIO;
    }

    public boolean getUseAsyncIO() {
        return useAsyncIO;
    }

    /**
     * 获取延迟接受设置（已过时，始终返回false）
     *
     * @deprecated Tomcat 11将移除
     */
    @Deprecated
    protected boolean getDeferAccept() {
        return false;
    }

    /**
     * 获取端点ID（默认返回null）
     */
    public String getId() {
        return null;
    }

    /**
     * 可协商协议列表
     */
    protected final List<String> negotiableProtocols = new ArrayList<>();

    public void addNegotiatedProtocol(String negotiableProtocol) {
        negotiableProtocols.add(negotiableProtocol);
    }

    public boolean hasNegotiableProtocols() {
        return (!negotiableProtocols.isEmpty());
    }

    /**
     * 套接字处理器
     */
    private Handler<S> handler = null;

    public void setHandler(Handler<S> handler) {
        this.handler = handler;
    }

    public Handler<S> getHandler() {
        return handler;
    }

    /**
     * 端点属性（用于传递配置到子组件）
     */
    protected HashMap<String, Object> attributes = new HashMap<>();

    /**
     * 设置端点属性
     *
     * @param name 属性名
     * @param value 属性值
     */
    public void setAttribute(String name, Object value) {
        if (getLog().isTraceEnabled()) {
            getLog().trace(sm.getString("endpoint.setAttribute", name, value));
        }
        attributes.put(name, value);
    }

    /**
     * 获取端点属性
     *
     * @param key 属性名
     * @return 属性值
     */
    public Object getAttribute(String key) {
        Object value = attributes.get(key);
        if (getLog().isTraceEnabled()) {
            getLog().trace(sm.getString("endpoint.getAttribute", key, value));
        }
        return value;
    }

    /**
     * 设置属性（支持套接字属性前缀）
     */
    public boolean setProperty(String name, String value) {
        setAttribute(name, value);
        final String socketName = "socket.";
        try {
            if (name.startsWith(socketName)) {
                return IntrospectionUtils.setProperty(socketProperties, name.substring(socketName.length()), value);
            } else {
                return IntrospectionUtils.setProperty(this, name, value, false);
            }
        } catch (Exception x) {
            getLog().error(sm.getString("endpoint.setAttributeError", name, value), x);
            return false;
        }
    }

    public String getProperty(String name) {
        String value = (String) getAttribute(name);
        final String socketName = "socket.";
        if (value == null && name.startsWith(socketName)) {
            Object result = IntrospectionUtils.getProperty(socketProperties, name.substring(socketName.length()));
            if (result != null) {
                value = result.toString();
            }
        }
        return value;
    }

    /**
     * 获取线程池当前线程数
     */
    public int getCurrentThreadCount() {
        Executor executor = this.executor;
        if (executor != null) {
            if (executor instanceof ThreadPoolExecutor) {
                return ((ThreadPoolExecutor) executor).getPoolSize();
            } else if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
                return ((java.util.concurrent.ThreadPoolExecutor) executor).getPoolSize();
            } else if (executor instanceof ResizableExecutor) {
                return ((ResizableExecutor) executor).getPoolSize();
            } else {
                return -1;
            }
        } else {
            return -2;
        }
    }

    /**
     * 获取线程池中繁忙线程数
     */
    public int getCurrentThreadsBusy() {
        Executor executor = this.executor;
        if (executor != null) {
            if (executor instanceof ThreadPoolExecutor) {
                return ((ThreadPoolExecutor) executor).getActiveCount();
            } else if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
                return ((java.util.concurrent.ThreadPoolExecutor) executor).getActiveCount();
            } else if (executor instanceof ResizableExecutor) {
                return ((ResizableExecutor) executor).getActiveCount();
            } else {
                return -1;
            }
        } else {
            return -2;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * 创建内部线程池
     */
    public void createExecutor() {
        internalExecutor = true;
        if (getUseVirtualThreads()) {
            executor = new VirtualThreadExecutor(getName() + "-virt-");
        } else {
            TaskQueue taskqueue = new TaskQueue(maxQueueSize);
            TaskThreadFactory tf = new TaskThreadFactory(getName() + "-exec-", daemon, getThreadPriority());
            executor = new ThreadPoolExecutor(getMinSpareThreads(), getMaxThreads(), getThreadsMaxIdleTime(),
                TimeUnit.MILLISECONDS, taskqueue, tf);
            taskqueue.setParent((ThreadPoolExecutor) executor);
        }
    }

    /**
     * 关闭内部线程池
     */
    public void shutdownExecutor() {
        Executor executor = this.executor;
        if (executor != null && internalExecutor) {
            this.executor = null;
            if (executor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
                tpe.shutdownNow();
                long timeout = getExecutorTerminationTimeoutMillis();
                if (timeout > 0) {
                    try {
                        tpe.awaitTermination(timeout, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        // 忽略中断
                    }
                    if (tpe.isTerminating()) {
                        getLog().warn(sm.getString("endpoint.warn.executorShutdown", getName()));
                    }
                }
                TaskQueue queue = (TaskQueue) tpe.getQueue();
                queue.setParent(null);
            }
        }
    }

    /**
     * 解锁服务器套接字接受线程
     */
    protected void unlockAccept() {
        if (acceptor == null || acceptor.getState() != AcceptorState.RUNNING) {
            return;
        }

        InetSocketAddress unlockAddress;
        InetSocketAddress localAddress = null;
        try {
            localAddress = getLocalAddress();
        } catch (IOException ioe) {
            getLog().debug(sm.getString("endpoint.debug.unlock.localFail", getName()), ioe);
        }
        if (localAddress == null) {
            getLog().warn(sm.getString("endpoint.debug.unlock.localNone", getName()));
            return;
        }

        try {
            unlockAddress = getUnlockAddress(localAddress);

            try (java.net.Socket s = new java.net.Socket()) {
                s.setSoTimeout(getSocketProperties().getUnlockTimeout());
                s.setSoLinger(true, 0);
                if (getLog().isTraceEnabled()) {
                    getLog().trace("About to unlock socket for:" + unlockAddress);
                }
                s.connect(unlockAddress, getSocketProperties().getUnlockTimeout());
                if (getLog().isTraceEnabled()) {
                    getLog().trace("Socket unlock completed for:" + unlockAddress);
                }
            }
            long startTime = System.nanoTime();
            while (startTime + 1_000_000_000 > System.nanoTime() && acceptor.getState() == AcceptorState.RUNNING) {
                if (startTime + 1_000_000 < System.nanoTime()) {
                    Thread.sleep(1);
                }
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            if (getLog().isDebugEnabled()) {
                getLog().debug(sm.getString("endpoint.debug.unlock.fail", String.valueOf(getPortWithOffset())), t);
            }
        }
    }

    /**
     * 获取解锁地址
     */
    private static InetSocketAddress getUnlockAddress(InetSocketAddress localAddress) throws SocketException {
        if (localAddress.getAddress().isAnyLocalAddress()) {
            InetAddress loopbackUnlockAddress = null;
            InetAddress linkLocalUnlockAddress = null;

            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (!networkInterface.isPointToPoint() && networkInterface.isUp()) {
                    Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress inetAddress = inetAddresses.nextElement();
                        if (localAddress.getAddress().getClass().isAssignableFrom(inetAddress.getClass())) {
                            if (inetAddress.isLoopbackAddress()) {
                                if (loopbackUnlockAddress == null) {
                                    loopbackUnlockAddress = inetAddress;
                                }
                            } else if (inetAddress.isLinkLocalAddress()) {
                                if (linkLocalUnlockAddress == null) {
                                    linkLocalUnlockAddress = inetAddress;
                                }
                            } else {
                                return new InetSocketAddress(inetAddress, localAddress.getPort());
                            }
                        }
                    }
                }
            }
            if (loopbackUnlockAddress != null) {
                return new InetSocketAddress(loopbackUnlockAddress, localAddress.getPort());
            }
            if (linkLocalUnlockAddress != null) {
                return new InetSocketAddress(linkLocalUnlockAddress, localAddress.getPort());
            }
            return new InetSocketAddress("localhost", localAddress.getPort());
        } else {
            return localAddress;
        }
    }

    // ---------------------------------------------- 请求处理方法

    /**
     * 处理套接字
     *
     * @param socketWrapper 套接字包装器
     * @param event 套接字事件
     * @param dispatch 是否在新线程中处理
     * @return 处理是否成功
     */
    public boolean processSocket(SocketWrapperBase<S> socketWrapper, SocketEvent event, boolean dispatch) {
        try {
            if (socketWrapper == null) {
                return false;
            }
            SocketProcessorBase<S> sc = null;
            if (processorCache != null) {
                sc = processorCache.pop();
            }
            if (sc == null) {
                sc = createSocketProcessor(socketWrapper, event);
            } else {
                sc.reset(socketWrapper, event);
            }
            Executor executor = getExecutor();
            if (dispatch && executor != null) {
                executor.execute(sc);
            } else {
                sc.run();
            }
        } catch (RejectedExecutionException ree) {
            getLog().warn(sm.getString("endpoint.executor.fail", socketWrapper), ree);
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            getLog().error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }

    /**
     * 创建套接字处理器（由子类实现）
     *
     * @param socketWrapper 套接字包装器
     * @param event 套接字事件
     * @return 套接字处理器
     */
    protected abstract SocketProcessorBase<S> createSocketProcessor(SocketWrapperBase<S> socketWrapper, SocketEvent event);

    // ------------------------------------------------------- 生命周期方法

    /**
     * 绑定端口（由子类实现）
     *
     * @throws Exception 如果绑定失败
     */
    public abstract void bind() throws Exception;

    /**
     * 取消绑定（由子类实现）
     *
     * @throws Exception 如果取消绑定失败
     */
    public abstract void unbind() throws Exception;

    /**
     * 启动端点内部逻辑（由子类实现）
     *
     * @throws Exception 如果启动失败
     */
    public abstract void startInternal() throws Exception;

    /**
     * 停止端点内部逻辑（由子类实现）
     *
     * @throws Exception 如果停止失败
     */
    public abstract void stopInternal() throws Exception;

    /**
     * 绑定端口并处理清理
     */
    private void bindWithCleanup() throws Exception {
        try {
            bind();
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            unbind();
            throw t;
        }
    }

    /**
     * 初始化端点
     *
     * @throws Exception 如果初始化失败
     */
    public final void init() throws Exception {
        if (bindOnInit) {
            bindWithCleanup();
            bindState = BindState.BOUND_ON_INIT;
        }
        if (this.domain != null) {
            oname = new ObjectName(domain + ":type=ThreadPool,name=\"" + getName() + "\"");
            Registry.getRegistry(null).registerComponent(this, oname, null);

            ObjectName socketPropertiesOname =
                new ObjectName(domain + ":type=SocketProperties,name=\"" + getName() + "\"");
            socketProperties.setObjectName(socketPropertiesOname);
            Registry.getRegistry(null).registerComponent(socketProperties, socketPropertiesOname, null);

            for (SSLHostConfig sslHostConfig : findSslHostConfigs()) {
                registerJmx(sslHostConfig);
            }
        }
    }

    /**
     * 注册SSL主机配置到JMX
     */
    private void registerJmx(SSLHostConfig sslHostConfig) {
        if (domain == null) {
            return;
        }
        ObjectName sslOname;
        try {
            sslOname = new ObjectName(domain + ":type=SSLHostConfig,ThreadPool=\"" + getName() + "\",name=" +
                ObjectName.quote(sslHostConfig.getHostName()));
            sslHostConfig.setObjectName(sslOname);
            try {
                Registry.getRegistry(null).registerComponent(sslHostConfig, sslOname, null);
            } catch (Exception e) {
                getLog().warn(sm.getString("endpoint.jmxRegistrationFailed", sslOname), e);
            }
        } catch (MalformedObjectNameException e) {
            getLog().warn(sm.getString("endpoint.invalidJmxNameSslHost", sslHostConfig.getHostName()), e);
        }

        for (SSLHostConfigCertificate sslHostConfigCert : sslHostConfig.getCertificates()) {
            ObjectName sslCertOname;
            try {
                sslCertOname = new ObjectName(
                    domain + ":type=SSLHostConfigCertificate,ThreadPool=\"" + getName() + "\",Host=" +
                        ObjectName.quote(sslHostConfig.getHostName()) + ",name=" + sslHostConfigCert.getType());
                sslHostConfigCert.setObjectName(sslCertOname);
                try {
                    Registry.getRegistry(null).registerComponent(sslHostConfigCert, sslCertOname, null);
                } catch (Exception e) {
                    getLog().warn(sm.getString("endpoint.jmxRegistrationFailed", sslCertOname), e);
                }
            } catch (MalformedObjectNameException e) {
                getLog().warn(sm.getString("endpoint.invalidJmxNameSslHostCert", sslHostConfig.getHostName(),
                    sslHostConfigCert.getType()), e);
            }
        }
    }

    /**
     * 从JMX注销SSL主机配置
     */
    private void unregisterJmx(SSLHostConfig sslHostConfig) {
        Registry registry = Registry.getRegistry(null);
        registry.unregisterComponent(sslHostConfig.getObjectName());
        for (SSLHostConfigCertificate sslHostConfigCert : sslHostConfig.getCertificates()) {
            registry.unregisterComponent(sslHostConfigCert.getObjectName());
        }
    }

    /**
     * 启动端点
     *
     * @throws Exception 如果启动失败
     */
    public final void start() throws Exception {
        if (bindState == BindState.UNBOUND) {
            bindWithCleanup();
            bindState = BindState.BOUND_ON_START;
        }
        startInternal();
    }

    /**
     * 启动接受线程
     */
    protected void startAcceptorThread() {
        acceptor = new Acceptor<>(this);
        String threadName = getName() + "-Acceptor";
        acceptor.setThreadName(threadName);
        Thread t = new Thread(acceptor, threadName);
        t.setPriority(getAcceptorThreadPriority());
        t.setDaemon(getDaemon());
        t.start();
    }

    /**
     * 暂停端点
     */
    public void pause() {
        if (running && !paused) {
            paused = true;
            releaseConnectionLatch();
            unlockAccept();
            getHandler().pause();
        }
    }

    /**
     * 恢复端点
     */
    public void resume() {
        if (running) {
            paused = false;
        }
    }

    /**
     * 停止端点
     *
     * @throws Exception 如果停止失败
     */
    public final void stop() throws Exception {
        stopInternal();
        if (bindState == BindState.BOUND_ON_START || bindState == BindState.SOCKET_CLOSED_ON_STOP) {
            unbind();
            bindState = BindState.UNBOUND;
        }
    }

    /**
     * 销毁端点
     *
     * @throws Exception 如果销毁失败
     */
    public final void destroy() throws Exception {
        if (bindState == BindState.BOUND_ON_INIT) {
            unbind();
            bindState = BindState.UNBOUND;
        }
        Registry registry = Registry.getRegistry(null);
        registry.unregisterComponent(oname);
        registry.unregisterComponent(socketProperties.getObjectName());
        for (SSLHostConfig sslHostConfig : findSslHostConfigs()) {
            unregisterJmx(sslHostConfig);
        }
    }

    /**
     * 获取日志记录器（由子类实现）
     *
     * @return 日志记录器
     */
    protected abstract Log getLog();

    /**
     * 获取证书日志记录器（默认使用普通日志记录器）
     *
     * @return 证书日志记录器
     */
    protected Log getLogCertificate() {
        return getLog();
    }

    /**
     * 初始化连接数限制latch
     *
     * @return 初始化的latch
     */
    protected LimitLatch initializeConnectionLatch() {
        if (maxConnections == -1) {
            return null;
        }
        if (connectionLimitLatch == null) {
            connectionLimitLatch = new LimitLatch(getMaxConnections());
        }
        return connectionLimitLatch;
    }

    /**
     * 释放连接数限制latch
     */
    private void releaseConnectionLatch() {
        LimitLatch latch = connectionLimitLatch;
        if (latch != null) {
            latch.releaseAll();
        }
        connectionLimitLatch = null;
    }

    /**
     * 增加连接计数或等待连接释放
     *
     * @throws InterruptedException 如果等待被中断
     */
    protected void countUpOrAwaitConnection() throws InterruptedException {
        if (maxConnections == -1) {
            return;
        }
        LimitLatch latch = connectionLimitLatch;
        if (latch != null) {
            latch.countUpOrAwait();
        }
    }

    /**
     * 减少连接计数
     *
     * @return 剩余连接数
     */
    protected long countDownConnection() {
        if (maxConnections == -1) {
            return -1;
        }
        LimitLatch latch = connectionLimitLatch;
        if (latch != null) {
            long result = latch.countDown();
            if (result < 0) {
                getLog().warn(sm.getString("endpoint.warn.incorrectConnectionCount"));
            }
            return result;
        } else {
            return -1;
        }
    }

    /**
     * 优雅关闭服务器套接字
     */
    public final void closeServerSocketGraceful() {
        if (bindState == BindState.BOUND_ON_START) {
            acceptor.stopMillis(-1);
            releaseConnectionLatch();
            unlockAccept();
            getHandler().pause();
            bindState = BindState.SOCKET_CLOSED_ON_STOP;
            try {
                doCloseServerSocket();
            } catch (IOException ioe) {
                getLog().warn(sm.getString("endpoint.serverSocket.closeFailed", getName()), ioe);
            }
        }
    }

    /**
     * 等待客户端连接优雅关闭
     *
     * @param waitMillis 最大等待时间（毫秒）
     * @return 剩余等待时间
     */
    public final long awaitConnectionsClose(long waitMillis) {
        while (waitMillis > 0 && !connections.isEmpty()) {
            try {
                Thread.sleep(50);
                waitMillis -= 50;
            } catch (InterruptedException e) {
                Thread.interrupted();
                waitMillis = 0;
            }
        }
        return waitMillis;
    }

    /**
     * 关闭服务器套接字（由子类实现）
     *
     * @throws IOException 如果关闭失败
     */
    protected abstract void doCloseServerSocket() throws IOException;

    /**
     * 接受服务器套接字连接（由子类实现）
     *
     * @return 接受的套接字
     * @throws Exception 如果接受失败
     */
    protected abstract U serverSocketAccept() throws Exception;

    /**
     * 设置套接字选项（由子类实现）
     *
     * @param socket 套接字
     * @return 是否设置成功
     */
    protected abstract boolean setSocketOptions(U socket);

    /**
     * 关闭套接字（使用包装器）
     *
     * @param socket 要关闭的套接字
     */
    protected void closeSocket(U socket) {
        SocketWrapperBase<S> socketWrapper = connections.get(socket);
        if (socketWrapper != null) {
            socketWrapper.close();
        }
    }

    /**
     * 销毁套接字（由子类实现）
     *
     * @param socket 要销毁的套接字
     */
    protected abstract void destroySocket(U socket);
}