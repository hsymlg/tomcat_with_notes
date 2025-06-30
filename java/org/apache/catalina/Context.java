/*
 * 版权所有 (C) Apache Software Foundation. 保留所有权利
 * 有关版权所有权的额外信息，请参阅随附的NOTICE文件
 * Apache 软件基金会根据 Apache 许可证 2.0 版（"许可证"）授权本文件
 * 除非符合许可证，否则不得使用本文件
 * 您可以在 http://www.apache.org/licenses/LICENSE-2.0 获得许可证副本
 */
package org.apache.catalina;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.*;
import jakarta.servlet.descriptor.JspConfigDescriptor;

import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.tomcat.ContextBind;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.JarScanner;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.apache.tomcat.util.descriptor.web.ApplicationParameter;
import org.apache.tomcat.util.descriptor.web.ErrorPage;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.apache.tomcat.util.file.ConfigFileLoader;
import org.apache.tomcat.util.file.ConfigurationSource.Resource;
import org.apache.tomcat.util.http.CookieProcessor;

/**
 * Context 接口表示 Catalina Servlet 引擎中的 Servlet 上下文（即单个 Web 应用程序）
 * 它在几乎所有 Catalina 部署中都很有用（即使连接到 Apache 等 Web 服务器的 Connector
 * 使用 Web 服务器的功能来识别处理请求的适当 Wrapper）
 * 它还提供了一种方便的机制来使用拦截器处理此特定 Web 应用程序的每个请求
 * <p>
 * 附加到 Context 的父容器通常是 Host，但也可以是其他实现，或者在不需要时可以省略
 * <p>
 * 附加到 Context 的子容器通常是 Wrapper 实现（表示单个 Servlet 定义）
 *
 * @author Craig R. McClanahan
 */
public interface Context extends Container, ContextBind {


    // ----------------------------------------------------- 常量定义

    /** 添加欢迎文件的容器事件 */
    String ADD_WELCOME_FILE_EVENT = "addWelcomeFile";

    /** 移除欢迎文件的容器事件 */
    String REMOVE_WELCOME_FILE_EVENT = "removeWelcomeFile";

    /** 清除欢迎文件的容器事件 */
    String CLEAR_WELCOME_FILES_EVENT = "clearWelcomeFiles";

    /** 更改会话ID的容器事件 */
    String CHANGE_SESSION_ID_EVENT = "changeSessionId";


    /** 资源查找的前缀 */
    String WEBAPP_PROTOCOL = "webapp:";


    // ------------------------------------------------------------- 属性访问方法

    /**
     * 获取是否允许为没有"multipart config"的Servlet解析multipart/form-data请求
     *
     * @return true 如果允许，false 否则
     */
    boolean getAllowCasualMultipartParsing();


    /**
     * 设置是否允许为没有"multipart config"的Servlet解析multipart/form-data请求
     *
     * @param allowCasualMultipartParsing 允许为true，否则为false
     */
    void setAllowCasualMultipartParsing(boolean allowCasualMultipartParsing);


    /**
     * 获取注册的应用程序事件监听器
     *
     * @return 按Web应用部署描述符中指定顺序排列的应用程序事件监听器实例数组
     */
    Object[] getApplicationEventListeners();


    /**
     * 设置应用程序事件监听器实例数组
     * 按Web应用部署描述符中指定的顺序存储
     *
     * @param listeners 实例化的监听器对象集合
     */
    void setApplicationEventListeners(Object[] listeners);


    /**
     * 获取注册的应用程序生命周期监听器
     *
     * @return 按Web应用部署描述符中指定顺序排列的应用程序生命周期监听器实例数组
     */
    Object[] getApplicationLifecycleListeners();


    /**
     * 设置应用程序生命周期监听器实例数组
     * 按Web应用部署描述符中指定的顺序存储
     *
     * @param listeners 实例化的监听器对象集合
     */
    void setApplicationLifecycleListeners(Object[] listeners);


    /**
     * 获取与给定Locale关联的字符集名称
     * 不同的Context可能有不同的Locale到字符集的映射
     *
     * @param locale 要获取字符集的Locale
     * @return 与给定Locale关联的字符集名称
     */
    String getCharset(Locale locale);


    /**
     * 返回此Context的XML描述符的URL
     *
     * @return Context的XML描述符的URL
     */
    URL getConfigFile();


    /**
     * 设置此Context的XML描述符的URL
     *
     * @param configFile Context的XML描述符的URL
     */
    void setConfigFile(URL configFile);


    /**
     * 返回此Context的"正确配置"标志
     *
     * @return 如果Context已正确配置为true，否则为false
     */
    boolean getConfigured();


    /**
     * 设置此Context的"正确配置"标志
     * 启动监听器检测到致命配置错误时可设置为false，避免应用可用
     *
     * @param configured 新的正确配置标志
     */
    void setConfigured(boolean configured);


    /**
     * 返回"使用Cookie存储会话ID"标志
     *
     * @return 如果允许使用Cookie跟踪此Web应用的会话ID为true，否则为false
     */
    boolean getCookies();


    /**
     * 设置"使用Cookie存储会话ID"标志
     *
     * @param cookies 新标志
     */
    void setCookies(boolean cookies);


    /**
     * 获取会话Cookie使用的名称，覆盖应用程序指定的任何设置
     *
     * @return 默认会话Cookie名称，未指定时返回null
     */
    String getSessionCookieName();


    /**
     * 设置会话Cookie使用的名称，覆盖应用程序指定的任何设置
     *
     * @param sessionCookieName 要使用的名称
     */
    void setSessionCookieName(String sessionCookieName);


    /**
     * 获取会话Cookie使用HttpOnly标志的标志值
     *
     * @return 如果会话Cookie应设置HttpOnly标志为true
     */
    boolean getUseHttpOnly();


    /**
     * 设置会话Cookie使用HttpOnly标志的标志
     *
     * @param useHttpOnly 设置为true以对会话Cookie使用HttpOnly标志
     */
    void setUseHttpOnly(boolean useHttpOnly);


    /**
     * 是否应将Partitioned属性添加到此Web应用创建的会话Cookie中
     *
     * @return true 如果应添加Partitioned属性，否则false
     */
    boolean getUsePartitioned();


    /**
     * 配置是否应将Partitioned属性添加到此Web应用创建的会话Cookie中
     *
     * @param usePartitioned true 表示应添加Partitioned属性
     */
    void setUsePartitioned(boolean usePartitioned);


    /**
     * 获取会话Cookie使用的域，覆盖应用程序指定的任何设置
     *
     * @return 默认会话Cookie域，未指定时返回null
     */
    String getSessionCookieDomain();


    /**
     * 设置会话Cookie使用的域，覆盖应用程序指定的任何设置
     *
     * @param sessionCookieDomain 要使用的域
     */
    void setSessionCookieDomain(String sessionCookieDomain);


    /**
     * 获取会话Cookie使用的路径，覆盖应用程序指定的任何设置
     *
     * @return 默认会话Cookie路径，未指定时返回null
     */
    String getSessionCookiePath();


    /**
     * 设置会话Cookie使用的路径，覆盖应用程序指定的任何设置
     *
     * @param sessionCookiePath 要使用的路径
     */
    void setSessionCookiePath(String sessionCookiePath);


    /**
     * 会话Cookie路径末尾是否添加/，确保浏览器（尤其是IE）
     * 不会将/foo上下文的会话Cookie发送到/foobar上下文的请求
     *
     * @return true 如果添加斜杠，否则false
     */
    boolean getSessionCookiePathUsesTrailingSlash();


    /**
     * 配置会话Cookie路径末尾是否添加/，确保浏览器（尤其是IE）
     * 不会将/foo上下文的会话Cookie发送到/foobar上下文的请求
     *
     * @param sessionCookiePathUsesTrailingSlash true 表示应添加斜杠
     */
    void setSessionCookiePathUsesTrailingSlash(boolean sessionCookiePathUsesTrailingSlash);


    /**
     * 返回"允许跨Servlet上下文"标志
     *
     * @return 如果此Web应用允许跨上下文请求为true，否则false
     */
    boolean getCrossContext();


    /**
     * 返回备用部署描述符名称
     *
     * @return 名称
     */
    String getAltDDName();


    /**
     * 设置备用部署描述符名称
     *
     * @param altDDName 新名称
     */
    void setAltDDName(String altDDName);


    /**
     * 设置"允许跨Servlet上下文"标志
     *
     * @param crossContext 新的跨上下文标志
     */
    void setCrossContext(boolean crossContext);


    /**
     * 返回此Web应用的deny-uncovered-http-methods标志
     *
     * @return 标志的当前值
     */
    boolean getDenyUncoveredHttpMethods();


    /**
     * 设置此Web应用的deny-uncovered-http-methods标志
     *
     * @param denyUncoveredHttpMethods 新的deny-uncovered-http-methods标志
     */
    void setDenyUncoveredHttpMethods(boolean denyUncoveredHttpMethods);


    /**
     * 返回此Web应用的显示名称
     *
     * @return 显示名称
     */
    String getDisplayName();


    /**
     * 设置此Web应用的显示名称
     *
     * @param displayName 新的显示名称
     */
    void setDisplayName(String displayName);


    /**
     * 获取此Web应用的distributable标志
     *
     * @return 此Web应用的distributable标志值
     */
    boolean getDistributable();


    /**
     * 设置此Web应用的distributable标志
     *
     * @param distributable 新的distributable标志
     */
    void setDistributable(boolean distributable);


    /**
     * 获取此Context的文档根目录
     *
     * @return 绝对路径名或相对于Host的appBase的路径名
     */
    String getDocBase();


    /**
     * 设置此Context的文档根目录
     * 可以是绝对路径名或相对路径名，相对路径相对于包含的Host的appBase
     *
     * @param docBase 新的文档根目录
     */
    void setDocBase(String docBase);


    /**
     * 返回URL编码的上下文路径
     *
     * @return 使用UTF-8编码的URL编码上下文路径
     */
    String getEncodedPath();


    /**
     * 确定当前是否禁用注释解析
     *
     * @return true 如果为此Web应用禁用了注释解析
     */
    boolean getIgnoreAnnotations();


    /**
     * 设置此Web应用的注释解析布尔值
     *
     * @param ignoreAnnotations 注释解析的布尔值
     */
    void setIgnoreAnnotations(boolean ignoreAnnotations);


    /**
     * 返回此Web应用的登录配置描述符
     *
     * @return 登录配置描述符
     */
    LoginConfig getLoginConfig();


    /**
     * 设置此Web应用的登录配置描述符
     *
     * @param config 新的登录配置
     */
    void setLoginConfig(LoginConfig config);


    /**
     * 返回与此Web应用关联的命名资源
     *
     * @return 命名资源
     */
    NamingResourcesImpl getNamingResources();


    /**
     * 设置此Web应用的命名资源
     *
     * @param namingResources 新的命名资源
     */
    void setNamingResources(NamingResourcesImpl namingResources);


    /**
     * 返回此Web应用的上下文路径
     *
     * @return 上下文路径
     */
    String getPath();


    /**
     * 设置此Web应用的上下文路径
     *
     * @param path 新的上下文路径
     */
    void setPath(String path);


    /**
     * 返回当前正在解析的部署描述符DTD的公共标识符
     *
     * @return 公共标识符
     */
    String getPublicId();


    /**
     * 设置当前正在解析的部署描述符DTD的公共标识符
     *
     * @param publicId 公共标识符
     */
    void setPublicId(String publicId);


    /**
     * 返回此Web应用的reloadable标志
     *
     * @return reloadable标志值
     */
    boolean getReloadable();


    /**
     * 设置此Web应用的reloadable标志
     *
     * @param reloadable 新的reloadable标志
     */
    void setReloadable(boolean reloadable);


    /**
     * 返回此Web应用的override标志
     *
     * @return override标志值
     */
    boolean getOverride();


    /**
     * 设置此Web应用的override标志
     *
     * @param override 新的override标志
     */
    void setOverride(boolean override);


    /**
     * 返回此Web应用的privileged标志
     *
     * @return privileged标志值
     */
    boolean getPrivileged();


    /**
     * 设置此Web应用的privileged标志
     *
     * @param privileged 新的privileged标志
     */
    void setPrivileged(boolean privileged);


    /**
     * 返回此Context作为外观的Servlet上下文
     *
     * @return Servlet上下文
     */
    ServletContext getServletContext();


    /**
     * 返回此Web应用的默认会话超时时间（分钟）
     *
     * @return 默认会话超时时间
     */
    int getSessionTimeout();


    /**
     * 设置此Web应用的默认会话超时时间（分钟）
     *
     * @param timeout 新的默认会话超时时间
     */
    void setSessionTimeout(int timeout);


    /**
     * 返回true如果即使请求违反数据大小约束，仍将读取（吞没）剩余请求数据
     *
     * @return true表示会吞没数据（默认），false否则
     */
    boolean getSwallowAbortedUploads();


    /**
     * 设置为false以禁用因大小约束而中止上传后请求数据的吞没
     *
     * @param swallowAbortedUploads false表示禁用吞没，true否则（默认）
     */
    void setSwallowAbortedUploads(boolean swallowAbortedUploads);


    /**
     * 返回swallowOutput标志的值
     *
     * @return swallowOutput标志值
     */
    boolean getSwallowOutput();


    /**
     * 设置swallowOutput标志的值
     * 如果设置为true，Servlet执行期间的system.out和system.err将重定向到日志记录器
     *
     * @param swallowOutput 新值
     */
    void setSwallowOutput(boolean swallowOutput);


    /**
     * 返回此Context中注册的Servlet使用的Wrapper实现的Java类名
     *
     * @return Wrapper实现类名
     */
    String getWrapperClass();


    /**
     * 设置此Context中注册的Servlet使用的Wrapper实现的Java类名
     *
     * @param wrapperClass 新的Wrapper类
     */
    void setWrapperClass(String wrapperClass);


    /**
     * 此Context的web.xml和web-fragment.xml文件的解析是否使用支持命名空间的解析器？
     *
     * @return true如果启用了命名空间感知
     */
    boolean getXmlNamespaceAware();


    /**
     * 控制此Context的web.xml和web-fragment.xml文件的解析是否使用支持命名空间的解析器
     *
     * @param xmlNamespaceAware true以启用命名空间感知
     */
    void setXmlNamespaceAware(boolean xmlNamespaceAware);


    /**
     * 此Context的web.xml和web-fragment.xml文件的解析是否使用验证解析器？
     *
     * @return true如果启用了验证
     */
    boolean getXmlValidation();


    /**
     * 控制此Context的web.xml和web-fragment.xml文件的解析是否使用验证解析器
     *
     * @param xmlValidation true以启用XML验证
     */
    void setXmlValidation(boolean xmlValidation);


    /**
     * 此Context的web.xml、web-fragment.xml、*.tld、*.jspx、*.tagx和tagplugin.xml文件的解析
     * 是否会阻止使用外部实体？
     *
     * @return true如果阻止外部实体的访问
     */
    boolean getXmlBlockExternal();


    /**
     * 控制此Context的web.xml、web-fragment.xml、*.tld、*.jspx、*.tagx和tagplugin.xml文件的解析
     * 是否会阻止使用外部实体
     *
     * @param xmlBlockExternal true以阻止外部实体
     */
    void setXmlBlockExternal(boolean xmlBlockExternal);


    /**
     * 此Context的*.tld文件的解析是否使用验证解析器？
     *
     * @return true如果启用了验证
     */
    boolean getTldValidation();


    /**
     * 控制此Context的*.tld文件的解析是否使用验证解析器
     *
     * @param tldValidation true以启用XML验证
     */
    void setTldValidation(boolean tldValidation);


    /**
     * 获取用于扫描此上下文JAR资源的Jar Scanner
     *
     * @return 为此上下文配置的Jar Scanner
     */
    JarScanner getJarScanner();


    /**
     * 设置用于扫描此上下文JAR资源的Jar Scanner
     *
     * @param jarScanner 用于此上下文的Jar Scanner
     */
    void setJarScanner(JarScanner jarScanner);


    /**
     * 返回此上下文使用的Authenticator
     * 对于已启动的Context，此值始终非null
     *
     * @return Authenticator实例
     */
    Authenticator getAuthenticator();


    /**
     * 设置是否应在上下文启动时记录此上下文的有效web.xml
     *
     * @param logEffectiveWebXml 设置为true以记录将用于Web应用的完整web.xml
     */
    void setLogEffectiveWebXml(boolean logEffectiveWebXml);


    /**
     * 上下文启动时是否应记录此上下文的有效web.xml？
     *
     * @return true如果应记录将用于Web应用的重建web.xml
     */
    boolean getLogEffectiveWebXml();


    /**
     * 返回与此上下文关联的实例管理器
     *
     * @return 实例管理器
     */
    InstanceManager getInstanceManager();


    /**
     * 设置与此上下文关联的实例管理器
     *
     * @param instanceManager 新的实例管理器实例
     */
    void setInstanceManager(InstanceManager instanceManager);


    /**
     * 设置正则表达式，指定应过滤掉且不用于此上下文的容器提供的SCI
     * 匹配使用Matcher.find()，因此正则表达式只需匹配容器提供的SCI的完全限定类名的子字符串
     *
     * @param containerSciFilter 应针对每个容器提供的SCI的完全限定类名检查的正则表达式
     */
    void setContainerSciFilter(String containerSciFilter);


    /**
     * 获取正则表达式，指定应过滤掉且不用于此上下文的容器提供的SCI
     * 匹配使用Matcher.find()，因此正则表达式只需匹配容器提供的SCI的完全限定类名的子字符串
     *
     * @return 应针对每个容器提供的SCI的完全限定类名检查的正则表达式
     */
    String getContainerSciFilter();


    /**
     * 返回并行注释扫描标志的值
     * 如果为true，将调度扫描到实用程序执行器
     *
     * @deprecated 此方法将在Tomcat 11及以后版本中删除
     */
    @Deprecated
    default boolean isParallelAnnotationScanning() {
        return getParallelAnnotationScanning();
    }


    /**
     * 返回并行注释扫描标志的值
     * 如果为true，将调度扫描到实用程序执行器
     *
     * @return 并行注释扫描标志值
     */
    boolean getParallelAnnotationScanning();


    /**
     * 设置并行注释扫描值
     *
     * @param parallelAnnotationScanning 新的并行注释扫描标志
     */
    void setParallelAnnotationScanning(boolean parallelAnnotationScanning);


    // --------------------------------------------------------- 公共方法

    /**
     * 向为此应用程序配置的监听器集合添加新的监听器类名
     *
     * @param listener 监听器类的Java类名
     */
    void addApplicationListener(String listener);


    /**
     * 为此应用程序添加新的应用程序参数
     *
     * @param parameter 新的应用程序参数
     */
    void addApplicationParameter(ApplicationParameter parameter);


    /**
     * 向此Web应用的集合添加安全约束
     *
     * @param constraint 应添加的安全约束
     */
    void addConstraint(SecurityConstraint constraint);


    /**
     * 为指定的错误或Java异常添加错误页面
     *
     * @param errorPage 要添加的错误页面定义
     */
    void addErrorPage(ErrorPage errorPage);


    /**
     * 向此Context添加过滤器定义
     *
     * @param filterDef 要添加的过滤器定义
     */
    void addFilterDef(FilterDef filterDef);


    /**
     * 向此Context添加过滤器映射
     *
     * @param filterMap 要添加的过滤器映射
     */
    void addFilterMap(FilterMap filterMap);


    /**
     * 在部署描述符中定义的映射之前但在通过此方法添加的任何其他映射之后
     * 向此Context添加过滤器映射
     *
     * @param filterMap 要添加的过滤器映射
     * @exception IllegalArgumentException 如果指定的过滤器名称与现有过滤器定义不匹配
     * 或过滤器映射格式错误
     */
    void addFilterMapBefore(FilterMap filterMap);


    /**
     * 添加Locale编码映射（请参阅Servlet规范2.4的第5.4节）
     *
     * @param locale 要映射编码的Locale
     * @param encoding 用于给定Locale的编码
     */
    void addLocaleEncodingMappingParameter(String locale, String encoding);


    /**
     * 添加新的MIME映射，替换指定扩展名的任何现有映射
     *
     * @param extension 要映射的文件扩展名
     * @param mimeType 对应的MIME类型
     */
    void addMimeMapping(String extension, String mimeType);


    /**
     * 添加新的上下文初始化参数，替换指定名称的任何现有值
     *
     * @param name 新参数的名称
     * @param value 新参数的值
     */
    void addParameter(String name, String value);


    /**
     * 为此Web应用添加安全角色引用
     *
     * @param role 应用程序中使用的安全角色
     * @param link 要检查的实际安全角色
     */
    void addRoleMapping(String role, String link);


    /**
     * 为此Web应用添加新的安全角色
     *
     * @param role 新的安全角色
     */
    void addSecurityRole(String role);


    /**
     * 添加新的Servlet映射，替换指定模式的任何现有映射
     *
     * @param pattern 要映射的URL模式
     * @param name 要执行的相应Servlet的名称
     */
    default void addServletMappingDecoded(String pattern, String name) {
        addServletMappingDecoded(pattern, name, false);
    }


    /**
     * 添加新的Servlet映射，替换指定模式的任何现有映射
     *
     * @param pattern 要映射的URL模式
     * @param name 要执行的相应Servlet的名称
     * @param jspWildcard true如果名称标识JspServlet且模式包含通配符，否则false
     */
    void addServletMappingDecoded(String pattern, String name, boolean jspWildcard);


    /**
     * 添加将由主机自动部署程序监视重新加载的资源
     * 注意：在嵌入式模式下不使用
     *
     * @param name 资源路径，相对于docBase
     */
    void addWatchedResource(String name);


    /**
     * 向此Context识别的集合添加新的欢迎文件
     *
     * @param name 新的欢迎文件名
     */
    void addWelcomeFile(String name);


    /**
     * 添加将添加到附加到此Context的每个Wrapper的LifecycleListener的类名
     *
     * @param listener LifecycleListener类的Java类名
     */
    void addWrapperLifecycle(String listener);


    /**
     * 添加将添加到附加到此Context的每个Wrapper的ContainerListener的类名
     *
     * @param listener ContainerListener类的Java类名
     */
    void addWrapperListener(String listener);


    /**
     * 工厂方法，创建并返回新的InstanceManager实例
     * 可用于框架集成或使用自定义Context实现更轻松地配置
     *
     * @return 实例管理器
     */
    InstanceManager createInstanceManager();


    /**
     * 工厂方法，创建并返回新的Wrapper实例
     * 适用于此Context实现的Java实现类
     * 已调用实例化Wrapper的构造函数，但未设置任何属性
     *
     * @return 新创建的Wrapper实例，用于包装Servlet
     */
    Wrapper createWrapper();


    /**
     * 返回为此应用程序配置的应用程序监听器类名数组
     *
     * @return 应用程序监听器类名数组
     */
    String[] findApplicationListeners();


    /**
     * 返回此应用程序的应用程序参数数组
     *
     * @return 应用程序参数数组
     */
    ApplicationParameter[] findApplicationParameters();


    /**
     * 返回此Web应用的安全约束数组
     * 如果没有，返回零长度数组
     *
     * @return 安全约束数组
     */
    SecurityConstraint[] findConstraints();


    /**
     * 返回指定HTTP错误代码的错误页面条目（如果有），否则返回null
     *
     * @param errorCode 要查找的错误代码
     * @return 错误页面条目
     */
    ErrorPage findErrorPage(int errorCode);


    /**
     * 查找并返回指定异常类的ErrorPage实例
     * 或找到的最接近超类的ErrorPage实例
     * 如果未找到关联的ErrorPage实例，返回null
     *
     * @param throwable 要查找ErrorPage的异常类型
     * @return 指定Java异常类型的错误页面条目（如果有），否则返回null
     */
    ErrorPage findErrorPage(Throwable throwable);


    /**
     * 返回所有指定错误代码和异常类型的已定义错误页面数组
     *
     * @return 错误页面数组
     */
    ErrorPage[] findErrorPages();


    /**
     * 返回指定过滤器名称的过滤器定义（如果有），否则返回null
     *
     * @param filterName 要查找的过滤器名称
     * @return 过滤器定义
     */
    FilterDef findFilterDef(String filterName);


    /**
     * 返回此Context的已定义过滤器数组
     *
     * @return 过滤器定义数组
     */
    FilterDef[] findFilterDefs();


    /**
     * 返回此Context的过滤器映射数组
     *
     * @return 过滤器映射数组
     */
    FilterMap[] findFilterMaps();


    /**
     * 返回指定扩展名映射到的MIME类型（如果有），否则返回null
     *
     * @param extension 要映射到MIME类型的扩展名
     * @return MIME类型
     */
    String findMimeMapping(String extension);


    /**
     * 返回定义了MIME映射的扩展名
     * 如果没有，返回零长度数组
     *
     * @return 扩展名数组
     */
    String[] findMimeMappings();


    /**
     * 返回指定上下文初始化参数名称的值（如果有），否则返回null
     *
     * @param name 要返回的参数名称
     * @return 参数值
     */
    String findParameter(String name);


    /**
     * 返回此Context的所有已定义上下文初始化参数的名称
     * 如果未定义参数，返回零长度数组
     *
     * @return 参数名称数组
     */
    String[] findParameters();


    /**
     * 对于给定的安全角色（如应用程序所使用），返回对应的角色名称
     * （如底层Realm所定义），如果有的话
     * 否则，返回指定的角色（不变）
     *
     * @param role 要映射的安全角色
     * @return 映射到指定角色的角色名称
     */
    String findRoleMapping(String role);


    /**
     * 如果为此应用程序定义了指定的安全角色，返回true，否则返回false
     *
     * @param role 要验证的安全角色
     * @return 验证结果
     */
    boolean findSecurityRole(String role);


    /**
     * 返回为此应用程序定义的安全角色
     * 如果未定义任何角色，返回零长度数组
     *
     * @return 安全角色数组
     */
    String[] findSecurityRoles();


    /**
     * 返回由指定模式映射的Servlet名称（如果有），否则返回null
     *
     * @param pattern 请求映射的模式
     * @return Servlet名称
     */
    String findServletMapping(String pattern);


    /**
     * 返回此Context的所有已定义Servlet映射的模式
     * 如果未定义映射，返回零长度数组
     *
     * @return Servlet映射模式数组
     */
    String[] findServletMappings();


    /**
     * 返回关联的ThreadBindingListener
     *
     * @return ThreadBindingListener实例
     */
    ThreadBindingListener getThreadBindingListener();


    /**
     * 设置关联的ThreadBindingListener
     *
     * @param threadBindingListener 当进入和退出应用程序作用域时将接收通知的监听器
     */
    void setThreadBindingListener(ThreadBindingListener threadBindingListener);


    /**
     * 返回此Context的观察资源数组
     * 如果未定义，返回零长度数组
     *
     * @return 观察资源数组
     */
    String[] findWatchedResources();


    /**
     * 如果为此Context定义了指定的欢迎文件，返回true，否则返回false
     *
     * @param name 要验证的欢迎文件
     * @return 验证结果
     */
    boolean findWelcomeFile(String name);


    /**
     * 返回为此Context定义的欢迎文件数组
     * 如果未定义，返回零长度数组
     *
     * @return 欢迎文件数组
     */
    String[] findWelcomeFiles();


    /**
     * 返回将自动添加到新创建的Wrapper的LifecycleListener类数组
     *
     * @return LifecycleListener类数组
     */
    String[] findWrapperLifecycles();


    /**
     * 返回将自动添加到新创建的Wrapper的ContainerListener类数组
     *
     * @return ContainerListener类数组
     */
    String[] findWrapperListeners();


    /**
     * 通知所有ServletRequestListener请求已启动
     *
     * @param request 将传递给监听器的请求对象
     * @return true如果监听器触发成功，否则false
     */
    boolean fireRequestInitEvent(ServletRequest request);


    /**
     * 通知所有ServletRequestListener请求已结束
     *
     * @param request 将传递给监听器的请求对象
     * @return true如果监听器触发成功，否则false
     */
    boolean fireRequestDestroyEvent(ServletRequest request);


    /**
     * 重新加载此Web应用（如果支持重新加载）
     *
     * @exception IllegalStateException 如果reloadable属性设置为false
     */
    void reload();


    /**
     * 从应用程序的监听器集合中删除指定的应用程序监听器类
     *
     * @param listener 要删除的监听器的Java类名
     */
    void removeApplicationListener(String listener);


    /**
     * 从此应用程序的集合中删除具有指定名称的应用程序参数
     *
     * @param name 要删除的应用程序参数的名称
     */
    void removeApplicationParameter(String name);


    /**
     * 从此Web应用中删除指定的安全约束
     *
     * @param constraint 要删除的约束
     */
    void removeConstraint(SecurityConstraint constraint);


    /**
     * 删除指定错误代码或Java语言异常的错误页面（如果存在）
     * 否则，不执行任何操作
     *
     * @param errorPage 要删除的错误页面定义
     */
    void removeErrorPage(ErrorPage errorPage);


    /**
     * 从此Context中删除指定的过滤器定义（如果存在）
     * 否则，不执行任何操作
     *
     * @param filterDef 要删除的过滤器定义
     */
    void removeFilterDef(FilterDef filterDef);


    /**
     * 从此Context中删除过滤器映射
     *
     * @param filterMap 要删除的过滤器映射
     */
    void removeFilterMap(FilterMap filterMap);


    /**
     * 删除指定扩展名的MIME映射（如果存在）
     * 否则，不执行任何操作
     *
     * @param extension 要删除映射的扩展名
     */
    void removeMimeMapping(String extension);


    /**
     * 删除具有指定名称的上下文初始化参数（如果存在）
     * 否则，不执行任何操作
     *
     * @param name 要删除的参数的名称
     */
    void removeParameter(String name);


    /**
     * 删除指定名称的任何安全角色引用
     *
     * @param role 要删除的安全角色（如应用程序中所使用）
     */
    void removeRoleMapping(String role);


    /**
     * 删除具有指定名称的任何安全角色
     *
     * @param role 要删除的安全角色
     */
    void removeSecurityRole(String role);


    /**
     * 删除指定模式的任何Servlet映射（如果存在）
     * 否则，不执行任何操作
     *
     * @param pattern 要删除的映射的URL模式
     */
    void removeServletMapping(String pattern);


    /**
     * 从此Context关联的列表中删除指定的观察资源名称
     *
     * @param name 要删除的观察资源的名称
     */
    void removeWatchedResource(String name);


    /**
     * 从此Context识别的列表中删除指定的欢迎文件名
     *
     * @param name 要删除的欢迎文件的名称
     */
    void removeWelcomeFile(String name);


    /**
     * 从将添加到新创建的Wrapper的LifecycleListener类集合中删除类名
     *
     * @param listener 要删除的LifecycleListener类的类名
     */
    void removeWrapperLifecycle(String listener);


    /**
     * 从将添加到新创建的Wrapper的ContainerListener类集合中删除类名
     *
     * @param listener 要删除的ContainerListener类的类名
     */
    void removeWrapperListener(String listener);


    /**
     * 返回给定虚拟路径的真实路径（如果可能），否则返回null
     *
     * @param path 所需资源的路径
     * @return 真实路径
     */
    String getRealPath(String path);


    /**
     * 返回此Context使用的Servlet规范的有效主版本
     *
     * @return Servlet规范主版本号
     */
    int getEffectiveMajorVersion();


    /**
     * 设置此Context使用的Servlet规范的有效主版本
     *
     * @param major 版本号
     */
    void setEffectiveMajorVersion(int major);


    /**
     * 返回此Context使用的Servlet规范的有效次版本
     *
     * @return Servlet规范次版本号
     */
    int getEffectiveMinorVersion();


    /**
     * 设置此Context使用的Servlet规范的有效次版本
     *
     * @param minor 版本号
     */
    void setEffectiveMinorVersion(int minor);


    /**
     * 返回此Context的JSP配置
     * 如果没有JSP配置，将为null
     *
     * @return JSP配置描述符
     */
    JspConfigDescriptor getJspConfigDescriptor();


    /**
     * 设置此Context的JspConfigDescriptor
     * null值表示没有JSP配置
     *
     * @param descriptor 新的JSP配置
     */
    void setJspConfigDescriptor(JspConfigDescriptor descriptor);


    /**
     * 向此Web应用添加ServletContainerInitializer实例
     *
     * @param sci 要添加的实例
     * @param classes 初始化器感兴趣的类
     */
    void addServletContainerInitializer(ServletContainerInitializer sci, Set<Class<?>> classes);


    /**
     * 此Context在重新加载时是否已暂停？
     *
     * @return true如果上下文已暂停
     */
    boolean getPaused();


    /**
     * 此Context是否使用Servlet规范2.2版本？
     *
     * @return true表示传统的Servlet 2.2 Web应用
     */
    boolean isServlet22();


    /**
     * 通知Servlet安全已在Dynamic ServletRegistration中动态设置
     *
     * @param registration Servlet安全已修改
     * @param servletSecurityElement 此Servlet的新安全约束
     * @return 目前映射到此外部化的URL，这些URL已存在于web.xml中
     */
    Set<String> addServletSecurity(ServletRegistration.Dynamic registration,
                                   ServletSecurityElement servletSecurityElement);


    /**
     * 设置（逗号分隔）期望资源存在的Servlet列表
     * 用于确保与期望资源存在的Servlet关联的欢迎文件
     * 在没有资源时不映射
     *
     * @param resourceOnlyServlets Servlet名称的逗号分隔列表
     */
    void setResourceOnlyServlets(String resourceOnlyServlets);


    /**
     * 获取期望资源存在的Servlet列表
     *
     * @return web.xml中使用的Servlet名称的逗号分隔列表
     */
    String getResourceOnlyServlets();


    /**
     * 检查指定的Servlet是否期望资源存在
     *
     * @param servletName 要检查的Servlet名称（如web.xml中所示）
     * @return true如果Servlet期望资源，否则false
     */
    boolean isResourceOnlyServlet(String servletName);


    /**
     * 返回用于此Context的WAR、目录或context.xml文件的基本名称
     *
     * @return 基本名称
     */
    String getBaseName();


    /**
     * 设置此Web应用的版本
     * 用于在使用并行部署时区分同一Web应用的不同版本
     *
     * @param webappVersion 与此Context关联的Web应用版本，应唯一
     */
    void setWebappVersion(String webappVersion);


    /**
     * 返回此Web应用的版本
     * 用于在使用并行部署时区分同一Web应用的不同版本
     * 如果未指定，默认为空字符串
     *
     * @return Web应用版本
     */
    String getWebappVersion();


    /**
     * 配置是否为此Context的转发请求触发请求监听器
     *
     * @param enable true表示转发时触发请求监听器
     */
    void setFireRequestListenersOnForwards(boolean enable);


    /**
     * 返回是否为此Context的转发请求触发请求监听器
     *
     * @return 是否触发请求监听器
     */
    boolean getFireRequestListenersOnForwards();


    /**
     * 配置如果用户提供了身份验证凭据
     * 当请求是针对非受保护资源时，上下文是否将处理它们
     *
     * @param enable true表示即使在安全约束之外也执行身份验证
     */
    void setPreemptiveAuthentication(boolean enable);


    /**
     * 返回如果用户提供了身份验证凭据
     * 当请求是针对非受保护资源时，上下文是否将处理它们
     *
     * @return 处理验证的标志
     */
    boolean getPreemptiveAuthentication();


    /**
     * 配置将重定向响应发送到客户端时是否包含响应体
     *
     * @param enable true表示为重定向发送响应体
     */
    void setSendRedirectBody(boolean enable);


    /**
     * 返回上下文是否配置为将响应体作为重定向响应的一部分
     *
     * @return 重定向时是否发送响应体
     */
    boolean getSendRedirectBody();


    /**
     * 返回与此Context关联的Loader
     *
     * @return Loader实例
     */
    Loader getLoader();


    /**
     * 设置与此Context关联的Loader
     *
     * @param loader 新关联的Loader
     */
    void setLoader(Loader loader);


    /**
     * 返回与此Context关联的Resources
     *
     * @return WebResourceRoot实例
     */
    WebResourceRoot getResources();


    /**
     * 设置与此Context关联的Resources对象
     *
     * @param resources 新关联的Resources
     */
    void setResources(WebResourceRoot resources);


    /**
     * 返回与此Context关联的Manager
     * 如果没有关联的Manager，返回null
     *
     * @return Manager实例
     */
    Manager getManager();


    /**
     * 设置与此Context关联的Manager
     *
     * @param manager 新关联的Manager
     */
    void setManager(Manager manager);


    /**
     * 设置标志，指示是否应将/WEB-INF/classes视为展开的JAR
     * 并使JAR资源可用，就像它们在JAR中一样
     *
     * @param addWebinfClassesResources 标志的新值
     */
    void setAddWebinfClassesResources(boolean addWebinfClassesResources);


    /**
     * 返回标志，指示是否应将/WEB-INF/classes视为展开的JAR
     * 并使JAR资源可用，就像它们在JAR中一样
     *
     * @return 标志值
     */
    boolean getAddWebinfClassesResources();


    /**
     * 为给定类添加后置构造方法定义
     * 如果存在指定类的现有定义，将抛出IllegalArgumentException
     *
     * @param clazz 完全限定类名
     * @param method 后置构造方法名
     * @throws IllegalArgumentException 如果完全限定类名或方法名为null
     * 或给定类已存在后置构造方法定义
     */
    void addPostConstructMethod(String clazz, String method);


    /**
     * 为给定类添加前置销毁方法定义
     * 如果存在指定类的现有定义，将抛出IllegalArgumentException
     *
     * @param clazz 完全限定类名
     * @param method 前置销毁方法名
     * @throws IllegalArgumentException 如果完全限定类名或方法名为null
     * 或给定类已存在前置销毁方法定义
     */
    void addPreDestroyMethod(String clazz, String method);


    /**
     * 删除给定类的后置构造方法定义（如果存在）
     * 否则，不执行任何操作
     *
     * @param clazz 完全限定类名
     */
    void removePostConstructMethod(String clazz);


    /**
     * 删除给定类的前置销毁方法定义（如果存在）
     * 否则，不执行任何操作
     *
     * @param clazz 完全限定类名
     */
    void removePreDestroyMethod(String clazz);


    /**
     * 返回为给定类指定的后置构造方法的方法名（如果存在）
     * 否则返回null
     *
     * @param clazz 完全限定类名
     * @return 为给定类指定的后置构造方法的方法名，否则返回null
     */
    String findPostConstructMethod(String clazz);


    /**
     * 返回为给定类指定的前置销毁方法的方法名（如果存在）
     * 否则返回null
     *
     * @param clazz 完全限定类名
     * @return 为给定类指定的前置销毁方法的方法名，否则返回null
     */
    String findPreDestroyMethod(String clazz);


    /**
     * 返回一个映射，其中键是具有后置构造方法的类的完全限定类名
     * 值是相应的方法名
     * 如果没有这样的类，将返回空映射
     *
     * @return 后置构造方法映射
     */
    Map<String, String> findPostConstructMethods();


    /**
     * 返回一个映射，其中键是具有前置销毁方法的类的完全限定类名
     * 值是相应的方法名
     * 如果没有这样的类，将返回空映射
     *
     * @return 前置销毁方法映射
     */
    Map<String, String> findPreDestroyMethods();


    /**
     * 返回与此关联的JNDI命名上下文操作所需的令牌
     *
     * @return 命名令牌
     */
    Object getNamingToken();


    /**
     * 设置将用于此Context的Cookie处理的CookieProcessor
     *
     * @param cookieProcessor 新的Cookie处理器
     * @throws IllegalArgumentException 如果指定了null的CookieProcessor
     */
    void setCookieProcessor(CookieProcessor cookieProcessor);


    /**
     * 返回将用于此Context的Cookie处理的CookieProcessor
     *
     * @return CookieProcessor实例
     */
    CookieProcessor getCookieProcessor();


    /**
     * 当客户端提供新会话的ID时，是否应验证该ID？
     * 客户端提供会话ID的唯一用例是在多个Web应用程序之间使用公共会话ID
     * 因此，任何客户端提供的会话ID都应该已经存在于另一个Web应用程序中
     * 如果启用此检查，客户端提供的会话ID将仅在当前主机的至少一个其他Web应用程序中存在时使用
     * 请注意，无论此设置如何，始终应用以下附加测试：
     * <ul>
     * <li>会话ID由Cookie提供</li>
     * <li>会话Cookie的路径为{@code /}</li>
     * </ul>
     *
     * @param validateClientProvidedNewSessionId true表示应应用验证
     */
    void setValidateClientProvidedNewSessionId(boolean validateClientProvidedNewSessionId);


    /**
     * 使用前是否验证客户端提供的会话ID？
     *
     * @return true表示将应用验证，否则false
     */
    boolean getValidateClientProvidedNewSessionId();


    /**
     * 如果启用，Web应用上下文根的请求将被Mapper重定向（添加尾部斜杠）
     * 这更高效，但具有确认上下文路径有效的副作用
     *
     * @param mapperContextRootRedirectEnabled 是否应启用重定向？
     */
    void setMapperContextRootRedirectEnabled(boolean mapperContextRootRedirectEnabled);


    /**
     * 确定Web应用上下文根的请求是否将被Mapper重定向（添加尾部斜杠）
     * 这更高效，但具有确认上下文路径有效的副作用
     *
     * @return true如果为此Context启用了Mapper级重定向
     */
    boolean getMapperContextRootRedirectEnabled();


    /**
     * 如果启用，目录的请求将被Mapper重定向（添加尾部斜杠）
     * 这更高效，但具有确认目录有效的副作用
     *
     * @param mapperDirectoryRedirectEnabled 是否应启用重定向？
     */
    void setMapperDirectoryRedirectEnabled(boolean mapperDirectoryRedirectEnabled);


    /**
     * 确定目录的请求是否将被Mapper重定向（添加尾部斜杠）
     * 这更高效，但具有确认目录有效的副作用
     *
     * @return true如果为此Context启用了Mapper级重定向
     */
    boolean getMapperDirectoryRedirectEnabled();


    /**
     * 控制通过调用HttpServletResponse.sendRedirect(String)生成的HTTP 1.1及更高版本的Location标头
     * 将使用相对重定向还是绝对重定向
     * <p>
     * 相对重定向更高效，但可能不适用于更改上下文路径的反向代理
     * 应注意，不建议使用反向代理更改上下文路径，因为它会产生多个问题
     * <p>
     * 绝对重定向应适用于更改上下文路径的反向代理
     * 但如果过滤器正在更改方案和/或端口，可能会导致与RemoteIpFilter的问题
     *
     * @param useRelativeRedirects true表示使用相对重定向，false表示使用绝对重定向
     */
    void setUseRelativeRedirects(boolean useRelativeRedirects);


    /**
     * 通过调用HttpServletResponse.sendRedirect(String)生成的HTTP 1.1及更高版本的Location标头
     * 将使用相对重定向还是绝对重定向？
     *
     * @return true如果将使用相对重定向，false如果使用绝对重定向
     */
    boolean getUseRelativeRedirects();


    /**
     * 获取请求调度程序调用中使用的路径是否应为编码的
     * 这会影响Tomcat处理请求调度程序调用的方式
     * 以及Tomcat内部生成用于获取请求调度程序的路径的方式
     *
     * @param dispatchersUseEncodedPaths true表示使用编码路径，否则false
     */
    void setDispatchersUseEncodedPaths(boolean dispatchersUseEncodedPaths);


    /**
     * 请求调度程序调用中使用的路径是否应为编码的？
     * 这适用于Tomcat处理请求调度程序调用的方式
     * 以及Tomcat内部生成用于获取请求调度程序的路径的方式
     *
     * @return true如果将使用编码路径，否则false
     */
    boolean getDispatchersUseEncodedPaths();


    /**
     * 设置此Web应用的默认请求体编码
     *
     * @param encoding 默认编码
     */
    void setRequestCharacterEncoding(String encoding);


    /**
     * 获取此Web应用的默认请求体编码
     *
     * @return 默认请求体编码
     */
    String getRequestCharacterEncoding();


    /**
     * 设置此Web应用的默认响应体编码
     *
     * @param encoding 默认编码
     */
    void setResponseCharacterEncoding(String encoding);


    /**
     * 获取此Web应用的默认响应体编码
     *
     * @return 默认响应体编码
     */
    String getResponseCharacterEncoding();


    /**
     * 配置从HttpServletRequest.getContextPath()返回上下文路径时
     * 返回值是否允许包含多个前导'/'字符
     *
     * @param allowMultipleLeadingForwardSlashInPath 标志的新值
     */
    void setAllowMultipleLeadingForwardSlashInPath(boolean allowMultipleLeadingForwardSlashInPath);


    /**
     * 从HttpServletRequest.getContextPath()返回上下文路径时
     * 是否允许包含多个前导'/'字符？
     *
     * @return true如果允许多个前导'/'字符，否则false
     */
    boolean getAllowMultipleLeadingForwardSlashInPath();


    /**
     * 增加进行中的异步计数
     */
    void incrementInProgressAsyncCount();


    /**
     * 减少进行中的异步计数
     */
    void decrementInProgressAsyncCount();


    /**
     * 配置如果Web应用尝试使用上传目标而该目标不存在时
     * Tomcat是否会尝试创建该目标
     *
     * @param createUploadTargets true表示Tomcat应尝试创建上传目标，否则false
     */
    void setCreateUploadTargets(boolean createUploadTargets);


    /**
     * 当Web应用尝试使用上传目标而该目标不存在时
     * Tomcat是否会尝试创建该目标？
     *
     * @return true表示Tomcat将尝试创建上传目标，否则false
     */
    boolean getCreateUploadTargets();


    /**
     * 如果为true，每个与会话关联的请求将导致会话的最后访问时间更新
     * 无论请求是否显式访问会话
     * 如果org.apache.catalina.STRICT_SERVLET_COMPLIANCE设置为true
     * 此设置的默认值将为true，否则默认值为false
     *
     * @return 标志值
     */
    boolean getAlwaysAccessSession();


    /**
     * 设置会话访问行为
     *
     * @param alwaysAccessSession 新的标志值
     */
    void setAlwaysAccessSession(boolean alwaysAccessSession);


    /**
     * 如果为true，传递给ServletContext.getResource()或ServletContext.getResourceAsStream()的路径
     * 必须以"/"开头
     * 如果为false，像getResource("myfolder/myresource.txt")这样的代码将起作用
     * 因为Tomcat会在提供的路径前加上"/"
     * 如果org.apache.catalina.STRICT_SERVLET_COMPLIANCE设置为true
     * 此设置的默认值将为true，否则默认值为false
     *
     * @return 标志值
     */
    boolean getContextGetResourceRequiresSlash();


    /**
     * 允许使用不带前导"/"的ServletContext.getResource()或ServletContext.getResourceAsStream()
     *
     * @param contextGetResourceRequiresSlash 新的标志值
     */
    void setContextGetResourceRequiresSlash(boolean contextGetResourceRequiresSlash);


    /**
     * 如果为true，传递给应用程序调度程序的任何包装的请求或响应对象
     * 将被检查以确保它包装了原始请求或响应
     * 如果org.apache.catalina.STRICT_SERVLET_COMPLIANCE设置为true
     * 此设置的默认值将为true，否则默认值为false
     *
     * @return 标志值
     */
    boolean getDispatcherWrapsSameObject();


    /**
     * 允许禁用请求调度程序中的对象包装检查
     *
     * @param dispatcherWrapsSameObject 新的标志值
     */
    void setDispatcherWrapsSameObject(boolean dispatcherWrapsSameObject);


    /**
     * 如果为true，转发后响应将被解包以暂停Catalina响应
     * 而不是简单地关闭顶级响应
     * 默认值为false
     *
     * @return 标志值
     */
    boolean getSuspendWrappedResponseAfterForward();


    /**
     * 允许解包响应对象以在转发后暂停响应
     *
     * @param suspendWrappedResponseAfterForward 新的标志值
     */
    void setSuspendWrappedResponseAfterForward(boolean suspendWrappedResponseAfterForward);


    /**
     * 查找具有指定路径的配置文件
     * 首先查看Web应用资源，然后委托给ConfigFileLoader.getSource().getResource
     * WEBAPP_PROTOCOL常量前缀用于表示Web应用资源
     *
     * @param name 资源名称
     * @return 资源
     * @throws IOException 如果发生错误或资源不存在
     */
    default Resource findConfigFileResource(String name) throws IOException {
        if (name.startsWith(WEBAPP_PROTOCOL)) {
            String path = name.substring(WEBAPP_PROTOCOL.length());
            WebResource resource = getResources().getResource(path);
            if (resource.canRead() && resource.isFile()) {
                InputStream stream = resource.getInputStream();
                try {
                    return new Resource(stream, resource.getURL().toURI());
                } catch (URISyntaxException e) {
                    stream.close();
                }
            }
            throw new FileNotFoundException(name);
        }
        return ConfigFileLoader.getSource().getResource(name);
    }


    /**
     * 返回true如果资源归档查找将使用布隆过滤器
     *
     * @deprecated 此方法将在Tomcat 11及以后版本中删除
     * 使用WebResourceRoot.getArchiveIndexStrategy()
     */
    @Deprecated
    boolean getUseBloomFilterForArchives();


    /**
     * 设置布隆过滤器标志值
     *
     * @param useBloomFilterForArchives 新的快速类路径扫描标志
     * @deprecated 此方法将在Tomcat 11及以后版本中删除
     * 使用WebResourceRoot.setArchiveIndexStrategy(String)
     */
    @Deprecated
    void setUseBloomFilterForArchives(boolean useBloomFilterForArchives);


    /**
     * 获取此Context中用于获取RequestDispatcher实例的路径中
     * 编码反向斜杠(%5c - \)字符的当前处理配置
     *
     * @return 编码反向斜杠字符的当前处理配置
     */
    default String getEncodedReverseSolidusHandling() {
        return EncodedSolidusHandling.DECODE.getValue();
    }


    /**
     * 配置此Context中用于获取RequestDispatcher实例的路径中
     * 编码反向斜杠(%5c - \)字符的处理
     *
     * @param encodedReverseSolidusHandling EncodedSolidusHandling的值之一
     */
    default void setEncodedReverseSolidusHandling(String encodedReverseSolidusHandling) {
        throw new UnsupportedOperationException();
    }


    /**
     * 获取此Context中用于获取RequestDispatcher实例的路径中
     * 编码反向斜杠(%5c - \)字符的当前处理配置
     *
     * @return 编码反向斜杠字符的当前处理配置
     */
    default EncodedSolidusHandling getEncodedReverseSolidusHandlingEnum() {
        return EncodedSolidusHandling.DECODE;
    }


    /**
     * 获取此Context中用于获取RequestDispatcher实例的路径中
     * 编码斜杠(%2f - /)字符的当前处理配置
     *
     * @return 编码斜杠字符的当前处理配置
     */
    default String getEncodedSolidusHandling() {
        return EncodedSolidusHandling.DECODE.getValue();
    }


    /**
     * 配置此Context中用于获取RequestDispatcher实例的路径中
     * 编码斜杠(%2f - /)字符的处理
     *
     * @param encodedSolidusHandling EncodedSolidusHandling的值之一
     */
    default void setEncodedSolidusHandling(String encodedSolidusHandling) {
        throw new UnsupportedOperationException();
    }


    /**
     * 获取此Context中用于获取RequestDispatcher实例的路径中
     * 编码斜杠(%2f - /)字符的当前处理配置
     *
     * @return 编码斜杠字符的当前处理配置
     */
    default EncodedSolidusHandling getEncodedSolidusHandlingEnum() {
        return EncodedSolidusHandling.DECODE;
    }
}