/*
 * 版权归Apache软件基金会(ASF)所有，受一个或多个贡献者许可协议的约束。
 * 请参阅随附的NOTICE文件，了解有关版权所有权的额外信息。
 * ASF根据Apache许可证2.0版（"许可证"）授权本文件；
 * 除非符合许可证，否则您不得使用本文件。
 * 您可以在以下网址获取许可证副本：
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * 除非适用法律要求或书面同意，否则根据许可证分发的软件
 * 按"原样"分发，不附带任何形式的保证或条件，无论是明示的还是暗示的。
 * 请参阅许可证，了解管理权限和限制的具体语言。
 */
package org.apache.catalina.security;

/**
 * 静态类，用于在使用Java SecurityManager时预加载Java类，
 * 以避免defineClassInPackage RuntimePermission触发AccessControlException。
 *
 * 这个SecurityClassLoad类主要用于在 Java 安全管理器环境下预加载 Tomcat 的核心类，避免在类加载过程中触发AccessControlException异常。核心原理是：
 *
 * 当 Java 安全管理器（SecurityManager）启用时，类加载操作会受到更严格的安全检查
 * 预加载这些类可以确保它们由系统信任的类加载器加载，避免后续访问时的权限问题
 * 每个loadXXXPackage方法负责预加载 Tomcat 不同模块的类，包括核心模块、Servlet 模块、会话管理模块等
 * 对于某些类，还通过实例化操作触发静态初始化块，确保系统属性被正确读取
 *
 * 这种预加载机制在安全敏感的环境中非常重要，可以避免因类加载权限问题导致的容器启动失败或异常行为。
 *
 * @author Glenn L. Nielsen
 */
public final class SecurityClassLoad {

    /**
     * 安全类加载的公共入口方法，调用带安全管理器检查的重载方法
     *
     * @param loader 用于加载类的类加载器
     * @throws Exception 类加载过程中发生异常
     */
    public static void securityClassLoad(ClassLoader loader) throws Exception {
        securityClassLoad(loader, true);
    }


    /**
     * 核心安全类加载方法，根据安全管理器状态决定是否执行类预加载
     *
     * @param loader 用于加载类的类加载器
     * @param requireSecurityManager 是否需要检查安全管理器
     * @throws Exception 类加载过程中发生异常
     */
    static void securityClassLoad(ClassLoader loader, boolean requireSecurityManager) throws Exception {

        // 如果需要检查安全管理器但当前没有安装安全管理器，则直接返回
        if (requireSecurityManager && System.getSecurityManager() == null) {
            return;
        }

        // 预加载核心包中的类
        loadCorePackage(loader);
        // 预加载Coyote包中的类（处理HTTP通信）
        loadCoyotePackage(loader);
        // 预加载加载器相关包中的类
        loadLoaderPackage(loader);
        // 预加载Realm（安全领域）相关包中的类
        loadRealmPackage(loader);
        // 预加载Servlet相关包中的类
        loadServletsPackage(loader);
        // 预加载会话管理相关包中的类
        loadSessionPackage(loader);
        // 预加载工具类包中的类
        loadUtilPackage(loader);
        // 预加载Jakarta Servlet相关类
        loadJakartaPackage(loader);
        // 预加载连接器相关包中的类
        loadConnectorPackage(loader);
        // 预加载Tomcat工具类包中的类
        loadTomcatPackage(loader);
    }


    /**
     * 预加载Catalina核心包中的类，避免安全管理器下的访问异常
     *
     * @param loader 用于加载类的类加载器
     * @throws Exception 类加载过程中发生异常
     */
    private static void loadCorePackage(ClassLoader loader) throws Exception {
        final String basePackage = "org.apache.catalina.core.";
        // 加载访问日志适配器类
        loader.loadClass(basePackage + "AccessLogAdapter");
        // 加载应用上下文门面的特权执行方法类
        loader.loadClass(basePackage + "ApplicationContextFacade$PrivilegedExecuteMethod");
        // 加载应用调度器的特权转发类
        loader.loadClass(basePackage + "ApplicationDispatcher$PrivilegedForward");
        // 加载应用调度器的特权包含类
        loader.loadClass(basePackage + "ApplicationDispatcher$PrivilegedInclude");
        // 加载应用推送构建器类
        loader.loadClass(basePackage + "ApplicationPushBuilder");
        // 加载异步上下文实现类
        loader.loadClass(basePackage + "AsyncContextImpl");
        // 加载异步上下文实现的异步可运行类
        loader.loadClass(basePackage + "AsyncContextImpl$AsyncRunnable");
        // 加载异步上下文实现的调试异常类
        loader.loadClass(basePackage + "AsyncContextImpl$DebugException");
        // 加载异步监听器包装类
        loader.loadClass(basePackage + "AsyncListenerWrapper");
        // 加载容器基础的特权添加子节点类
        loader.loadClass(basePackage + "ContainerBase$PrivilegedAddChild");
        // 加载默认实例管理器的注解缓存条目类
        loader.loadClass(basePackage + "DefaultInstanceManager$AnnotationCacheEntry");
        // 加载默认实例管理器的注解缓存条目类型类
        loader.loadClass(basePackage + "DefaultInstanceManager$AnnotationCacheEntryType");
        // 加载默认实例管理器的特权获取字段类
        loader.loadClass(basePackage + "DefaultInstanceManager$PrivilegedGetField");
        // 加载默认实例管理器的特权获取方法类
        loader.loadClass(basePackage + "DefaultInstanceManager$PrivilegedGetMethod");
        // 加载默认实例管理器的特权加载类类
        loader.loadClass(basePackage + "DefaultInstanceManager$PrivilegedLoadClass");
        // 加载应用HTTP请求的属性名称枚举器类
        loader.loadClass(basePackage + "ApplicationHttpRequest$AttributeNamesEnumerator");
    }


    /**
     * 预加载加载器相关包中的类
     *
     * @param loader 用于加载类的类加载器
     * @throws Exception 类加载过程中发生异常
     */
    private static void loadLoaderPackage(ClassLoader loader) throws Exception {
        final String basePackage = "org.apache.catalina.loader.";
        // 加载Web应用类加载器基础的特权按名称查找类类
        loader.loadClass(basePackage + "WebappClassLoaderBase$PrivilegedFindClassByName");
        // 加载Web应用类加载器基础的特权检查日志配置类
        loader.loadClass(basePackage + "WebappClassLoaderBase$PrivilegedHasLoggingConfig");
    }


    /**
     * 预加载安全领域相关包中的类
     *
     * @param loader 用于加载类的类加载器
     * @throws Exception 类加载过程中发生异常
     */
    private static void loadRealmPackage(ClassLoader loader) throws Exception {
        final String basePackage = "org.apache.catalina.realm.";
        // 加载锁定领域的锁定记录类
        loader.loadClass(basePackage + "LockOutRealm$LockRecord");
    }


    /**
     * 预加载Servlet相关包中的类，避免安全管理器下的内存泄漏问题
     *
     * @param loader 用于加载类的类加载器
     * @throws Exception 类加载过程中发生异常
     */
    private static void loadServletsPackage(ClassLoader loader) throws Exception {
        final String basePackage = "org.apache.catalina.servlets.";
        // 避免在安全管理器下运行时DefaultServlet可能出现的内存泄漏
        // DefaultServlet在安全管理器下需要加载XML解析器
        // 确保由容器而非Web应用加载，防止通过Web应用类加载器导致内存泄漏
        loader.loadClass(basePackage + "DefaultServlet");
    }


    /**
     * 预加载会话管理相关包中的类
     *
     * @param loader 用于加载类的类加载器
     * @throws Exception 类加载过程中发生异常
     */
    private static void loadSessionPackage(ClassLoader loader) throws Exception {
        final String basePackage = "org.apache.catalina.session.";
        // 加载标准会话类
        loader.loadClass(basePackage + "StandardSession");
        // 加载标准会话的特权新建会话门面类
        loader.loadClass(basePackage + "StandardSession$PrivilegedNewSessionFacade");
        // 加载标准管理器的特权卸载类
        loader.loadClass(basePackage + "StandardManager$PrivilegedDoUnload");
    }


    /**
     * 预加载工具类包中的类
     *
     * @param loader 用于加载类的类加载器
     * @throws Exception 类加载过程中发生异常
     */
    private static void loadUtilPackage(ClassLoader loader) throws Exception {
        final String basePackage = "org.apache.catalina.util.";
        // 加载参数映射类
        loader.loadClass(basePackage + "ParameterMap");
        // 加载请求工具类
        loader.loadClass(basePackage + "RequestUtil");
        // 加载TLS工具类
        loader.loadClass(basePackage + "TLSUtil");
    }


    /**
     * 预加载Coyote包中的类（处理HTTP通信）
     *
     * @param loader 用于加载类的类加载器
     * @throws Exception 类加载过程中发生异常
     */
    private static void loadCoyotePackage(ClassLoader loader) throws Exception {
        final String basePackage = "org.apache.coyote.";
        // 加载HTTP/1.1协议常量类
        loader.loadClass(basePackage + "http11.Constants");
        // 确保此时读取系统属性
        Class<?> clazz = loader.loadClass(basePackage + "Constants");
        // 通过构造函数实例化以触发静态初始化块
        clazz.getConstructor().newInstance();
        // 加载HTTP/2协议流的特权推送类
        loader.loadClass(basePackage + "http2.Stream$PrivilegedPush");
    }


    /**
     * 预加载Jakarta Servlet相关类
     *
     * @param loader 用于加载类的类加载器
     * @throws Exception 类加载过程中发生异常
     */
    private static void loadJakartaPackage(ClassLoader loader) throws Exception {
        // 加载Jakarta Servlet的Cookie类
        loader.loadClass("jakarta.servlet.http.Cookie");
    }


    /**
     * 预加载连接器相关包中的类，包含各种特权操作类
     *
     * @param loader 用于加载类的类加载器
     * @throws Exception 类加载过程中发生异常
     */
    private static void loadConnectorPackage(ClassLoader loader) throws Exception {
        final String basePackage = "org.apache.catalina.connector.";
        // 加载请求门面的获取属性特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetAttributePrivilegedAction");
        // 加载请求门面的获取参数映射特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetParameterMapPrivilegedAction");
        // 加载请求门面的获取请求调度器特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetRequestDispatcherPrivilegedAction");
        // 加载请求门面的获取参数特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetParameterPrivilegedAction");
        // 加载请求门面的获取参数名称特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetParameterNamesPrivilegedAction");
        // 加载请求门面的获取参数值特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetParameterValuePrivilegedAction");
        // 加载请求门面的获取字符编码特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetCharacterEncodingPrivilegedAction");
        // 加载请求门面的获取头部信息特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetHeadersPrivilegedAction");
        // 加载请求门面的获取头部名称特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetHeaderNamesPrivilegedAction");
        // 加载请求门面的获取Cookie特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetCookiesPrivilegedAction");
        // 加载请求门面的获取区域设置特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetLocalePrivilegedAction");
        // 加载请求门面的获取区域设置列表特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetLocalesPrivilegedAction");
        // 加载响应门面的设置内容类型特权操作类
        loader.loadClass(basePackage + "ResponseFacade$SetContentTypePrivilegedAction");
        // 加载响应门面的日期头部特权操作类
        loader.loadClass(basePackage + "ResponseFacade$DateHeaderPrivilegedAction");
        // 加载请求门面的获取会话特权操作类
        loader.loadClass(basePackage + "RequestFacade$GetSessionPrivilegedAction");
        // 加载响应门面的刷新缓冲区特权操作类
        loader.loadClass(basePackage + "ResponseFacade$FlushBufferPrivilegedAction");
        // 加载输出缓冲区的特权创建转换器类
        loader.loadClass(basePackage + "OutputBuffer$PrivilegedCreateConverter");
        // 加载Coyote输入流的特权获取可用字节数类
        loader.loadClass(basePackage + "CoyoteInputStream$PrivilegedAvailable");
        // 加载Coyote输入流的特权关闭类
        loader.loadClass(basePackage + "CoyoteInputStream$PrivilegedClose");
        // 加载Coyote输入流的特权读取类
        loader.loadClass(basePackage + "CoyoteInputStream$PrivilegedRead");
        // 加载Coyote输入流的特权读取数组类
        loader.loadClass(basePackage + "CoyoteInputStream$PrivilegedReadArray");
        // 加载Coyote输入流的特权读取缓冲区类
        loader.loadClass(basePackage + "CoyoteInputStream$PrivilegedReadBuffer");
        // 加载Coyote输出流类
        loader.loadClass(basePackage + "CoyoteOutputStream");
        // 加载输入缓冲区的特权创建转换器类
        loader.loadClass(basePackage + "InputBuffer$PrivilegedCreateConverter");
        // 加载响应的特权检查是否可编码类
        loader.loadClass(basePackage + "Response$PrivilegedDoIsEncodable");
        // 加载响应的特权生成Cookie字符串类
        loader.loadClass(basePackage + "Response$PrivilegedGenerateCookieString");
        // 加载响应的特权编码URL类
        loader.loadClass(basePackage + "Response$PrivilegedEncodeUrl");
    }


    /**
     * 预加载Tomcat工具类包中的类，包含各种功能模块
     *
     * @param loader 用于加载类的类加载器
     * @throws Exception 类加载过程中发生异常
     */
    private static void loadTomcatPackage(ClassLoader loader) throws Exception {
        final String basePackage = "org.apache.tomcat.";
        // --------------------------- buf 包 ---------------------------
        // 加载字节到字符转换器类
        loader.loadClass(basePackage + "util.buf.B2CConverter");
        // 加载字节缓冲区工具类
        loader.loadClass(basePackage + "util.buf.ByteBufferUtils");
        // 加载字符到字节转换器类
        loader.loadClass(basePackage + "util.buf.C2BConverter");
        // 加载十六进制工具类
        loader.loadClass(basePackage + "util.buf.HexUtils");
        // 加载字符串缓存类
        loader.loadClass(basePackage + "util.buf.StringCache");
        // 加载字符串缓存的字节条目类
        loader.loadClass(basePackage + "util.buf.StringCache$ByteEntry");
        // 加载字符串缓存的字符条目类
        loader.loadClass(basePackage + "util.buf.StringCache$CharEntry");
        // 加载URI工具类
        loader.loadClass(basePackage + "util.buf.UriUtil");
        // ------------------------- collections 包 -------------------------
        // 加载不区分大小写的键映射类
        loader.loadClass(basePackage + "util.collections.CaseInsensitiveKeyMap");
        // 加载不区分大小写的键映射的条目实现类
        loader.loadClass(basePackage + "util.collections.CaseInsensitiveKeyMap$EntryImpl");
        // 加载不区分大小写的键映射的条目迭代器类
        loader.loadClass(basePackage + "util.collections.CaseInsensitiveKeyMap$EntryIterator");
        // 加载不区分大小写的键映射的条目集合类
        loader.loadClass(basePackage + "util.collections.CaseInsensitiveKeyMap$EntrySet");
        // 加载不区分大小写的键映射的键类
        loader.loadClass(basePackage + "util.collections.CaseInsensitiveKeyMap$Key");
        // --------------------------- http 包 ---------------------------
        // 加载Cookie处理器类
        loader.loadClass(basePackage + "util.http.CookieProcessor");
        // 加载名称枚举器类
        loader.loadClass(basePackage + "util.http.NamesEnumerator");
        // 确保此时读取系统属性
        Class<?> clazz = loader.loadClass(basePackage + "util.http.FastHttpDateFormat");
        // 通过构造函数实例化以触发静态初始化块
        clazz.getConstructor().newInstance();
        // 加载HTTP解析器类
        loader.loadClass(basePackage + "util.http.parser.HttpParser");
        // 加载媒体类型类
        loader.loadClass(basePackage + "util.http.parser.MediaType");
        // 加载媒体类型缓存类
        loader.loadClass(basePackage + "util.http.parser.MediaTypeCache");
        // 加载跳过结果类
        loader.loadClass(basePackage + "util.http.parser.SkipResult");
        // --------------------------- net 包 ---------------------------
        // 加载网络常量类
        loader.loadClass(basePackage + "util.net.Constants");
        // 加载调度类型类
        loader.loadClass(basePackage + "util.net.DispatchType");
        // 加载NIO端点的套接字包装器的操作状态类
        loader.loadClass(basePackage + "util.net.NioEndpoint$NioSocketWrapper$NioOperationState");
        // 加载NIO2端点的套接字包装器的操作状态类
        loader.loadClass(basePackage + "util.net.Nio2Endpoint$Nio2SocketWrapper$Nio2OperationState");
        // 加载套接字包装器基础的阻塞模式类
        loader.loadClass(basePackage + "util.net.SocketWrapperBase$BlockingMode");
        // 加载套接字包装器基础的完成检查类
        loader.loadClass(basePackage + "util.net.SocketWrapperBase$CompletionCheck");
        // 加载套接字包装器基础的完成处理程序调用类
        loader.loadClass(basePackage + "util.net.SocketWrapperBase$CompletionHandlerCall");
        // 加载套接字包装器基础的完成状态类
        loader.loadClass(basePackage + "util.net.SocketWrapperBase$CompletionState");
        // 加载套接字包装器基础的向量IO完成处理程序类
        loader.loadClass(basePackage + "util.net.SocketWrapperBase$VectoredIOCompletionHandler");
        // 加载TLS客户端Hello提取器类
        loader.loadClass(basePackage + "util.net.TLSClientHelloExtractor");
        // 加载TLS客户端Hello提取器的结果类
        loader.loadClass(basePackage + "util.net.TLSClientHelloExtractor$ExtractorResult");
        // ------------------------- security 包 -------------------------
        // 加载特权获取线程上下文类加载器类
        loader.loadClass(basePackage + "util.security.PrivilegedGetTccl");
        // 加载特权设置线程上下文类加载器类
        loader.loadClass(basePackage + "util.security.PrivilegedSetTccl");
        // 加载特权设置访问控制上下文类
        loader.loadClass(basePackage + "util.security.PrivilegedSetAccessControlContext");
    }
}