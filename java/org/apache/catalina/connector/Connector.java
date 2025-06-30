/*
 * Apache许可证声明：该文件遵循Apache License 2.0协议，允许在合规条件下使用、修改和分发
 */
package org.apache.catalina.connector;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import javax.management.ObjectName;
import org.apache.catalina.Globals;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Service;
import org.apache.catalina.core.AprStatus;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.Adapter;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.http11.AbstractHttp11JsseProtocol;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.CharsetUtil;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.apache.tomcat.util.buf.StringUtils;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.openssl.OpenSSLImplementation;
import org.apache.tomcat.util.net.openssl.OpenSSLStatus;
import org.apache.tomcat.util.res.StringManager;

/**
 * Coyote连接器实现类：负责处理网络连接、请求解析和协议转换
 * @author Craig R. McClanahan, Remy Maucherat
 */
public class Connector extends LifecycleMBeanBase {
    // 日志工具，用于记录运行时信息
    private static final Log log = LogFactory.getLog(Connector.class);

    // 内部线程池名称，用于标识Tomcat内置的执行器
    public static final String INTERNAL_EXECUTOR_NAME = "Internal";

    // --------------------------- 构造函数 ---------------------------
    /**
     * 默认构造函数：使用HTTP/1.1 NIO协议
     */
    public Connector() {
        this("HTTP/1.1"); // 调用带协议参数的构造函数
    }

    /**
     * 带协议参数的构造函数
     * @param protocol 指定使用的协议（如"HTTP/1.1"、"AJP/1.3"）
     */
    public Connector(String protocol) {
        configuredProtocol = protocol; // 保存配置的协议名称
        ProtocolHandler p = null;
        try {
            // 通过协议名称创建对应的协议处理器
            p = ProtocolHandler.create(protocol);
        } catch (Exception e) {
            // 处理协议处理器创建失败的情况
            log.error(sm.getString("coyoteConnector.protocolHandlerInstantiationFailed"), e);
        }
        if (p != null) {
            protocolHandler = p; // 保存协议处理器实例
            protocolHandlerClassName = protocolHandler.getClass().getName(); // 记录类名
        } else {
            protocolHandler = null;
            protocolHandlerClassName = protocol; // 若创建失败，使用协议名作为类名
        }
        // 设置初始化失败时是否退出JVM，默认读取系统属性
        setThrowOnFailure(Boolean.getBoolean("org.apache.catalina.startup.EXIT_ON_INIT_FAILURE"));
    }

    /**
     * 带协议处理器的构造函数，用于自定义协议实现
     * @param protocolHandler 预创建的协议处理器实例
     */
    public Connector(ProtocolHandler protocolHandler) {
        protocolHandlerClassName = protocolHandler.getClass().getName(); // 记录类名
        configuredProtocol = protocolHandlerClassName; // 使用类名作为配置协议
        this.protocolHandler = protocolHandler; // 保存协议处理器
        // 同上：设置初始化失败时的退出策略
        setThrowOnFailure(Boolean.getBoolean("org.apache.catalina.startup.EXIT_ON_INIT_FAILURE"));
    }

    // --------------------------- 实例变量 ---------------------------
    /** 关联的Service组件（一个Service可包含多个Connector） */
    protected Service service = null;

    /** 是否允许URL中使用反斜杠（默认false，安全考虑） */
    protected boolean allowBackslash = false;

    /** 是否允许TRACE HTTP方法（默认false，避免安全风险） */
    protected boolean allowTrace = false;

    /** 异步请求的默认超时时间（毫秒，默认30000ms） */
    protected long asyncTimeout = 30000;

    /** 是否启用DNS反向查询（默认false，性能考虑） */
    protected boolean enableLookups = false;

    /** 当未指定字符编码时，是否强制使用ISO-8859-1（默认true，符合Servlet规范） */
    protected boolean enforceEncodingInGetWriter = true;

    /** 是否生成X-Powered-By响应头（默认false，避免暴露技术栈） */
    protected boolean xpoweredBy = false;

    /** 代理服务器的名称（用于反向代理场景下的URL重定向） */
    protected String proxyName = null;

    /** 代理服务器的端口（用于反向代理场景下的URL重定向） */
    protected int proxyPort = 0;

    /** 是否回收请求处理对象的门面（默认true，安全管理器启用时强制回收） */
    protected boolean discardFacades = true;

    /** 非SSL请求重定向到SSL的目标端口（默认443） */
    protected int redirectPort = 443;

    /** 请求的默认协议（http/https） */
    protected String scheme = "http";

    /** 请求的安全标志（是否为HTTPS连接） */
    protected boolean secure = false;

    /** 字符串资源管理器，用于国际化提示信息 */
    protected static final StringManager sm = StringManager.getManager(Connector.class);

    /** 最大允许的Cookie数量（默认200，-1表示无限制） */
    private int maxCookieCount = 200;

    /** 最大允许的参数数量（GET+POST，默认10000，-1无限制） */
    protected int maxParameterCount = 10000;

    /** 最大文件上传部件数量（默认10） */
    private int maxPartCount = 10;

    /** 最大部件头大小（字节，默认512） */
    private int maxPartHeaderSize = 512;

    /** 自动解析的POST请求最大大小（默认2MB） */
    protected int maxPostSize = 2 * 1024 * 1024;

    /** 认证时保存的POST请求最大大小（默认4KB） */
    protected int maxSavePostSize = 4 * 1024;

    /** 需要按POST规则解析请求体的HTTP方法列表（默认"POST"） */
    protected String parseBodyMethods = "POST";

    /** 解析请求体的方法集合（由parseBodyMethods转换而来） */
    protected HashSet<String> parseBodyMethodsSet;

    /** 是否启用基于IP的虚拟主机（默认false，使用域名） */
    protected boolean useIPVHosts = false;

    /** 协议处理器的类名（构造时确定，不可变） */
    protected final String protocolHandlerClassName;

    /** 配置的协议名称（构造时确定，不可变） */
    protected final String configuredProtocol;

    /** 协议处理器实例（处理网络连接和请求解析） */
    protected final ProtocolHandler protocolHandler;

    /** Coyote适配器，连接协议处理器和容器 */
    protected Adapter adapter = null;

    /** URI的字符编码（默认UTF-8） */
    private Charset uriCharset = StandardCharsets.UTF_8;

    /** 处理编码反斜杠（\）的策略 */
    private EncodedSolidusHandling encodedReverseSolidusHandling = EncodedSolidusHandling.DECODE;

    /** 处理编码斜杠（/）的策略（默认REJECT，拒绝非法编码） */
    private EncodedSolidusHandling encodedSolidusHandling = EncodedSolidusHandling.REJECT;

    /** 是否使用请求体编码作为URI编码（默认false） */
    protected boolean useBodyEncodingForURI = false;

    /** 是否拒绝可疑URI（增强安全性） */
    private boolean rejectSuspiciousURIs;

    // --------------------------- 属性访问方法 ---------------------------
    /**
     * 从协议处理器获取属性值
     * @param name 属性名
     * @return 属性值，不存在时返回null
     */
    public Object getProperty(String name) {
        if (protocolHandler == null) return null;
        return IntrospectionUtils.getProperty(protocolHandler, name); // 通过反射获取属性
    }

    /**
     * 设置协议处理器的属性
     * @param name 属性名
     * @param value 属性值（字符串形式）
     * @return 是否设置成功
     */
    public boolean setProperty(String name, String value) {
        if (protocolHandler == null) return false;
        return IntrospectionUtils.setProperty(protocolHandler, name, value); // 通过反射设置属性
    }

    // 以下是各实例变量的getter和setter方法，注释从简
    public Service getService() { return this.service; }
    public void setService(Service service) { this.service = service; }

    public boolean getAllowBackslash() { return allowBackslash; }
    public void setAllowBackslash(boolean allowBackslash) { this.allowBackslash = allowBackslash; }

    public boolean getAllowTrace() { return this.allowTrace; }
    public void setAllowTrace(boolean allowTrace) { this.allowTrace = allowTrace; }

    public long getAsyncTimeout() { return asyncTimeout; }
    public void setAsyncTimeout(long asyncTimeout) { this.asyncTimeout = asyncTimeout; }

    public boolean getDiscardFacades() {
        // 安全管理器启用时强制回收门面对象
        return discardFacades || Globals.IS_SECURITY_ENABLED;
    }
    public void setDiscardFacades(boolean discardFacades) { this.discardFacades = discardFacades; }

    public boolean getEnableLookups() { return this.enableLookups; }
    public void setEnableLookups(boolean enableLookups) { this.enableLookups = enableLookups; }

    public boolean getEnforceEncodingInGetWriter() { return enforceEncodingInGetWriter; }
    public void setEnforceEncodingInGetWriter(boolean enforceEncodingInGetWriter) {
        this.enforceEncodingInGetWriter = enforceEncodingInGetWriter;
    }

    // 省略部分getter/setter注释（逻辑类似）
    public int getMaxCookieCount() { return maxCookieCount; }
    public void setMaxCookieCount(int maxCookieCount) { this.maxCookieCount = maxCookieCount; }

    public int getMaxParameterCount() { return maxParameterCount; }
    public void setMaxParameterCount(int maxParameterCount) { this.maxParameterCount = maxParameterCount; }

    public int getMaxPartCount() { return maxPartCount; }
    public void setMaxPartCount(int maxPartCount) { this.maxPartCount = maxPartCount; }

    public int getMaxPartHeaderSize() { return maxPartHeaderSize; }
    public void setMaxPartHeaderSize(int maxPartHeaderSize) { this.maxPartHeaderSize = maxPartHeaderSize; }

    public int getMaxPostSize() { return maxPostSize; }
    public void setMaxPostSize(int maxPostSize) { this.maxPostSize = maxPostSize; }

    public int getMaxSavePostSize() { return maxSavePostSize; }
    public void setMaxSavePostSize(int maxSavePostSize) {
        this.maxSavePostSize = maxSavePostSize;
        setProperty("maxSavePostSize", String.valueOf(maxSavePostSize));
    }

    public String getParseBodyMethods() { return this.parseBodyMethods; }
    public void setParseBodyMethods(String methods) {
        // 解析方法列表并验证（不允许包含TRACE方法）
        HashSet<String> methodSet = new HashSet<>();
        if (methods != null) {
            methodSet.addAll(Arrays.asList(StringUtils.splitCommaSeparated(methods)));
        }
        if (methodSet.contains("TRACE")) {
            throw new IllegalArgumentException(sm.getString("coyoteConnector.parseBodyMethodNoTrace"));
        }
        this.parseBodyMethods = methods;
        this.parseBodyMethodsSet = methodSet;
    }

    /** 判断指定方法是否需要解析请求体 */
    protected boolean isParseBodyMethod(String method) {
        return parseBodyMethodsSet.contains(method);
    }

    /** 获取连接器监听的端口（考虑端口偏移） */
    public int getPort() {
        // 优先使用AbstractProtocol的快捷方式（性能优化）
        if (protocolHandler instanceof AbstractProtocol<?>) {
            return ((AbstractProtocol<?>) protocolHandler).getPort();
        }
        // 反射获取属性（适用于自定义协议处理器）
        Object port = getProperty("port");
        if (port instanceof Integer) {
            return ((Integer) port).intValue();
        }
        return -1; // 无效配置
    }

    public void setPort(int port) { setProperty("port", String.valueOf(port)); }

    // 省略部分方法注释（逻辑类似端口获取）
    public int getPortOffset() {
        if (protocolHandler instanceof AbstractProtocol<?>) {
            return ((AbstractProtocol<?>) protocolHandler).getPortOffset();
        }
        Object port = getProperty("portOffset");
        if (port instanceof Integer) {
            return ((Integer) port).intValue();
        }
        return 0;
    }

    public void setPortOffset(int portOffset) { setProperty("portOffset", String.valueOf(portOffset)); }

    public int getPortWithOffset() {
        int port = getPort();
        return port > 0 ? port + getPortOffset() : port; // 计算实际端口
    }

    public int getLocalPort() {
        return ((Integer) getProperty("localPort")).intValue(); // 获取实际绑定的端口
    }

    public String getProtocol() { return configuredProtocol; }
    public String getProtocolHandlerClassName() { return this.protocolHandlerClassName; }
    public ProtocolHandler getProtocolHandler() { return this.protocolHandler; }

    public String getProxyName() { return this.proxyName; }
    public void setProxyName(String proxyName) {
        this.proxyName = proxyName != null && !proxyName.isEmpty() ? proxyName : null;
    }

    public int getProxyPort() { return this.proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    public int getRedirectPort() { return this.redirectPort; }
    public void setRedirectPort(int redirectPort) { this.redirectPort = redirectPort; }

    public int getRedirectPortWithOffset() { return getRedirectPort() + getPortOffset(); }

    public String getScheme() { return this.scheme; }
    public void setScheme(String scheme) { this.scheme = scheme; }

    public boolean getSecure() { return this.secure; }
    public void setSecure(boolean secure) {
        this.secure = secure;
        setProperty("secure", Boolean.toString(secure));
    }

    /** 获取URI编码的字符集名称（原始大小写） */
    public String getURIEncoding() { return uriCharset.name(); }

    /** 获取URI编码的Charset实例（非null） */
    public Charset getURICharset() { return uriCharset; }

    /** 设置URI编码，验证是否为ASCII超集（避免编码问题） */
    public void setURIEncoding(String URIEncoding) {
        try {
            Charset charset = B2CConverter.getCharset(URIEncoding);
            if (!CharsetUtil.isAsciiSuperset(charset)) {
                log.error(sm.getString("coyoteConnector.notAsciiSuperset", URIEncoding, uriCharset.name()));
                return;
            }
            uriCharset = charset;
        } catch (UnsupportedEncodingException e) {
            log.error(sm.getString("coyoteConnector.invalidEncoding", URIEncoding, uriCharset.name()), e);
        }
    }

    public boolean getUseBodyEncodingForURI() { return this.useBodyEncodingForURI; }
    public void setUseBodyEncodingForURI(boolean useBodyEncodingForURI) {
        this.useBodyEncodingForURI = useBodyEncodingForURI;
    }

    public boolean getXpoweredBy() { return xpoweredBy; }
    public void setXpoweredBy(boolean xpoweredBy) { this.xpoweredBy = xpoweredBy; }

    public void setUseIPVHosts(boolean useIPVHosts) { this.useIPVHosts = useIPVHosts; }
    public boolean getUseIPVHosts() { return useIPVHosts; }

    /** 获取当前使用的执行器名称（内部或自定义） */
    public String getExecutorName() {
        Object obj = protocolHandler.getExecutor();
        if (obj instanceof org.apache.catalina.Executor) {
            return ((org.apache.catalina.Executor) obj).getName();
        }
        return INTERNAL_EXECUTOR_NAME;
    }

    // SSL和升级协议相关方法
    public void addSslHostConfig(SSLHostConfig sslHostConfig) {
        protocolHandler.addSslHostConfig(sslHostConfig);
    }

    public SSLHostConfig[] findSslHostConfigs() {
        return protocolHandler.findSslHostConfigs();
    }

    public void addUpgradeProtocol(UpgradeProtocol upgradeProtocol) {
        protocolHandler.addUpgradeProtocol(upgradeProtocol);
    }

    public UpgradeProtocol[] findUpgradeProtocols() {
        return protocolHandler.findUpgradeProtocols();
    }

    // 编码处理策略相关方法
    public String getEncodedReverseSolidusHandling() {
        return encodedReverseSolidusHandling.getValue();
    }

    public void setEncodedReverseSolidusHandling(String encodedReverseSolidusHandling) {
        this.encodedReverseSolidusHandling = EncodedSolidusHandling.fromString(encodedReverseSolidusHandling);
    }

    public EncodedSolidusHandling getEncodedReverseSolidusHandlingInternal() {
        return encodedReverseSolidusHandling;
    }

    public String getEncodedSolidusHandling() {
        return encodedSolidusHandling.getValue();
    }

    public void setEncodedSolidusHandling(String encodedSolidusHandling) {
        this.encodedSolidusHandling = EncodedSolidusHandling.fromString(encodedSolidusHandling);
    }

    public EncodedSolidusHandling getEncodedSolidusHandlingInternal() {
        return encodedSolidusHandling;
    }

    public boolean getRejectSuspiciousURIs() { return rejectSuspiciousURIs; }
    public void setRejectSuspiciousURIs(boolean rejectSuspiciousURIs) {
        this.rejectSuspiciousURIs = rejectSuspiciousURIs;
    }

    // --------------------------- 公共方法 ---------------------------
    /** 创建适合容器处理的Request对象 */
    public Request createRequest() {
        return new Request(this); // 传入当前Connector作为上下文
    }

    /** 创建适合容器处理的Response对象（支持缓冲区大小配置） */
    public Response createResponse() {
        int size = protocolHandler.getDesiredBufferSize();
        return size > 0 ? new Response(size) : new Response();
    }

    /** 创建MBean对象名的键值属性（用于JMX注册） */
    protected String createObjectNameKeyProperties(String type) {
        Object addressObj = getProperty("address");
        StringBuilder sb = new StringBuilder("type=");
        sb.append(type);
        String id = (protocolHandler != null) ? protocolHandler.getId() : null;
        if (id != null) {
            // 兼容旧版MBean命名规则
            sb.append(",port=0,address=").append(ObjectName.quote(id));
        } else {
            sb.append(",port=");
            int port = getPortWithOffset();
            if (port > 0) {
                sb.append(port);
            } else {
                sb.append("auto-").append(getProperty("nameIndex"));
            }
            String address = addressObj instanceof InetAddress
                ? ((InetAddress) addressObj).getHostAddress()
                : (addressObj != null ? addressObj.toString() : "");
            if (!address.isEmpty()) {
                sb.append(",address=").append(ObjectName.quote(address));
            }
        }
        return sb.toString();
    }

    /** 暂停连接器（暂停接收新请求） */
    public void pause() {
        try {
            if (protocolHandler != null) {
                protocolHandler.pause(); // 调用协议处理器的暂停方法
            }
        } catch (Exception e) {
            log.error(sm.getString("coyoteConnector.protocolHandlerPauseFailed"), e);
        }
    }

    /** 恢复连接器（重新接收请求） */
    public void resume() {
        try {
            if (protocolHandler != null) {
                protocolHandler.resume(); // 调用协议处理器的恢复方法
            }
        } catch (Exception e) {
            log.error(sm.getString("coyoteConnector.protocolHandlerResumeFailed"), e);
        }
    }

    // --------------------------- 生命周期方法 ---------------------------
    /** 初始化连接器内部资源 */
    @Override
    protected void initInternal() throws LifecycleException {
        super.initInternal(); // 调用父类初始化方法

        if (protocolHandler == null) {
            throw new LifecycleException(sm.getString("coyoteConnector.protocolHandlerInstantiationFailed"));
        }

        // 初始化适配器：连接协议处理器和容器
        adapter = new CoyoteAdapter(this);
        protocolHandler.setAdapter(adapter);

        // 确保parseBodyMethodsSet有默认值
        if (parseBodyMethodsSet == null) {
            setParseBodyMethods(getParseBodyMethods());
        }

        // 自动检测并配置OpenSSL或APR（若可用）
        if (JreCompat.isJre22Available() && OpenSSLStatus.getUseOpenSSL() && OpenSSLStatus.isAvailable() &&
            protocolHandler instanceof AbstractHttp11Protocol) {
            AbstractHttp11JsseProtocol<?> jsseProtocolHandler = (AbstractHttp11JsseProtocol<?>) protocolHandler;
            if (jsseProtocolHandler.isSSLEnabled() && jsseProtocolHandler.getSslImplementationName() == null) {
                // 使用Panama OpenSSL实现（JDK 22+）
                jsseProtocolHandler.setSslImplementationName(
                    "org.apache.tomcat.util.net.openssl.panama.OpenSSLImplementation");
            }
        } else if (AprStatus.isAprAvailable() && AprStatus.getUseOpenSSL() &&
            protocolHandler instanceof AbstractHttp11Protocol) {
            AbstractHttp11JsseProtocol<?> jsseProtocolHandler = (AbstractHttp11JsseProtocol<?>) protocolHandler;
            if (jsseProtocolHandler.isSSLEnabled() && jsseProtocolHandler.getSslImplementationName() == null) {
                // 使用tomcat-native的OpenSSL实现
                jsseProtocolHandler.setSslImplementationName(OpenSSLImplementation.class.getName());
            }
        }

        // 初始化协议处理器
        try {
            protocolHandler.init();
        } catch (Exception e) {
            throw new LifecycleException(sm.getString("coyoteConnector.protocolHandlerInitializationFailed"), e);
        }
    }

    /** 启动连接器（开始接收请求） */
    @Override
    protected void startInternal() throws LifecycleException {
        // 验证配置（端口不可为负）
        String id = (protocolHandler != null) ? protocolHandler.getId() : null;
        if (id == null && getPortWithOffset() < 0) {
            throw new LifecycleException(sm.getString("coyoteConnector.invalidPort", getPortWithOffset()));
        }

        setState(LifecycleState.STARTING); // 更新生命周期状态

        // 配置工具执行器（用于异步任务）
        if (protocolHandler != null && service != null) {
            protocolHandler.setUtilityExecutor(service.getServer().getUtilityExecutor());
        }

        // 启动协议处理器
        try {
            protocolHandler.start();
        } catch (Exception e) {
            throw new LifecycleException(sm.getString("coyoteConnector.protocolHandlerStartFailed"), e);
        }
    }

    /** 停止连接器（停止接收请求） */
    @Override
    protected void stopInternal() throws LifecycleException {
        setState(LifecycleState.STOPPING); // 更新生命周期状态

        try {
            if (protocolHandler != null) {
                protocolHandler.stop(); // 停止协议处理器
            }
        } catch (Exception e) {
            throw new LifecycleException(sm.getString("coyoteConnector.protocolHandlerStopFailed"), e);
        }

        // 停止后移除工具执行器（释放资源）
        if (protocolHandler != null) {
            protocolHandler.setUtilityExecutor(null);
        }
    }

    /** 销毁连接器（释放所有资源） */
    @Override
    protected void destroyInternal() throws LifecycleException {
        try {
            if (protocolHandler != null) {
                protocolHandler.destroy(); // 销毁协议处理器
            }
        } catch (Exception e) {
            throw new LifecycleException(sm.getString("coyoteConnector.protocolHandlerDestroyFailed"), e);
        }

        // 从Service中移除自身引用
        if (getService() != null) {
            getService().removeConnector(this);
        }

        super.destroyInternal(); // 调用父类销毁方法
    }

    /** 重写toString方法，返回连接器的描述信息 */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Connector[");
        String name = (String) getProperty("name");
        if (name == null) {
            sb.append(getProtocol()).append('-');
            String id = (protocolHandler != null) ? protocolHandler.getId() : null;
            if (id != null) {
                sb.append(id);
            } else {
                int port = getPortWithOffset();
                if (port > 0) {
                    sb.append(port);
                } else {
                    sb.append("auto-").append(getProperty("nameIndex"));
                }
            }
        } else {
            sb.append(name);
        }
        sb.append(']');
        return sb.toString();
    }

    // --------------------------- JMX相关方法 ---------------------------
    /** 获取MBean的域名（继承自Service） */
    @Override
    protected String getDomainInternal() {
        Service s = getService();
        return s != null ? service.getDomain() : null;
    }

    /** 获取MBean对象名的键值属性（调用自定义方法） */
    @Override
    protected String getObjectNameKeyProperties() {
        return createObjectNameKeyProperties("Connector");
    }
}