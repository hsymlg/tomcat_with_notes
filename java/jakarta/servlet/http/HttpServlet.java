/*
 * 版权所有至 Apache 软件基金会（ASF），根据一个或多个贡献者许可协议。有关版权所有权的额外信息，请参阅随附的 NOTICE 文件。
 * ASF 根据 Apache 许可证 2.0 版（“许可证”）向您许可本文件；除非符合许可证，否则您不得使用本文件。
 * 您可以在以下地址获取许可证副本：
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * 除非适用法律要求或书面同意，否则根据许可证分发的软件按“原样”分发，
 * 不附带任何明示或暗示的保证或条件。请参阅许可证，了解管理权限和限制的特定语言。
 */
package jakarta.servlet.http;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import jakarta.servlet.*;

/**
 * 提供一个抽象类，通过子类化创建适用于网站的 HTTP Servlet。
 * HttpServlet 的子类必须至少覆盖一个方法，通常为以下之一：
 * <ul>
 * <li>doGet：处理 HTTP GET 请求
 * <li>doPost：处理 HTTP POST 请求
 * <li>doPut：处理 HTTP PUT 请求
 * <li>doDelete：处理 HTTP DELETE 请求
 * <li>init 和 destroy：管理 servlet 生命周期内的资源
 * <li>getServletInfo：提供 servlet 自身信息
 * </ul>
 * <p>
 * 几乎不需要覆盖 service 方法，该方法通过调度到每个 HTTP 请求类型的处理方法来处理标准 HTTP 请求。
 * <p>
 * 同样，几乎不需要覆盖 doOptions 和 doTrace 方法。
 * <p>
 * Servlet 通常在多线程服务器上运行，因此必须处理并发请求并小心同步访问共享资源。
 */
public abstract class HttpServlet extends GenericServlet {

    // 序列化版本号，确保不同版本序列化兼容
    private static final long serialVersionUID = 1L;

    // 定义支持的 HTTP 方法常量
    private static final String METHOD_DELETE = "DELETE";
    private static final String METHOD_HEAD = "HEAD";
    private static final String METHOD_GET = "GET";
    private static final String METHOD_OPTIONS = "OPTIONS";
    private static final String METHOD_POST = "POST";
    private static final String METHOD_PUT = "PUT";
    private static final String METHOD_TRACE = "TRACE";

    // HTTP 头相关常量
    private static final String HEADER_IFMODSINCE = "If-Modified-Since";
    private static final String HEADER_LASTMOD = "Last-Modified";

    // 本地化资源相关
    private static final String LSTRING_FILE = "jakarta.servlet.http.LocalStrings";
    private static final ResourceBundle lStrings = ResourceBundle.getBundle(LSTRING_FILE);

    // 敏感 HTTP 头列表，TRACE 请求时需过滤
    private static final List<String> SENSITIVE_HTTP_HEADERS =
        Arrays.asList("authorization", "cookie", "x-forwarded", "forwarded", "proxy-authorization");

    // 已弃用的系统属性，用于兼容旧版 doHead 处理
    @Deprecated(forRemoval = true, since = "Servlet 6.0")
    public static final String LEGACY_DO_HEAD = "jakarta.servlet.http.legacyDoHead";

    // 缓存 Allow 头值的锁对象
    private final transient Object cachedAllowHeaderValueLock = new Object();

    // 缓存的 HTTP Allow 头值，避免重复计算
    private volatile String cachedAllowHeaderValue = null;

    // 缓存的系统属性值，判断是否使用旧版 doHead 逻辑
    private volatile boolean cachedUseLegacyDoHead;

    /**
     * 构造函数，无操作，因为是抽象类
     */
    public HttpServlet() {
        // 无操作
    }

    /**
     * 初始化 servlet，调用父类初始化并缓存旧版 doHead 配置
     *
     * @param config Servlet 配置对象
     * @throws ServletException 初始化异常
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        // 读取系统属性，判断是否使用旧版 doHead 逻辑
        cachedUseLegacyDoHead = Boolean.parseBoolean(config.getInitParameter(LEGACY_DO_HEAD));
    }

    /**
     * 处理 HTTP GET 请求的方法
     * 默认为不支持状态，子类需覆盖此方法
     *
     * @param req HTTP 请求对象
     * @param resp HTTP 响应对象
     * @throws ServletException 请求处理异常
     * @throws IOException 输入/输出异常
     */
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 获取不支持 GET 方法的错误信息
        String msg = lStrings.getString("http.method_get_not_supported");
        // 发送方法不支持的错误响应
        sendMethodNotAllowed(req, resp, msg);
    }

    /**
     * 获取请求的最后修改时间，用于缓存验证
     * 默认为 -1（未知时间），子类可覆盖
     *
     * @param req HTTP 请求对象
     * @return 最后修改时间（毫秒），未知则返回 -1
     */
    protected long getLastModified(HttpServletRequest req) {
        return -1;
    }

    /**
     * 处理 HTTP HEAD 请求的方法
     * HEAD 请求与 GET 请求逻辑类似，但不返回响应体
     *
     * @param req HTTP 请求对象
     * @param resp HTTP 响应对象
     * @throws ServletException 请求处理异常
     * @throws IOException 输入/输出异常
     */
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 判断是否使用旧版 doHead 逻辑
        if (DispatcherType.INCLUDE.equals(req.getDispatcherType()) || !cachedUseLegacyDoHead) {
            // 直接调用 doGet 处理
            doGet(req, resp);
        } else {
            // 包装响应以忽略响应体，仅计算内容长度
            NoBodyResponse response = new NoBodyResponse(resp);
            doGet(req, response);
            // 处理异步请求场景
            if (req.isAsyncStarted()) {
                req.getAsyncContext().addListener(new NoBodyAsyncContextListener(response));
            } else {
                response.setContentLength();
            }
        }
    }

    /**
     * 处理 HTTP POST 请求的方法
     * 默认为不支持状态，子类需覆盖此方法
     *
     * @param req HTTP 请求对象
     * @param resp HTTP 响应对象
     * @throws ServletException 请求处理异常
     * @throws IOException 输入/输出异常
     */
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 获取不支持 POST 方法的错误信息
        String msg = lStrings.getString("http.method_post_not_supported");
        // 发送方法不支持的错误响应
        sendMethodNotAllowed(req, resp, msg);
    }

    /**
     * 处理 HTTP PUT 请求的方法
     * 默认为不支持状态，子类需覆盖此方法
     *
     * @param req HTTP 请求对象
     * @param resp HTTP 响应对象
     * @throws ServletException 请求处理异常
     * @throws IOException 输入/输出异常
     */
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 获取不支持 PUT 方法的错误信息
        String msg = lStrings.getString("http.method_put_not_supported");
        // 发送方法不支持的错误响应
        sendMethodNotAllowed(req, resp, msg);
    }

    /**
     * 处理 HTTP DELETE 请求的方法
     * 默认为不支持状态，子类需覆盖此方法
     *
     * @param req HTTP 请求对象
     * @param resp HTTP 响应对象
     * @throws ServletException 请求处理异常
     * @throws IOException 输入/输出异常
     */
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 获取不支持 DELETE 方法的错误信息
        String msg = lStrings.getString("http.method_delete_not_supported");
        // 发送方法不支持的错误响应
        sendMethodNotAllowed(req, resp, msg);
    }

    /**
     * 发送方法不支持的错误响应
     * 根据 HTTP 协议版本返回不同错误状态码
     *
     * @param req HTTP 请求对象
     * @param resp HTTP 响应对象
     * @param msg 错误信息
     * @throws IOException 输入/输出异常
     */
    private void sendMethodNotAllowed(HttpServletRequest req, HttpServletResponse resp, String msg) throws IOException {
        String protocol = req.getProtocol();
        // 处理旧版 HTTP 协议（0.9/1.0）返回 BAD_REQUEST，其他返回 METHOD_NOT_ALLOWED
        if (protocol.length() == 0 || protocol.endsWith("0.9") || protocol.endsWith("1.0")) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, msg);
        } else {
            resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, msg);
        }
    }

    /**
     * 获取缓存的 Allow 头值，包含 servlet 支持的 HTTP 方法
     *
     * @return Allow 头字符串
     */
    private String getCachedAllowHeaderValue() {
        // 双重检查锁模式获取缓存的 Allow 头值
        if (cachedAllowHeaderValue == null) {
            synchronized (cachedAllowHeaderValueLock) {
                if (cachedAllowHeaderValue == null) {
                    // 获取 servlet 类及其父类的所有声明方法
                    Method[] methods = getAllDeclaredMethods(this.getClass());
                    // 检查方法覆盖情况，确定支持的 HTTP 方法
                    boolean allowGet = false;
                    boolean allowHead = false;
                    boolean allowPost = false;
                    boolean allowPut = false;
                    boolean allowDelete = false;

                    for (Method method : methods) {
                        switch (method.getName()) {
                            case "doGet":
                                allowGet = true;
                                allowHead = true;
                                break;
                            case "doPost":
                                allowPost = true;
                                break;
                            case "doPut":
                                allowPut = true;
                                break;
                            case "doDelete":
                                allowDelete = true;
                                break;
                            default:
                                // 无操作
                        }
                    }

                    // 构建 Allow 头字符串
                    StringBuilder allow = new StringBuilder();
                    if (allowGet) {
                        allow.append(METHOD_GET).append(", ");
                    }
                    if (allowHead) {
                        allow.append(METHOD_HEAD).append(", ");
                    }
                    if (allowPost) {
                        allow.append(METHOD_POST).append(", ");
                    }
                    if (allowPut) {
                        allow.append(METHOD_PUT).append(", ");
                    }
                    if (allowDelete) {
                        allow.append(METHOD_DELETE).append(", ");
                    }
                    // OPTIONS 方法始终支持
                    allow.append(METHOD_OPTIONS);

                    cachedAllowHeaderValue = allow.toString();
                }
            }
        }
        return cachedAllowHeaderValue;
    }

    /**
     * 递归获取类及其父类的所有声明方法
     *
     * @param c 类对象
     * @return 方法数组
     */
    private static Method[] getAllDeclaredMethods(Class<?> c) {
        // 到达 HttpServlet 父类时返回 null
        if (c.equals(HttpServlet.class)) {
            return null;
        }
        // 获取父类方法和当前类方法并合并
        Method[] parentMethods = getAllDeclaredMethods(c.getSuperclass());
        Method[] thisMethods = c.getDeclaredMethods();

        if ((parentMethods != null) && (parentMethods.length > 0)) {
            Method[] allMethods = new Method[parentMethods.length + thisMethods.length];
            System.arraycopy(parentMethods, 0, allMethods, 0, parentMethods.length);
            System.arraycopy(thisMethods, 0, allMethods, parentMethods.length, thisMethods.length);
            thisMethods = allMethods;
        }
        return thisMethods;
    }

    /**
     * 处理 HTTP OPTIONS 请求的方法
     * 返回 servlet 支持的 HTTP 方法
     *
     * @param req HTTP 请求对象
     * @param resp HTTP 响应对象
     * @throws ServletException 请求处理异常
     * @throws IOException 输入/输出异常
     */
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 获取缓存的 Allow 头值
        String allow = getCachedAllowHeaderValue();
        // Tomcat 特定逻辑，判断是否允许 TRACE 方法
        if (TomcatHack.getAllowTrace(req)) {
            if (allow.length() == 0) {
                allow = METHOD_TRACE;
            } else {
                allow = allow + ", " + METHOD_TRACE;
            }
        }
        // 设置 Allow 头
        resp.setHeader("Allow", allow);
    }

    /**
     * 处理 HTTP TRACE 请求的方法
     * 返回请求头信息，过滤敏感头
     *
     * @param req HTTP 请求对象
     * @param resp HTTP 响应对象
     * @throws ServletException 请求处理异常
     * @throws IOException 输入/输出异常
     */
    protected void doTrace(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        int responseLength;
        String CRLF = "\r\n";
        // 构建 TRACE 响应内容
        StringBuilder buffer = new StringBuilder("TRACE ").append(req.getRequestURI()).append(' ').append(req.getProtocol());
        // 添加非敏感请求头
        Enumeration<String> reqHeaderNames = req.getHeaderNames();
        while (reqHeaderNames.hasMoreElements()) {
            String headerName = reqHeaderNames.nextElement();
            if (!isSensitiveHeader(headerName)) {
                Enumeration<String> headerValues = req.getHeaders(headerName);
                while (headerValues.hasMoreElements()) {
                    String headerValue = headerValues.nextElement();
                    buffer.append(CRLF).append(headerName).append(": ").append(headerValue);
                }
            }
        }
        buffer.append(CRLF);
        responseLength = buffer.length();
        // 设置响应内容
        resp.setContentType("message/http");
        resp.setContentLength(responseLength);
        ServletOutputStream out = resp.getOutputStream();
        out.print(buffer.toString());
        out.close();
    }

    /**
     * 判断请求头是否为敏感头（TRACE 请求时需过滤）
     *
     * @param headerName 头名称
     * @return 是否为敏感头
     */
    private boolean isSensitiveHeader(String headerName) {
        String lcHeaderName = headerName.toLowerCase(Locale.ENGLISH);
        // 检查是否以敏感头前缀开头
        for (String sensitiveHeaderName : SENSITIVE_HTTP_HEADERS) {
            if (lcHeaderName.startsWith(sensitiveHeaderName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 处理 HTTP 请求的核心方法，分发到具体的 doXXX 方法
     *
     * @param req HTTP 请求对象
     * @param resp HTTP 响应对象
     * @throws ServletException 请求处理异常
     * @throws IOException 输入/输出异常
     */
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String method = req.getMethod();
        // 根据请求方法调用对应的处理方法
        if (method.equals(METHOD_GET)) {
            long lastModified = getLastModified(req);
            if (lastModified == -1) {
                // 不支持缓存验证，直接调用 doGet
                doGet(req, resp);
            } else {
                // 处理缓存验证逻辑
                long ifModifiedSince;
                try {
                    ifModifiedSince = req.getDateHeader(HEADER_IFMODSINCE);
                } catch (IllegalArgumentException iae) {
                    ifModifiedSince = -1;
                }
                if (ifModifiedSince < (lastModified / 1000 * 1000)) {
                    // 资源已修改，调用 doGet 并设置 Last-Modified 头
                    maybeSetLastModified(resp, lastModified);
                    doGet(req, resp);
                } else {
                    // 资源未修改，返回 304 状态码
                    resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                }
            }

        } else if (method.equals(METHOD_HEAD)) {
            // 处理 HEAD 请求，设置 Last-Modified 头并调用 doHead
            long lastModified = getLastModified(req);
            maybeSetLastModified(resp, lastModified);
            doHead(req, resp);

        } else if (method.equals(METHOD_POST)) {
            doPost(req, resp);

        } else if (method.equals(METHOD_PUT)) {
            doPut(req, resp);

        } else if (method.equals(METHOD_DELETE)) {
            doDelete(req, resp);

        } else if (method.equals(METHOD_OPTIONS)) {
            doOptions(req, resp);

        } else if (method.equals(METHOD_TRACE)) {
            doTrace(req, resp);

        } else {
            // 不支持的方法，返回 501 状态码
            String errMsg = lStrings.getString("http.method_not_implemented");
            Object[] errArgs = new Object[1];
            errArgs[0] = method;
            errMsg = MessageFormat.format(errMsg, errArgs);
            resp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED, errMsg);
        }
    }

    /**
     * 可能设置 Last-Modified 响应头（如果未设置）
     *
     * @param resp HTTP 响应对象
     * @param lastModified 最后修改时间
     */
    private void maybeSetLastModified(HttpServletResponse resp, long lastModified) {
        // 若响应已包含 Last-Modified 头则不重复设置
        if (resp.containsHeader(HEADER_LASTMOD)) {
            return;
        }
        if (lastModified >= 0) {
            resp.setDateHeader(HEADER_LASTMOD, lastModified);
        }
    }

    /**
     * 服务方法的公共入口，转换请求响应类型后调用 protected service 方法
     *
     * 对于spring而言调用到了
     * DispatcherServlet.doGet/doPost() → DispatcherServlet.processRequest() →
     * DispatcherServlet.doService()
     *
     * @param req Servlet 请求对象
     * @param res Servlet 响应对象
     * @throws ServletException 请求处理异常
     * @throws IOException 输入/输出异常
     */
    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
        HttpServletRequest request;
        HttpServletResponse response;
        // 转换为 HTTP 请求响应类型
        try {
            request = (HttpServletRequest) req;
            response = (HttpServletResponse) res;
        } catch (ClassCastException e) {
            throw new ServletException(lStrings.getString("http.non_http"));
        }
        // 调用 HTTP 专用的 service 方法
        service(request, response);
    }

    /**
     * Tomcat 特定的工具类，用于判断是否允许 TRACE 方法
     */
    private static class TomcatHack {
        // 反射获取 Tomcat RequestFacade 的 getAllowTrace 方法
        private static final Class<?> REQUEST_FACADE_CLAZZ;
        private static final Method GET_ALLOW_TRACE;

        static {
            Method m1 = null;
            Class<?> c1 = null;
            try {
                c1 = Class.forName("org.apache.catalina.connector.RequestFacade");
                m1 = c1.getMethod("getAllowTrace", (Class<?>[]) null);
            } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException e) {
                // 非 Tomcat 环境时忽略
            }
            REQUEST_FACADE_CLAZZ = c1;
            GET_ALLOW_TRACE = m1;
        }

        /**
         * 判断是否允许 TRACE 方法（Tomcat 特定逻辑）
         *
         * @param req HTTP 请求对象
         * @return 是否允许 TRACE
         */
        public static boolean getAllowTrace(HttpServletRequest req) {
            if (REQUEST_FACADE_CLAZZ != null && GET_ALLOW_TRACE != null) {
                if (REQUEST_FACADE_CLAZZ.isAssignableFrom(req.getClass())) {
                    try {
                        return ((Boolean) GET_ALLOW_TRACE.invoke(req, (Object[]) null)).booleanValue();
                    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                        // 异常时默认允许 TRACE
                    }
                }
            }
            return true;
        }
    }

    /**
     * 处理 HEAD 请求的响应包装类，忽略响应体仅计算内容长度
     */
    private static class NoBodyResponse extends HttpServletResponseWrapper {
        private final NoBodyOutputStream noBodyOutputStream;
        private ServletOutputStream originalOutputStream;
        private NoBodyPrintWriter noBodyWriter;
        private boolean didSetContentLength;

        /**
         * 构造函数，包装原始响应对象
         *
         * @param r 原始 HTTP 响应对象
         */
        private NoBodyResponse(HttpServletResponse r) {
            super(r);
            noBodyOutputStream = new NoBodyOutputStream(this);
        }

        /**
         * 设置内容长度（基于已写入的字节数）
         */
        private void setContentLength() {
            if (!didSetContentLength) {
                if (noBodyWriter != null) {
                    noBodyWriter.flush();
                }
                super.setContentLengthLong(noBodyOutputStream.getWrittenByteCount());
            }
        }

        // 重写方法以标记内容长度已设置
        @Override
        public void setContentLength(int len) {
            super.setContentLength(len);
            didSetContentLength = true;
        }

        @Override
        public void setContentLengthLong(long len) {
            super.setContentLengthLong(len);
            didSetContentLength = true;
        }

        // 检查头是否为 Content-Length，标记内容长度已设置
        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, value);
            checkHeader(name);
        }

        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, value);
            checkHeader(name);
        }

        @Override
        public void setIntHeader(String name, int value) {
            super.setIntHeader(name, value);
            checkHeader(name);
        }

        @Override
        public void addIntHeader(String name, int value) {
            super.addIntHeader(name, value);
            checkHeader(name);
        }

        private void checkHeader(String name) {
            if ("content-length".equalsIgnoreCase(name)) {
                didSetContentLength = true;
            }
        }

        // 返回包装的输出流，忽略写入内容
        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            originalOutputStream = getResponse().getOutputStream();
            return noBodyOutputStream;
        }

        // 返回包装的打印 writer，忽略写入内容
        @Override
        public PrintWriter getWriter() throws UnsupportedEncodingException {
            if (noBodyWriter == null) {
                noBodyWriter = new NoBodyPrintWriter(noBodyOutputStream, getCharacterEncoding());
            }
            return noBodyWriter;
        }

        // 重置响应状态
        @Override
        public void reset() {
            super.reset();
            resetBuffer();
            originalOutputStream = null;
        }

        @Override
        public void resetBuffer() {
            noBodyOutputStream.resetBuffer();
            if (noBodyWriter != null) {
                noBodyWriter.resetBuffer();
            }
        }
    }

    /**
     * 忽略写入内容的输出流，仅计算字节数
     */
    private static class NoBodyOutputStream extends ServletOutputStream {
        private static final String LSTRING_FILE = "jakarta.servlet.http.LocalStrings";
        private static final ResourceBundle lStrings = ResourceBundle.getBundle(LSTRING_FILE);

        private final NoBodyResponse response;
        private boolean flushed = false;
        private long writtenByteCount = 0;

        /**
         * 构造函数，关联 NoBodyResponse
         *
         * @param response NoBodyResponse 对象
         */
        private NoBodyOutputStream(NoBodyResponse response) {
            this.response = response;
        }

        /**
         * 获取已写入的字节数
         *
         * @return 已写入字节数
         */
        private long getWrittenByteCount() {
            return writtenByteCount;
        }

        // 重写写入方法，仅计数不实际写入
        @Override
        public void write(int b) throws IOException {
            writtenByteCount++;
            checkCommit();
        }

        @Override
        public void write(byte buf[], int offset, int len) throws IOException {
            if (buf == null) {
                throw new NullPointerException(lStrings.getString("err.io.nullArray"));
            }
            if (offset < 0 || len < 0 || offset + len > buf.length) {
                String msg = lStrings.getString("err.io.indexOutOfBounds");
                Object[] msgArgs = new Object[3];
                msgArgs[0] = Integer.valueOf(offset);
                msgArgs[1] = Integer.valueOf(len);
                msgArgs[2] = Integer.valueOf(buf.length);
                msg = MessageFormat.format(msg, msgArgs);
                throw new IndexOutOfBoundsException(msg);
            }
            writtenByteCount += len;
            checkCommit();
        }

        @Override
        public boolean isReady() {
            // 始终就绪，因为不实际写入
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            response.originalOutputStream.setWriteListener(listener);
        }

        /**
         * 检查是否需要提交响应（内容超过缓冲区时）
         *
         * @throws IOException 输入/输出异常
         */
        private void checkCommit() throws IOException {
            if (!flushed && writtenByteCount > response.getBufferSize()) {
                response.flushBuffer();
                flushed = true;
            }
        }

        /**
         * 重置缓冲区（未提交时）
         */
        private void resetBuffer() {
            if (flushed) {
                throw new IllegalStateException(lStrings.getString("err.state.commit"));
            }
            writtenByteCount = 0;
        }
    }

    /**
     * 忽略写入内容的打印 writer，仅包装输出流
     */
    private static class NoBodyPrintWriter extends PrintWriter {
        private final NoBodyOutputStream out;
        private final String encoding;
        private PrintWriter pw;

        /**
         * 构造函数，创建包装的打印 writer
         *
         * @param out NoBodyOutputStream 对象
         * @param encoding 编码
         * @throws UnsupportedEncodingException 不支持的编码异常
         */
        NoBodyPrintWriter(NoBodyOutputStream out, String encoding) throws UnsupportedEncodingException {
            super(out);
            this.out = out;
            this.encoding = encoding;
            Writer osw = new OutputStreamWriter(out, encoding);
            pw = new PrintWriter(osw);
        }

        /**
         * 重置缓冲区，创建新的打印 writer
         */
        private void resetBuffer() {
            out.resetBuffer();
            Writer osw = null;
            try {
                osw = new OutputStreamWriter(out, encoding);
            } catch (UnsupportedEncodingException e) {
                // 不可能发生
            }
            pw = new PrintWriter(osw);
        }

        // 重写所有写入方法，委托给内部打印 writer
        @Override
        public void flush() {
            pw.flush();
        }

        @Override
        public void close() {
            pw.close();
        }

        @Override
        public boolean checkError() {
            return pw.checkError();
        }

        @Override
        public void write(int c) {
            pw.write(c);
        }

        @Override
        public void write(char[] buf, int off, int len) {
            pw.write(buf, off, len);
        }

        @Override
        public void write(char[] buf) {
            pw.write(buf);
        }

        @Override
        public void write(String s, int off, int len) {
            pw.write(s, off, len);
        }

        @Override
        public void write(String s) {
            pw.write(s);
        }

        @Override
        public void print(boolean b) {
            pw.print(b);
        }

        @Override
        public void print(char c) {
            pw.print(c);
        }

        @Override
        public void print(int i) {
            pw.print(i);
        }

        @Override
        public void print(long l) {
            pw.print(l);
        }

        @Override
        public void print(float f) {
            pw.print(f);
        }

        @Override
        public void print(double d) {
            pw.print(d);
        }

        @Override
        public void print(char[] s) {
            pw.print(s);
        }

        @Override
        public void print(String s) {
            pw.print(s);
        }

        @Override
        public void print(Object obj) {
            pw.print(obj);
        }

        @Override
        public void println() {
            pw.println();
        }

        @Override
        public void println(boolean x) {
            pw.println(x);
        }

        @Override
        public void println(char x) {
            pw.println(x);
        }

        @Override
        public void println(int x) {
            pw.println(x);
        }

        @Override
        public void println(long x) {
            pw.println(x);
        }

        @Override
        public void println(float x) {
            pw.println(x);
        }

        @Override
        public void println(double x) {
            pw.println(x);
        }

        @Override
        public void println(char[] x) {
            pw.println(x);
        }

        @Override
        public void println(String x) {
            pw.println(x);
        }

        @Override
        public void println(Object x) {
            pw.println(x);
        }
    }

    /**
     * 异步上下文监听器，用于异步请求完成时设置内容长度
     */
    private static class NoBodyAsyncContextListener implements AsyncListener {
        private final NoBodyResponse noBodyResponse;

        /**
         * 构造函数，关联 NoBodyResponse
         *
         * @param noBodyResponse NoBodyResponse 对象
         */
        NoBodyAsyncContextListener(NoBodyResponse noBodyResponse) {
            this.noBodyResponse = noBodyResponse;
        }

        // 异步请求完成时设置内容长度
        @Override
        public void onComplete(AsyncEvent event) throws IOException {
            noBodyResponse.setContentLength();
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException {
            // 无操作
        }

        @Override
        public void onError(AsyncEvent event) throws IOException {
            // 无操作
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException {
            // 无操作
        }
    }
}