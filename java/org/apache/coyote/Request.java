/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.coyote;

// 导入基础IO与字符集处理相关类
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

// 导入集合与并发工具类
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

// 导入Servlet API相关接口
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConnection;

// 导入Tomcat内部工具类
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.buf.UDecoder;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.Parameters;
import org.apache.tomcat.util.http.ServerCookies;
import org.apache.tomcat.util.http.parser.MediaType;
import org.apache.tomcat.util.net.ApplicationBufferHandler;
import org.apache.tomcat.util.res.StringManager;

/**
 * Tomcat内部请求处理的核心类，负责高效表示和处理HTTP请求
 *
 * 设计特点：
 * 1. 采用GC-free设计：大多数成员变量使用对象复用机制减少垃圾回收
 * 2. 延迟计算策略：昂贵操作（如参数解析）延迟到实际需要时执行
 * 3. 模块化处理：通过钩子机制将处理逻辑委派给不同模块
 * 4. 高性能优化：使用特殊数据结构（如MessageBytes）减少字符串操作开销
 *
 * 使用限制：
 * - 非公共API，仅供Tomcat内部使用
 * - 应用层代码通过HttpServletRequest接口访问请求信息
 *
 * 内部属性：
 * - "org.apache.tomcat.request"：允许受信任应用直接访问底层请求对象
 */
public final class Request {

    // 字符串资源管理器，用于加载国际化错误信息
    private static final StringManager sm = StringManager.getManager(Request.class);

    // 初始Cookie数组大小，优化常见场景下的内存分配
    private static final int INITIAL_COOKIE_SIZE = 4;

    /*
     * 请求ID生成器，采用AtomicLong实现线程安全：
     * - 设计目标：支持10万请求/秒持续运行300万年不重复
     * - 性能特性：单实例可支持6000万+请求/秒（17ns/请求）
     * - 实现方式：通过原子操作保证多线程环境下ID唯一性
     */
    private static final AtomicLong requestIdGenerator = new AtomicLong(0);

    // ----------------------------------------------------------- Constructors

    /**
     * 构造函数：初始化请求对象的核心组件
     *
     * 初始化操作：
     * 1. 设置参数处理器的查询字符串来源
     * 2. 关联URL解码器，用于处理URI和参数解码
     */
    public Request() {
        parameters.setQuery(queryMB);
        parameters.setURLDecoder(urlDecoder);
    }

    // ----------------------------------------------------- Instance Variables

    // 服务器端口信息（-1表示未设置）
    private int serverPort = -1;
    // 服务器名称（使用MessageBytes避免不必要的字符串转换）
    private final MessageBytes serverNameMB = MessageBytes.newInstance();

    // 客户端和服务器端口信息
    private int remotePort;     // 客户端端口
    private int localPort;      // 本地服务端口

    // 请求协议方案（如"http"或"https"）
    private final MessageBytes schemeMB = MessageBytes.newInstance();

    // 请求行核心组件（使用MessageBytes优化内存操作）
    private final MessageBytes methodMB = MessageBytes.newInstance();       // HTTP方法（GET/POST等）
    private final MessageBytes uriMB = MessageBytes.newInstance();          // 原始请求URI
    private final MessageBytes decodedUriMB = MessageBytes.newInstance();   // 解码后的URI
    private final MessageBytes queryMB = MessageBytes.newInstance();        // 查询字符串
    private final MessageBytes protoMB = MessageBytes.newInstance();        // 协议版本（如HTTP/1.1）

    // 请求唯一标识符（使用原子生成器确保唯一性）
    private volatile String requestId = Long.toString(requestIdGenerator.getAndIncrement());

    // 网络地址信息（使用MessageBytes避免字符串创建开销）
    private final MessageBytes remoteAddrMB = MessageBytes.newInstance();   // 客户端IP地址
    private final MessageBytes peerAddrMB = MessageBytes.newInstance();     // 对等方IP地址（用于代理场景）
    private final MessageBytes localNameMB = MessageBytes.newInstance();    // 本地服务器名称
    private final MessageBytes remoteHostMB = MessageBytes.newInstance();   // 客户端主机名
    private final MessageBytes localAddrMB = MessageBytes.newInstance();    // 本地服务器IP地址

    // 请求头和尾部字段（使用MimeHeaders高效处理HTTP头）
    private final MimeHeaders headers = new MimeHeaders();                // 请求头
    private final MimeHeaders trailerFields = new MimeHeaders();          // 分块传输的尾部字段

    /**
     * 路径参数存储（如RESTful API中的变量部分）
     * 示例：/users/{id} 中的id参数
     */
    private final Map<String, String> pathParameters = new HashMap<>();

    /**
     * 内部存储区域，用于模块间传递数据
     * 注：索引0-8保留给Servlet容器，9-16保留给连接器，17-31未分配
     */
    private final Object[] notes = new Object[Constants.MAX_NOTES];

    /**
     * 关联的输入缓冲区，用于读取请求体数据
     * 实际类型取决于具体的协议实现（如HTTP/1.1或HTTP/2）
     */
    private InputBuffer inputBuffer = null;

    /**
     * URL解码器，用于处理URI和参数的解码
     * 支持标准的URL编码规则（如%20表示空格）
     */
    private final UDecoder urlDecoder = new UDecoder();

    /**
     * HTTP请求内容相关字段
     */
    private long contentLength = -1;                  // 内容长度（-1表示未指定）
    private MessageBytes contentTypeMB = null;        // 内容类型
    private Charset charset = null;                   // 字符集（解析后）
    // 原始字符编码（可能无效，但需保留用于返回给应用）
    private String characterEncoding = null;

    /**
     * Expect请求头标记（如"Expect: 100-continue"）
     * 用于客户端在发送大请求体前确认服务器是否愿意接收
     */
    private boolean expectation = false;

    // 客户端Cookie和请求参数处理器
    private final ServerCookies serverCookies = new ServerCookies(INITIAL_COOKIE_SIZE);
    private final Parameters parameters = new Parameters();

    // 认证相关信息
    private final MessageBytes remoteUser = MessageBytes.newInstance();           // 认证用户名
    private boolean remoteUserNeedsAuthorization = false;       // 是否需要授权
    private final MessageBytes authType = MessageBytes.newInstance();             // 认证类型

    // 通用属性存储（用于在请求处理链中传递数据）
    private final HashMap<String, Object> attributes = new HashMap<>();

    // 关联的响应对象和处理钩子
    private Response response;
    private volatile ActionHook hook;

    // 性能统计和状态跟踪
    private long bytesRead = 0;                   // 已读取的字节数
    private long startTimeNanos = -1;             // 请求开始时间（纳秒）
    private long threadId = 0;                    // 处理线程ID
    private int available = 0;                    // 可读取的字节数

    // 用于JMX监控的请求信息对象
    private final RequestInfo reqProcessorMX = new RequestInfo(this);

    // 是否启用sendfile优化（用于静态资源传输）
    private boolean sendfile = true;

    /**
     * 请求体读取过程中发生的异常
     * 用于跟踪和报告请求处理中的错误
     */
    private Exception errorException = null;

    /*
     * 非阻塞I/O状态管理
     * 这些变量用于协调异步读取操作和事件触发
     */
    volatile ReadListener listener;               // 异步读取监听器
    private boolean fireListener = false;         // 是否触发监听器
    private boolean registeredForRead = false;    // 是否已注册读取事件
    private final Object nonBlockingStateLock = new Object();  // 状态锁

    /**
     * 获取异步读取监听器
     */
    public ReadListener getReadListener() {
        return listener;
    }

    /**
     * 设置异步读取监听器
     *
     * 处理逻辑：
     * 1. 验证监听器不为空且未设置过
     * 2. 确认请求处于异步模式
     * 3. 初始化监听器并根据当前状态决定是否立即触发
     */
    public void setReadListener(ReadListener listener) {
        if (listener == null) {
            throw new NullPointerException(sm.getString("request.nullReadListener"));
        }
        if (getReadListener() != null) {
            throw new IllegalStateException(sm.getString("request.readListenerSet"));
        }
        // 验证请求是否处于异步模式
        AtomicBoolean result = new AtomicBoolean(false);
        action(ActionCode.ASYNC_IS_ASYNC, result);
        if (!result.get()) {
            throw new IllegalStateException(sm.getString("request.notAsync"));
        }

        this.listener = listener;

        // 容器负责首次调用onDataAvailable()
        // 如果isReady()返回true，需在新线程中触发监听器
        // 如果isReady()返回false，注册读取事件，数据到达时触发监听器
        if (!isFinished() && isReady()) {
            synchronized (nonBlockingStateLock) {
                // 确保不重复注册读取事件
                registeredForRead = true;
                // 设置触发标志，确保容器尝试触发onDataAvailable()时有效
                fireListener = true;
            }
            action(ActionCode.DISPATCH_READ, null);
            if (!isRequestThread()) {
                // 不在容器线程上，需要执行调度
                action(ActionCode.DISPATCH_EXECUTE, null);
            }
        }
    }

    /**
     * 检查是否可以立即读取数据
     *
     * 返回值说明：
     * - true：可以立即读取数据
     * - false：不能立即读取，已注册读取事件，数据到达时会触发监听器
     */
    public boolean isReady() {
        boolean ready;
        synchronized (nonBlockingStateLock) {
            if (registeredForRead) {
                fireListener = true;
                return false;
            }
            ready = checkRegisterForRead();
            fireListener = !ready;
        }
        return ready;
    }

    /**
     * 检查并注册读取事件
     *
     * 返回值说明：
     * - true：数据已就绪，可以读取
     * - false：数据未就绪，已注册读取事件
     */
    private boolean checkRegisterForRead() {
        AtomicBoolean ready = new AtomicBoolean(false);
        synchronized (nonBlockingStateLock) {
            if (!registeredForRead) {
                action(ActionCode.NB_READ_INTEREST, ready);
                registeredForRead = !ready.get();
            }
        }
        return ready.get();
    }

    /**
     * 数据可用时的回调方法
     * 由底层I/O系统在数据到达时调用
     */
    public void onDataAvailable() throws IOException {
        boolean fire = false;
        synchronized (nonBlockingStateLock) {
            registeredForRead = false;
            if (fireListener) {
                fireListener = false;
                fire = true;
            }
        }
        if (fire) {
            listener.onDataAvailable();
        }
    }

    // 标记是否已发送"所有数据已读取"事件
    private final AtomicBoolean allDataReadEventSent = new AtomicBoolean(false);

    /**
     * 尝试发送"所有数据已读取"事件
     * 使用CAS操作确保事件只发送一次
     */
    public boolean sendAllDataReadEvent() {
        return allDataReadEventSent.compareAndSet(false, true);
    }

    // ------------------------------------------------------------- Properties

    /**
     * 获取请求头集合
     */
    public MimeHeaders getMimeHeaders() {
        return headers;
    }

    /**
     * 检查尾部字段是否已准备好
     * 用于分块传输编码的请求
     */
    public boolean isTrailerFieldsReady() {
        AtomicBoolean result = new AtomicBoolean(false);
        action(ActionCode.IS_TRAILER_FIELDS_READY, result);
        return result.get();
    }

    /**
     * 获取尾部字段的Map表示
     */
    public Map<String, String> getTrailerFields() {
        return trailerFields.toMap();
    }

    /**
     * 获取尾部字段的MimeHeaders表示
     */
    public MimeHeaders getMimeTrailerFields() {
        return trailerFields;
    }

    /**
     * 获取URL解码器
     */
    public UDecoder getURLDecoder() {
        return urlDecoder;
    }

    // -------------------- Request data --------------------

    // 获取请求各部分的MessageBytes表示（避免字符串转换开销）
    public MessageBytes scheme() {
        return schemeMB;
    }

    public MessageBytes method() {
        return methodMB;
    }

    public MessageBytes requestURI() {
        return uriMB;
    }

    public MessageBytes decodedURI() {
        return decodedUriMB;
    }

    public MessageBytes queryString() {
        return queryMB;
    }

    public MessageBytes protocol() {
        return protoMB;
    }

    /**
     * 获取请求的"虚拟主机"（从Host请求头解析）
     *
     * @return 服务器名称的缓冲区表示，使用isNull()检查是否未设置
     */
    public MessageBytes serverName() {
        return serverNameMB;
    }

    // 服务器端口的访问器和修改器
    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    // 地址和端口信息的访问器
    public MessageBytes remoteAddr() {
        return remoteAddrMB;
    }

    public MessageBytes peerAddr() {
        return peerAddrMB;
    }

    public MessageBytes remoteHost() {
        return remoteHostMB;
    }

    public MessageBytes localName() {
        return localNameMB;
    }

    public MessageBytes localAddr() {
        return localAddrMB;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public void setRemotePort(int port) {
        this.remotePort = port;
    }

    public int getLocalPort() {
        return localPort;
    }

    public void setLocalPort(int port) {
        this.localPort = port;
    }

    // -------------------- encoding/type --------------------

    /**
     * 获取请求的字符编码
     *
     * 处理逻辑：
     * 1. 优先返回显式设置的编码
     * 2. 否则尝试从Content-Type头解析
     * 3. 未找到则返回null
     */
    public String getCharacterEncoding() {
        if (characterEncoding == null) {
            characterEncoding = getCharsetFromContentType(getContentType());
        }

        return characterEncoding;
    }

    /**
     * 获取请求的字符集
     *
     * @throws UnsupportedEncodingException 如果指定的字符集不支持
     */
    public Charset getCharset() throws UnsupportedEncodingException {
        if (charset == null) {
            getCharacterEncoding();
            if (characterEncoding != null) {
                charset = B2CConverter.getCharset(characterEncoding);
            }
        }

        return charset;
    }

    /**
     * 设置请求的字符集
     */
    public void setCharset(Charset charset) {
        this.charset = charset;
        this.characterEncoding = charset.name();
    }

    // 内容长度相关方法
    public void setContentLength(long len) {
        this.contentLength = len;
    }

    /**
     * 获取内容长度（整数形式，超过Integer.MAX_VALUE时返回-1）
     */
    public int getContentLength() {
        long length = getContentLengthLong();

        if (length < Integer.MAX_VALUE) {
            return (int) length;
        }
        return -1;
    }

    /**
     * 获取内容长度（长整型形式）
     */
    public long getContentLengthLong() {
        if (contentLength > -1) {
            return contentLength;
        }

        // 从Content-Length头解析长度
        MessageBytes clB = headers.getUniqueValue("content-length");
        contentLength = (clB == null || clB.isNull()) ? -1 : clB.getLong();

        return contentLength;
    }

    // 内容类型相关方法
    public String getContentType() {
        contentType();
        if (contentTypeMB == null || contentTypeMB.isNull()) {
            return null;
        }
        return contentTypeMB.toStringType();
    }

    public void setContentType(String type) {
        contentTypeMB.setString(type);
    }

    public MessageBytes contentType() {
        if (contentTypeMB == null) {
            contentTypeMB = headers.getValue("content-type");
        }
        return contentTypeMB;
    }

    public void setContentType(MessageBytes mb) {
        contentTypeMB = mb;
    }

    /**
     * 获取指定名称的请求头值
     */
    public String getHeader(String name) {
        return headers.getHeader(name);
    }

    // Expect头相关方法
    public void setExpectation(boolean expectation) {
        this.expectation = expectation;
    }

    public boolean hasExpectation() {
        return expectation;
    }

    // -------------------- Associated response --------------------

    // 响应对象的访问器和修改器
    public Response getResponse() {
        return response;
    }

    public void setResponse(Response response) {
        this.response = response;
        response.setRequest(this);
    }

    /**
     * 设置请求处理钩子（由连接器调用）
     */
    void setHook(ActionHook hook) {
        this.hook = hook;
    }

    /**
     * 触发特定操作码的处理
     */
    public void action(ActionCode actionCode, Object param) {
        if (hook != null) {
            hook.action(actionCode, Objects.requireNonNullElse(param, this));
        }
    }

    // -------------------- Cookies --------------------

    /**
     * 获取Cookie处理器
     */
    public ServerCookies getCookies() {
        return serverCookies;
    }

    // -------------------- Parameters --------------------

    /**
     * 获取请求参数处理器
     */
    public Parameters getParameters() {
        return parameters;
    }

    // 路径参数操作方法
    public void addPathParameter(String name, String value) {
        pathParameters.put(name, value);
    }

    public String getPathParameter(String name) {
        return pathParameters.get(name);
    }

    // -------------------- Other attributes --------------------
    // 使用notes存储内部数据（比ThreadLocal更高效）

    // 通用属性操作方法
    public void setAttribute(String name, Object o) {
        attributes.put(name, o);
    }

    public HashMap<String, Object> getAttributes() {
        return attributes;
    }

    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    // 认证信息访问器
    public MessageBytes getRemoteUser() {
        return remoteUser;
    }

    public boolean getRemoteUserNeedsAuthorization() {
        return remoteUserNeedsAuthorization;
    }

    public void setRemoteUserNeedsAuthorization(boolean remoteUserNeedsAuthorization) {
        this.remoteUserNeedsAuthorization = remoteUserNeedsAuthorization;
    }

    public MessageBytes getAuthType() {
        return authType;
    }

    // 其他请求状态相关方法
    public int getAvailable() {
        return available;
    }

    public void setAvailable(int available) {
        this.available = available;
    }

    public boolean getSendfile() {
        return sendfile;
    }

    public void setSendfile(boolean sendfile) {
        this.sendfile = sendfile;
    }

    /**
     * 检查请求体是否已完全读取
     */
    public boolean isFinished() {
        AtomicBoolean result = new AtomicBoolean(false);
        action(ActionCode.REQUEST_BODY_FULLY_READ, result);
        return result.get();
    }

    /**
     * 检查是否支持相对重定向（HTTP/1.1及以上版本支持）
     */
    public boolean getSupportsRelativeRedirects() {
        return !protocol().equals("") && !protocol().equals("HTTP/1.0");
    }

    // -------------------- Input Buffer --------------------

    /**
     * 获取请求体输入缓冲区
     */
    public InputBuffer getInputBuffer() {
        return inputBuffer;
    }

    /**
     * 设置请求体输入缓冲区
     */
    public void setInputBuffer(InputBuffer inputBuffer) {
        this.inputBuffer = inputBuffer;
    }

    /**
     * 从输入缓冲区读取数据到应用缓冲区
     *
     * 设计要点：
     * 1. 首次读取时发送ACK响应（如果需要）
     * 2. 直接操作底层缓冲区，避免数据复制（提高性能）
     * 3. 记录已读取的字节数用于统计
     *
     * @param handler 目标缓冲区处理器
     * @return 读取的字节数
     */
    public int doRead(ApplicationBufferHandler handler) throws IOException {
        if (getBytesRead() == 0 && !response.isCommitted()) {
            action(ActionCode.ACK, ContinueResponseTiming.ON_REQUEST_BODY_READ);
        }

        int n = inputBuffer.doRead(handler);
        if (n > 0) {
            bytesRead += n;
        }
        return n;
    }

    // -------------------- Error tracking --------------------

    /**
     * 设置请求处理过程中发生的异常
     */
    public void setErrorException(Exception ex) {
        errorException = ex;
    }

    /**
     * 获取请求处理过程中发生的异常
     */
    public Exception getErrorException() {
        return errorException;
    }

    /**
     * 检查是否有异常发生
     */
    public boolean isExceptionPresent() {
        return errorException != null;
    }

    // -------------------- debug --------------------

    /**
     * 获取请求唯一标识符
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * 获取协议层的请求ID（可能包含更多信息）
     */
    public String getProtocolRequestId() {
        AtomicReference<String> ref = new AtomicReference<>();
        hook.action(ActionCode.PROTOCOL_REQUEST_ID, ref);
        return ref.get();
    }

    /**
     * 获取Servlet连接对象
     */
    public ServletConnection getServletConnection() {
        AtomicReference<ServletConnection> ref = new AtomicReference<>();
        hook.action(ActionCode.SERVLET_CONNECTION, ref);
        return ref.get();
    }

    /**
     * 返回请求的字符串表示（主要用于调试）
     */
    @Override
    public String toString() {
        return "R( " + requestURI().toString() + ")";
    }

    /**
     * 获取请求开始时间（毫秒）
     */
    public long getStartTime() {
        return System.currentTimeMillis() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
    }

    /**
     * 设置请求开始时间（已弃用，Tomcat 11将移除）
     */
    @Deprecated
    public void setStartTime(long startTime) {
    }

    /**
     * 获取请求开始时间（纳秒）
     */
    public long getStartTimeNanos() {
        return startTimeNanos;
    }

    /**
     * 设置请求开始时间（纳秒）
     */
    public void setStartTimeNanos(long startTimeNanos) {
        this.startTimeNanos = startTimeNanos;
    }

    /**
     * 获取处理该请求的线程ID
     */
    public long getThreadId() {
        return threadId;
    }

    /**
     * 清除请求线程关联
     */
    public void clearRequestThread() {
        threadId = 0;
    }

    /**
     * 设置当前线程为处理该请求的线程
     */
    public void setRequestThread() {
        Thread t = Thread.currentThread();
        threadId = t.getId();
        getRequestProcessor().setWorkerThreadName(t.getName());
    }

    /**
     * 检查当前线程是否为处理该请求的线程
     */
    public boolean isRequestThread() {
        return Thread.currentThread().getId() == threadId;
    }

    // -------------------- Per-Request "notes" --------------------

    /**
     * 存储内部使用的"笔记"数据
     * 用于模块间高效传递数据（比ThreadLocal更快）
     *
     * 注意：
     * - 0-8索引保留给Servlet容器
     * - 9-16索引保留给连接器
     * - 17-31索引未分配
     */
    public void setNote(int pos, Object value) {
        notes[pos] = value;
    }

    /**
     * 获取指定索引的"笔记"数据
     */
    public Object getNote(int pos) {
        return notes[pos];
    }

    // -------------------- Recycling --------------------

    /**
     * 重置请求对象，准备复用
     *
     * 处理逻辑：
     * 1. 重置所有字段为初始状态
     * 2. 回收可复用对象（避免GC压力）
     * 3. 生成新的请求ID（如果请求已开始处理）
     */
    public void recycle() {
        bytesRead = 0;

        // 重置内容相关属性
        contentLength = -1;
        contentTypeMB = null;
        charset = null;
        characterEncoding = null;
        expectation = false;

        // 回收请求头和尾部字段
        headers.recycle();
        trailerFields.recycle();
        trailerFields.setLimit(MimeHeaders.DEFAULT_HEADER_SIZE);

        // 重置网络相关信息
        serverNameMB.recycle();
        serverPort = -1;
        localAddrMB.recycle();
        localNameMB.recycle();
        localPort = -1;
        peerAddrMB.recycle();
        remoteAddrMB.recycle();
        remoteHostMB.recycle();
        remotePort = -1;
        available = 0;
        sendfile = true;

        // 仅在请求已开始处理时生成新ID
        if (startTimeNanos != -1) {
            requestId = Long.toHexString(requestIdGenerator.getAndIncrement());
        }

        // 回收Cookie和参数处理器
        serverCookies.recycle();
        parameters.recycle();
        pathParameters.clear();

        // 重置请求行信息
        uriMB.recycle();
        decodedUriMB.recycle();
        queryMB.recycle();
        methodMB.recycle();
        protoMB.recycle();
        schemeMB.recycle();

        // 重置认证和属性
        remoteUser.recycle();
        remoteUserNeedsAuthorization = false;
        authType.recycle();
        attributes.clear();

        // 重置错误和异步状态
        errorException = null;
        listener = null;
        synchronized (nonBlockingStateLock) {
            fireListener = false;
            registeredForRead = false;
        }
        allDataReadEventSent.set(false);

        // 重置时间和线程信息
        startTimeNanos = -1;
        threadId = 0;

        // 对于非流水线处理器，清理钩子和输入缓冲区以帮助GC
        if (hook instanceof NonPipeliningProcessor) {
            setHook(null);
            setInputBuffer(null);
        }
    }

    // -------------------- Info --------------------

    /**
     * 更新性能计数器
     */
    public void updateCounters() {
        reqProcessorMX.updateCounters();
    }

    /**
     * 获取请求处理器信息（用于监控）
     */
    public RequestInfo getRequestProcessor() {
        return reqProcessorMX;
    }

    /**
     * 获取已读取的字节数
     */
    public long getBytesRead() {
        return bytesRead;
    }

    /**
     * 检查请求是否正在处理中
     */
    public boolean isProcessing() {
        return reqProcessorMX.getStage() == Constants.STAGE_SERVICE;
    }

    /**
     * 从Content-Type头解析字符编码
     *
     * 解析逻辑：
     * 1. 使用MediaType解析器处理Content-Type字符串
     * 2. 提取字符编码部分（如果存在）
     * 3. 解析失败时返回null
     */
    private static String getCharsetFromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }

        MediaType mediaType = null;
        try {
            mediaType = MediaType.parseMediaType(new StringReader(contentType));
        } catch (IOException e) {
            // 解析失败，返回null
        }
        if (mediaType != null) {
            return mediaType.getCharset();
        }

        return null;
    }
}