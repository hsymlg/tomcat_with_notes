/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.coyote.http11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.management.ObjectInstance;
import javax.management.ObjectName;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpUpgradeHandler;

import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.CompressionConfig;
import org.apache.coyote.ContinueResponseTiming;
import org.apache.coyote.Processor;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.UpgradeToken;
import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.coyote.http11.upgrade.UpgradeGroupInfo;
import org.apache.coyote.http11.upgrade.UpgradeProcessorExternal;
import org.apache.coyote.http11.upgrade.UpgradeProcessorInternal;
import org.apache.tomcat.util.buf.StringUtils;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.modeler.Util;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.apache.tomcat.util.res.StringManager;

/**
 * HTTP/1.1协议处理器的抽象基类
 * 提供HTTP/1.1协议处理的通用功能和配置
 * 实现了HTTP/1.1协议的核心逻辑，包括请求解析、响应生成、连接管理等
 */
public abstract class AbstractHttp11Protocol<S> extends AbstractProtocol<S> {

    // 字符串资源管理器，用于获取国际化提示信息
    protected static final StringManager sm = StringManager.getManager(AbstractHttp11Protocol.class);

    // 压缩配置处理器，管理HTTP响应压缩相关配置
    private final CompressionConfig compressionConfig = new CompressionConfig();

    // HTTP解析器，用于解析HTTP请求和响应
    private HttpParser httpParser = null;

    /**
     * 构造函数
     * @param endpoint 关联的端点实例，处理底层网络连接
     */
    public AbstractHttp11Protocol(AbstractEndpoint<S,?> endpoint) {
        super(endpoint); // 调用父类构造函数
        setConnectionTimeout(Constants.DEFAULT_CONNECTION_TIMEOUT); // 设置默认连接超时时间
    }

    /**
     * 初始化协议处理器
     * 配置HTTP解析器和升级协议
     */
    @Override
    public void init() throws Exception {
        // 创建HTTP解析器，用于解析HTTP请求和响应
        httpParser = new HttpParser(relaxedPathChars, relaxedQueryChars);

        // 先配置升级协议，因为端点初始化时需要这些配置
        for (UpgradeProtocol upgradeProtocol : upgradeProtocols) {
            configureUpgradeProtocol(upgradeProtocol);
        }

        try {
            super.init(); // 调用父类初始化方法
        } finally {
            // 初始化完成后设置Http11Protocol引用到升级协议
            for (UpgradeProtocol upgradeProtocol : upgradeProtocols) {
                upgradeProtocol.setHttp11Protocol(this);
            }
        }
    }

    /**
     * 销毁协议处理器
     * 注销升级协议相关的MBean
     */
    @Override
    public void destroy() throws Exception {
        // 注销升级协议相关的MBean
        ObjectName rgOname = getGlobalRequestProcessorMBeanName();
        if (rgOname != null) {
            Registry registry = Registry.getRegistry(null);
            ObjectName query = new ObjectName(rgOname.getCanonicalName() + ",Upgrade=*");
            Set<ObjectInstance> upgrades = registry.getMBeanServer().queryMBeans(query, null);
            for (ObjectInstance upgrade : upgrades) {
                registry.unregisterComponent(upgrade.getObjectName());
            }
        }

        super.destroy(); // 调用父类销毁方法
    }

    /**
     * 获取协议名称
     * @return 协议名称为"Http"
     */
    @Override
    protected String getProtocolName() {
        return "Http";
    }

    /**
     * 获取关联的端点（覆盖父类方法，提供更具体的类型）
     * @return 关联的端点实例
     */
    @Override
    protected AbstractEndpoint<S,?> getEndpoint() {
        return super.getEndpoint();
    }

    /**
     * 获取HTTP解析器
     * @return HTTP解析器实例
     */
    public HttpParser getHttpParser() {
        return httpParser;
    }

    // -------------------------- HTTP特定属性配置 --------------------------

    // 继续响应定时配置
    private ContinueResponseTiming continueResponseTiming = ContinueResponseTiming.IMMEDIATELY;

    /**
     * 获取继续响应定时策略
     * @return 继续响应定时策略名称
     */
    public String getContinueResponseTiming() {
        return continueResponseTiming.toString();
    }

    /**
     * 设置继续响应定时策略
     * @param continueResponseTiming 继续响应定时策略名称
     */
    public void setContinueResponseTiming(String continueResponseTiming) {
        this.continueResponseTiming = ContinueResponseTiming.fromString(continueResponseTiming);
    }

    /**
     * 获取内部继续响应定时策略
     * @return 继续响应定时策略枚举值
     */
    public ContinueResponseTiming getContinueResponseTimingInternal() {
        return continueResponseTiming;
    }

    // 是否在响应中使用Keep-Alive头部
    private boolean useKeepAliveResponseHeader = true;

    /**
     * 获取是否使用Keep-Alive响应头部
     * @return true如果使用，否则false
     */
    public boolean getUseKeepAliveResponseHeader() {
        return useKeepAliveResponseHeader;
    }

    /**
     * 设置是否使用Keep-Alive响应头部
     * @param useKeepAliveResponseHeader true表示使用，false表示不使用
     */
    public void setUseKeepAliveResponseHeader(boolean useKeepAliveResponseHeader) {
        this.useKeepAliveResponseHeader = useKeepAliveResponseHeader;
    }

    // 宽松路径字符配置
    private String relaxedPathChars = null;

    /**
     * 获取宽松路径字符
     * @return 宽松路径字符字符串
     */
    public String getRelaxedPathChars() {
        return relaxedPathChars;
    }

    /**
     * 设置宽松路径字符
     * @param relaxedPathChars 宽松路径字符字符串
     */
    public void setRelaxedPathChars(String relaxedPathChars) {
        this.relaxedPathChars = relaxedPathChars;
    }

    // 宽松查询字符配置
    private String relaxedQueryChars = null;

    /**
     * 获取宽松查询字符
     * @return 宽松查询字符字符串
     */
    public String getRelaxedQueryChars() {
        return relaxedQueryChars;
    }

    /**
     * 设置宽松查询字符
     * @param relaxedQueryChars 宽松查询字符字符串
     */
    public void setRelaxedQueryChars(String relaxedQueryChars) {
        this.relaxedQueryChars = relaxedQueryChars;
    }

    // 允许主机头不匹配配置（已弃用）
    private boolean allowHostHeaderMismatch = false;

    /**
     * 获取是否允许主机头不匹配
     * @return true表示允许，否则false（已弃用）
     */
    @Deprecated
    public boolean getAllowHostHeaderMismatch() {
        return allowHostHeaderMismatch;
    }

    /**
     * 设置是否允许主机头不匹配
     * @param allowHostHeaderMismatch true表示允许，否则false（已弃用）
     */
    @Deprecated
    public void setAllowHostHeaderMismatch(boolean allowHostHeaderMismatch) {
        this.allowHostHeaderMismatch = allowHostHeaderMismatch;
    }

    // 拒绝非法头部配置（已弃用）
    private boolean rejectIllegalHeader = true;

    /**
     * 获取是否拒绝非法头部
     * @return true表示拒绝，否则false（已弃用）
     */
    @Deprecated
    public boolean getRejectIllegalHeader() {
        return rejectIllegalHeader;
    }

    /**
     * 设置是否拒绝非法头部
     * @param rejectIllegalHeader true表示拒绝，否则false（已弃用）
     */
    @Deprecated
    public void setRejectIllegalHeader(boolean rejectIllegalHeader) {
        this.rejectIllegalHeader = rejectIllegalHeader;
    }

    // 最大保存POST大小配置
    private int maxSavePostSize = 4 * 1024;

    /**
     * 获取最大保存POST大小
     * @return 最大保存POST大小（字节）
     */
    public int getMaxSavePostSize() {
        return maxSavePostSize;
    }

    /**
     * 设置最大保存POST大小
     * @param maxSavePostSize 最大保存POST大小（字节）
     */
    public void setMaxSavePostSize(int maxSavePostSize) {
        this.maxSavePostSize = maxSavePostSize;
    }

    // 最大HTTP头部大小配置
    private int maxHttpHeaderSize = 8 * 1024;

    /**
     * 获取最大HTTP头部大小
     * @return 最大HTTP头部大小（字节）
     */
    public int getMaxHttpHeaderSize() {
        return maxHttpHeaderSize;
    }

    /**
     * 设置最大HTTP头部大小
     * @param valueI 最大HTTP头部大小（字节）
     */
    public void setMaxHttpHeaderSize(int valueI) {
        maxHttpHeaderSize = valueI;
    }

    // 最大HTTP请求头部大小配置
    private int maxHttpRequestHeaderSize = -1;

    /**
     * 获取最大HTTP请求头部大小
     * @return 最大HTTP请求头部大小（字节），-1表示使用全局配置
     */
    public int getMaxHttpRequestHeaderSize() {
        return maxHttpRequestHeaderSize == -1 ? getMaxHttpHeaderSize() : maxHttpRequestHeaderSize;
    }

    /**
     * 设置最大HTTP请求头部大小
     * @param valueI 最大HTTP请求头部大小（字节），-1表示使用全局配置
     */
    public void setMaxHttpRequestHeaderSize(int valueI) {
        maxHttpRequestHeaderSize = valueI;
    }

    // 最大HTTP响应头部大小配置
    private int maxHttpResponseHeaderSize = -1;

    /**
     * 获取最大HTTP响应头部大小
     * @return 最大HTTP响应头部大小（字节），-1表示使用全局配置
     */
    public int getMaxHttpResponseHeaderSize() {
        return maxHttpResponseHeaderSize == -1 ? getMaxHttpHeaderSize() : maxHttpResponseHeaderSize;
    }

    /**
     * 设置最大HTTP响应头部大小
     * @param valueI 最大HTTP响应头部大小（字节），-1表示使用全局配置
     */
    public void setMaxHttpResponseHeaderSize(int valueI) {
        maxHttpResponseHeaderSize = valueI;
    }

    // 连接上传超时配置
    private int connectionUploadTimeout = 300000;

    /**
     * 获取连接上传超时时间
     * @return 连接上传超时时间（毫秒）
     */
    public int getConnectionUploadTimeout() {
        return connectionUploadTimeout;
    }

    /**
     * 设置连接上传超时时间
     * @param timeout 连接上传超时时间（毫秒）
     */
    public void setConnectionUploadTimeout(int timeout) {
        connectionUploadTimeout = timeout;
    }

    // 禁用上传超时配置
    private boolean disableUploadTimeout = true;

    /**
     * 获取是否禁用上传超时
     * @return true表示禁用，否则false
     */
    public boolean getDisableUploadTimeout() {
        return disableUploadTimeout;
    }

    /**
     * 设置是否禁用上传超时
     * @param isDisabled true表示禁用，否则false
     */
    public void setDisableUploadTimeout(boolean isDisabled) {
        disableUploadTimeout = isDisabled;
    }

    // 压缩配置代理方法
    public void setCompression(String compression) {
        compressionConfig.setCompression(compression);
    }

    public String getCompression() {
        return compressionConfig.getCompression();
    }

    protected int getCompressionLevel() {
        return compressionConfig.getCompressionLevel();
    }

    // 不压缩的User-Agent配置
    public String getNoCompressionUserAgents() {
        return compressionConfig.getNoCompressionUserAgents();
    }

    protected Pattern getNoCompressionUserAgentsPattern() {
        return compressionConfig.getNoCompressionUserAgentsPattern();
    }

    public void setNoCompressionUserAgents(String noCompressionUserAgents) {
        compressionConfig.setNoCompressionUserAgents(noCompressionUserAgents);
    }

    // 可压缩MIME类型配置
    public String getCompressibleMimeType() {
        return compressionConfig.getCompressibleMimeType();
    }

    public void setCompressibleMimeType(String valueS) {
        compressionConfig.setCompressibleMimeType(valueS);
    }

    public String[] getCompressibleMimeTypes() {
        return compressionConfig.getCompressibleMimeTypes();
    }

    // 压缩最小大小配置
    public int getCompressionMinSize() {
        return compressionConfig.getCompressionMinSize();
    }

    public void setCompressionMinSize(int compressionMinSize) {
        compressionConfig.setCompressionMinSize(compressionMinSize);
    }

    // 判断是否使用压缩
    public boolean useCompression(Request request, Response response) {
        return compressionConfig.useCompression(request, response);
    }

    // 受限User-Agent配置
    private Pattern restrictedUserAgents = null;

    /**
     * 获取受限User-Agent的正则表达式
     * @return 受限User-Agent的正则表达式字符串
     */
    public String getRestrictedUserAgents() {
        if (restrictedUserAgents == null) {
            return null;
        } else {
            return restrictedUserAgents.toString();
        }
    }

    /**
     * 获取受限User-Agent的正则表达式模式
     * @return 受限User-Agent的正则表达式模式
     */
    protected Pattern getRestrictedUserAgentsPattern() {
        return restrictedUserAgents;
    }

    /**
     * 设置受限User-Agent的正则表达式
     * @param restrictedUserAgents 受限User-Agent的正则表达式
     */
    public void setRestrictedUserAgents(String restrictedUserAgents) {
        if (restrictedUserAgents == null || restrictedUserAgents.isEmpty()) {
            this.restrictedUserAgents = null;
        } else {
            this.restrictedUserAgents = Pattern.compile(restrictedUserAgents);
        }
    }

    // Server头部配置
    private String server;

    /**
     * 获取Server头部值
     * @return Server头部值
     */
    public String getServer() {
        return server;
    }

    /**
     * 设置Server头部值
     * @param server Server头部值
     */
    public void setServer(String server) {
        this.server = server;
    }

    // 是否移除应用提供的Server头部值
    private boolean serverRemoveAppProvidedValues = false;

    /**
     * 获取是否移除应用提供的Server头部值
     * @return true表示移除，否则false
     */
    public boolean getServerRemoveAppProvidedValues() {
        return serverRemoveAppProvidedValues;
    }

    public void setServerRemoveAppProvidedValues(boolean serverRemoveAppProvidedValues) {
        this.serverRemoveAppProvidedValues = serverRemoveAppProvidedValues;
    }

    // 最大 trailers 大小配置
    private int maxTrailerSize = 8192;

    /**
     * 获取最大 trailers 大小
     * @return 最大 trailers 大小（字节）
     */
    public int getMaxTrailerSize() {
        return maxTrailerSize;
    }

    public void setMaxTrailerSize(int maxTrailerSize) {
        this.maxTrailerSize = maxTrailerSize;
    }

    // 分块编码中扩展信息的最大大小配置
    private int maxExtensionSize = 8192;

    /**
     * 获取分块编码中扩展信息的最大大小
     * @return 扩展信息的最大大小（字节）
     */
    public int getMaxExtensionSize() {
        return maxExtensionSize;
    }

    public void setMaxExtensionSize(int maxExtensionSize) {
        this.maxExtensionSize = maxExtensionSize;
    }

    // 最大吞咽请求体大小配置
    private int maxSwallowSize = 2 * 1024 * 1024;

    /**
     * 获取最大吞咽请求体大小
     * @return 最大吞咽请求体大小（字节）
     */
    public int getMaxSwallowSize() {
        return maxSwallowSize;
    }

    public void setMaxSwallowSize(int maxSwallowSize) {
        this.maxSwallowSize = maxSwallowSize;
    }

    // 是否安全连接配置
    private boolean secure;

    /**
     * 获取是否为安全连接
     * @return true表示安全连接，否则false
     */
    public boolean getSecure() {
        return secure;
    }

    public void setSecure(boolean b) {
        secure = b;
    }

    // 允许的 trailers 头部集合
    private final Set<String> allowedTrailerHeaders = ConcurrentHashMap.newKeySet();

    /**
     * 设置允许的 trailers 头部
     * @param commaSeparatedHeaders 逗号分隔的头部名称字符串
     */
    public void setAllowedTrailerHeaders(String commaSeparatedHeaders) {
        Set<String> toRemove = new HashSet<>(allowedTrailerHeaders);
        if (commaSeparatedHeaders != null) {
            String[] headers = commaSeparatedHeaders.split(",");
            for (String header : headers) {
                String trimmedHeader = header.trim().toLowerCase(Locale.ENGLISH);
                if (toRemove.contains(trimmedHeader)) {
                    toRemove.remove(trimmedHeader);
                } else {
                    allowedTrailerHeaders.add(trimmedHeader);
                }
            }
            allowedTrailerHeaders.removeAll(toRemove);
        }
    }

    /**
     * 获取内部允许的 trailers 头部集合
     * @return 允许的 trailers 头部集合
     */
    protected Set<String> getAllowedTrailerHeadersInternal() {
        return allowedTrailerHeaders;
    }

    /**
     * 判断指定头部是否允许作为 trailers
     * @param headerName 头部名称
     * @return true表示允许，否则false
     */
    public boolean isTrailerHeaderAllowed(String headerName) {
        return allowedTrailerHeaders.contains(headerName);
    }

    /**
     * 获取允许的 trailers 头部字符串
     * @return 逗号分隔的允许头部名称字符串
     */
    public String getAllowedTrailerHeaders() {
        List<String> copy = new ArrayList<>(allowedTrailerHeaders);
        return StringUtils.join(copy);
    }

    /**
     * 添加允许的 trailers 头部
     * @param header 头部名称
     */
    public void addAllowedTrailerHeader(String header) {
        if (header != null) {
            allowedTrailerHeaders.add(header.trim().toLowerCase(Locale.ENGLISH));
        }
    }

    /**
     * 移除允许的 trailers 头部
     * @param header 头部名称
     */
    public void removeAllowedTrailerHeader(String header) {
        if (header != null) {
            allowedTrailerHeaders.remove(header.trim().toLowerCase(Locale.ENGLISH));
        }
    }

    // -------------------------- 升级协议管理 --------------------------

    // 配置的升级协议列表
    private final List<UpgradeProtocol> upgradeProtocols = new ArrayList<>();

    /**
     * 添加升级协议
     * @param upgradeProtocol 升级协议实例
     */
    @Override
    public void addUpgradeProtocol(UpgradeProtocol upgradeProtocol) {
        upgradeProtocols.add(upgradeProtocol);
    }

    /**
     * 获取所有升级协议
     * @return 升级协议数组
     */
    @Override
    public UpgradeProtocol[] findUpgradeProtocols() {
        return upgradeProtocols.toArray(new UpgradeProtocol[0]);
    }

    // HTTP升级协议映射（协议名称到升级协议实例）
    private final Map<String,UpgradeProtocol> httpUpgradeProtocols = new HashMap<>();
    // ALPN协商协议映射（协议名称到升级协议实例）
    private final Map<String,UpgradeProtocol> negotiatedProtocols = new HashMap<>();

    /**
     * 配置升级协议
     * @param upgradeProtocol 升级协议实例
     */
    private void configureUpgradeProtocol(UpgradeProtocol upgradeProtocol) {
        // 配置HTTP Upgrade协议
        String httpUpgradeName = upgradeProtocol.getHttpUpgradeName(getEndpoint().isSSLEnabled());
        boolean httpUpgradeConfigured = false;
        if (httpUpgradeName != null && !httpUpgradeName.isEmpty()) {
            httpUpgradeProtocols.put(httpUpgradeName, upgradeProtocol);
            httpUpgradeConfigured = true;
            getLog().info(sm.getString("abstractHttp11Protocol.httpUpgradeConfigured", getName(), httpUpgradeName));
        }

        // 配置ALPN协议（仅在SSL启用时）
        String alpnName = upgradeProtocol.getAlpnName();
        if (alpnName != null && !alpnName.isEmpty()) {
            if (getEndpoint().isSSLEnabled()) {
                negotiatedProtocols.put(alpnName, upgradeProtocol);
                getEndpoint().addNegotiatedProtocol(alpnName);
                getLog().info(sm.getString("abstractHttp11Protocol.alpnConfigured", getName(), alpnName));
            } else if (!httpUpgradeConfigured) {
                getLog().error(sm.getString("abstractHttp11Protocol.alpnWithNoAlpn",
                    upgradeProtocol.getClass().getName(), alpnName, getName()));
            }
        }
    }

    /**
     * 获取协商的协议对应的升级协议
     * @param negotiatedName 协商的协议名称
     * @return 升级协议实例，若不存在则返回null
     */
    @Override
    public UpgradeProtocol getNegotiatedProtocol(String negotiatedName) {
        return negotiatedProtocols.get(negotiatedName);
    }

    /**
     * 获取升级的协议对应的升级协议
     * @param upgradedName 升级的协议名称
     * @return 升级协议实例，若不存在则返回null
     */
    @Override
    public UpgradeProtocol getUpgradeProtocol(String upgradedName) {
        return httpUpgradeProtocols.get(upgradedName);
    }

    // 升级协议组信息映射（协议名称到升级协议组信息）
    private final Map<String,UpgradeGroupInfo> upgradeProtocolGroupInfos = new ConcurrentHashMap<>();

    /**
     * 获取升级协议组信息
     * @param upgradeProtocol 升级协议名称
     * @return 升级协议组信息实例
     */
    public UpgradeGroupInfo getUpgradeGroupInfo(String upgradeProtocol) {
        if (upgradeProtocol == null) {
            return null;
        }
        UpgradeGroupInfo result = upgradeProtocolGroupInfos.get(upgradeProtocol);
        if (result == null) {
            synchronized (upgradeProtocolGroupInfos) {
                result = upgradeProtocolGroupInfos.get(upgradeProtocol);
                if (result == null) {
                    result = new UpgradeGroupInfo();
                    upgradeProtocolGroupInfos.put(upgradeProtocol, result);
                    ObjectName oname = getONameForUpgrade(upgradeProtocol);
                    if (oname != null) {
                        try {
                            Registry.getRegistry(null).registerComponent(result, oname, null);
                        } catch (Exception e) {
                            getLog().warn(sm.getString("abstractHttp11Protocol.upgradeJmxRegistrationFail"), e);
                            result = null;
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 获取升级协议的MBean名称
     * @param upgradeProtocol 升级协议名称
     * @return MBean名称，若创建失败则返回null
     */
    public ObjectName getONameForUpgrade(String upgradeProtocol) {
        ObjectName oname = null;
        ObjectName parentRgOname = getGlobalRequestProcessorMBeanName();
        if (parentRgOname != null) {
            StringBuilder name = new StringBuilder(parentRgOname.getCanonicalName());
            name.append(",Upgrade=");
            if (Util.objectNameValueNeedsQuote(upgradeProtocol)) {
                name.append(ObjectName.quote(upgradeProtocol));
            } else {
                name.append(upgradeProtocol);
            }
            try {
                oname = new ObjectName(name.toString());
            } catch (Exception e) {
                getLog().warn(sm.getString("abstractHttp11Protocol.upgradeJmxNameFail"), e);
            }
        }
        return oname;
    }

    // -------------------------- 传递给端点的配置 --------------------------

    // SSL启用状态配置
    public boolean isSSLEnabled() {
        return getEndpoint().isSSLEnabled();
    }

    public void setSSLEnabled(boolean SSLEnabled) {
        getEndpoint().setSSLEnabled(SSLEnabled);
    }

    // 是否使用sendfile配置
    public boolean getUseSendfile() {
        return getEndpoint().getUseSendfile();
    }

    public void setUseSendfile(boolean useSendfile) {
        getEndpoint().setUseSendfile(useSendfile);
    }

    // 最大Keep-Alive请求数配置
    public int getMaxKeepAliveRequests() {
        return getEndpoint().getMaxKeepAliveRequests();
    }

    public void setMaxKeepAliveRequests(int mkar) {
        getEndpoint().setMaxKeepAliveRequests(mkar);
    }

    // -------------------------- HTTPS相关配置 --------------------------

    // 默认SSL主机配置名称
    public String getDefaultSSLHostConfigName() {
        return getEndpoint().getDefaultSSLHostConfigName();
    }

    public void setDefaultSSLHostConfigName(String defaultSSLHostConfigName) {
        getEndpoint().setDefaultSSLHostConfigName(defaultSSLHostConfigName);
    }

    // 添加SSL主机配置
    @Override
    public void addSslHostConfig(SSLHostConfig sslHostConfig) {
        getEndpoint().addSslHostConfig(sslHostConfig);
    }

    @Override
    public void addSslHostConfig(SSLHostConfig sslHostConfig, boolean replace) {
        getEndpoint().addSslHostConfig(sslHostConfig, replace);
    }

    @Override
    public SSLHostConfig[] findSslHostConfigs() {
        return getEndpoint().findSslHostConfigs();
    }

    public void reloadSslHostConfigs() {
        getEndpoint().reloadSslHostConfigs();
    }

    public void reloadSslHostConfig(String hostName) {
        getEndpoint().reloadSslHostConfig(hostName);
    }

    // -------------------------- 处理器创建 --------------------------

    /**
     * 创建HTTP/1.1处理器
     * @return HTTP/1.1处理器实例
     */
    @Override
    protected Processor createProcessor() {
        return new Http11Processor(this, adapter);
    }

    /**
     * 创建升级处理器
     * @param socket 套接字包装器
     * @param upgradeToken 升级令牌
     * @return 升级处理器实例
     */
    @Override
    protected Processor createUpgradeProcessor(SocketWrapperBase<?> socket, UpgradeToken upgradeToken) {
        HttpUpgradeHandler httpUpgradeHandler = upgradeToken.getHttpUpgradeHandler();
        if (httpUpgradeHandler instanceof InternalHttpUpgradeHandler) {
            // 内部升级处理器
            return new UpgradeProcessorInternal(socket, upgradeToken, getUpgradeGroupInfo(upgradeToken.getProtocol()));
        } else {
            // 外部升级处理器
            return new UpgradeProcessorExternal(socket, upgradeToken, getUpgradeGroupInfo(upgradeToken.getProtocol()));
        }
    }
}