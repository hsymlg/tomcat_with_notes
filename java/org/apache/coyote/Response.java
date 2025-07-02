/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.coyote;

import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import jakarta.servlet.WriteListener;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.MediaType;
import org.apache.tomcat.util.res.StringManager;

/**
 * Tomcat内部响应处理的核心类，负责高效构建和输出HTTP响应
 * <p>
 * 设计特点：
 * 1. 延迟提交机制：允许在输出内容前修改响应头
 * 2. 异步处理支持：通过WriteListener实现非阻塞输出
 * 3. 错误状态机：精确跟踪响应错误状态
 * 4. 内容协商支持：处理字符集、语言等国际化参数
 * <p>
 * 使用限制：
 * - 非公共API，仅供Tomcat内部使用
 * - 应用层代码通过HttpServletResponse接口访问响应功能
 */
public final class Response {

    // 字符串资源管理器，用于加载国际化错误信息
    private static final StringManager sm = StringManager.getManager(Response.class);
    // 日志记录器
    private static final Log log = LogFactory.getLog(Response.class);

    // ----------------------------------------------------- Class Variables

    /**
     * 默认语言环境（遵循规范要求）
     * 当用户未指定时使用系统默认Locale
     */
    private static final Locale DEFAULT_LOCALE = Locale.getDefault();

    // ----------------------------------------------------- Instance Variables

    /**
     * HTTP状态码
     * 初始值为200（OK）
     */
    int status = 200;

    /**
     * 状态码对应的消息文本
     * 例如"OK"、"Not Found"等
     */
    String message = null;

    /**
     * 响应头集合
     * 使用MimeHeaders高效处理HTTP头字段
     */
    final MimeHeaders headers = new MimeHeaders();

    /**
     * 分块传输的尾部字段供给器
     * 用于处理HTTP/1.1分块传输的尾部头
     */
    private Supplier<Map<String, String>> trailerFieldsSupplier = null;

    /**
     * 输出缓冲区
     * 实际类型取决于具体的协议实现（如HTTP/1.1或HTTP/2）
     */
    OutputBuffer outputBuffer;

    /**
     * 内部存储区域，用于模块间传递数据
     * 注：索引0-8保留给Servlet容器，9-16保留给连接器
     */
    final Object[] notes = new Object[Constants.MAX_NOTES];

    /**
     * 已提交标记
     * 表示响应头和状态码是否已发送到客户端
     */
    volatile boolean committed = false;

    /**
     * 响应处理钩子
     * 由连接器设置，用于触发底层协议操作
     */
    volatile ActionHook hook;

    /**
     * HTTP响应内容相关属性
     */
    String contentType = null;          // 内容类型
    String contentLanguage = null;      // 内容语言
    Charset charset = null;             // 字符集（解析后）
    String characterEncoding = null;    // 原始字符编码（保留用户指定值）
    long contentLength = -1;            // 内容长度（-1表示未指定）
    private Locale locale = DEFAULT_LOCALE;  // 语言环境

    // 性能统计和状态跟踪
    private long contentWritten = 0;     // 应用层写入的字节数
    private long commitTimeNanos = -1;   // 响应提交时间（纳秒）

    /**
     * 响应写入过程中发生的异常
     * 用于跟踪和报告响应处理中的错误
     */
    private Exception errorException = null;

    /**
     * 错误状态机（原子整数实现）
     * 状态定义：
     * 0 - NONE（无错误）
     * 1 - NOT_REPORTED（错误已发生但未报告）
     * 2 - REPORTED（错误已报告）
     */
    private final AtomicInteger errorState = new AtomicInteger(0);

    // 关联的请求对象
    Request req;

    // ------------------------------------------------------------- Properties

    /**
     * 获取关联的请求对象
     */
    public Request getRequest() {
        return req;
    }

    /**
     * 设置关联的请求对象
     * 双向关联，响应会同时设置自身到请求中
     */
    public void setRequest(Request req) {
        this.req = req;
    }

    /**
     * 设置输出缓冲区
     * 由协议处理器调用，指定实际的输出实现
     */
    public void setOutputBuffer(OutputBuffer outputBuffer) {
        this.outputBuffer = outputBuffer;
    }

    /**
     * 获取响应头集合
     */
    public MimeHeaders getMimeHeaders() {
        return headers;
    }

    /**
     * 设置响应处理钩子
     * 由连接器调用，用于触发底层操作
     */
    void setHook(ActionHook hook) {
        this.hook = hook;
    }

    // -------------------- Per-Response "notes" --------------------

    /**
     * 存储内部使用的"笔记"数据
     * 用于模块间高效传递数据（比ThreadLocal更快）
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

    // -------------------- Actions --------------------

    /**
     * 触发特定操作码的处理
     * 通过钩子机制委托给底层协议处理器
     */
    public void action(ActionCode actionCode, Object param) {
        if (hook != null) {
            hook.action(actionCode, Objects.requireNonNullElse(param, this));
        }
    }

    // -------------------- State --------------------

    /**
     * 获取响应状态码
     */
    public int getStatus() {
        return status;
    }

    /**
     * 设置响应状态码
     * 允许在响应提交前修改状态
     */
    public void setStatus(int status) {
        this.status = status;
    }

    /**
     * 获取状态码对应的消息文本
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置状态码对应的消息文本
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 检查响应是否已提交
     * 提交后无法修改头信息和状态码
     */
    public boolean isCommitted() {
        return committed;
    }

    /**
     * 设置响应提交状态
     * 首次提交时记录提交时间
     */
    public void setCommitted(boolean v) {
        if (v && !this.committed) {
            this.commitTimeNanos = System.nanoTime();
        }
        this.committed = v;
    }

    /**
     * 获取响应提交时间（毫秒）
     */
    public long getCommitTime() {
        return System.currentTimeMillis() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - commitTimeNanos);
    }

    /**
     * 获取响应提交时间（纳秒）
     */
    public long getCommitTimeNanos() {
        return commitTimeNanos;
    }

    // -----------------Error State --------------------

    /**
     * 设置响应处理过程中发生的异常
     * 仅在异常未设置时更新（保证异常唯一性）
     */
    public void setErrorException(Exception ex) {
        if (errorException == null) {
            errorException = ex;
        }
    }

    /**
     * 获取响应处理过程中发生的异常
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

    /**
     * 设置错误标志（原子操作）
     *
     * @return false表示错误标志已被设置
     * @deprecated Tomcat 11将改为无返回值
     */
    @Deprecated
    public boolean setError() {
        return errorState.compareAndSet(0, 1);
    }

    /**
     * 检查是否发生错误
     */
    public boolean isError() {
        return errorState.get() > 0;
    }

    /**
     * 检查是否需要报告错误
     * 错误已发生但尚未报告时返回true
     */
    public boolean isErrorReportRequired() {
        return errorState.get() == 1;
    }

    /**
     * 标记错误已报告
     *
     * @return true表示成功更新状态
     */
    public boolean setErrorReported() {
        return errorState.compareAndSet(1, 2);
    }

    /**
     * 重置错误状态
     * 将状态机重置为初始状态
     */
    public void resetError() {
        errorState.set(0);
    }

    // -------------------- Methods --------------------

    /**
     * 重置响应（未提交时可用）
     *
     * @throws IllegalStateException 如果响应已提交
     */
    public void reset() throws IllegalStateException {
        if (committed) {
            throw new IllegalStateException();
        }
        recycle(false);
    }

    // -------------------- Headers --------------------

    /**
     * 检查响应是否包含指定头字段
     * 注意：对于Content-Type和Content-Length始终返回false
     */
    public boolean containsHeader(String name) {
        return headers.getHeader(name) != null;
    }

    /**
     * 设置响应头字段
     * 特殊头字段（如Content-Type）会被特殊处理
     */
    public void setHeader(String name, String value) {
        char cc = name.charAt(0);
        if (cc == 'C' || cc == 'c') {
            if (checkSpecialHeader(name, value)) {
                return;
            }
        }
        headers.setValue(name).setString(value);
    }

    /**
     * 添加响应头字段（支持重复头）
     */
    public void addHeader(String name, String value) {
        addHeader(name, value, null);
    }

    /**
     * 添加响应头字段（支持指定字符集）
     */
    public void addHeader(String name, String value, Charset charset) {
        char cc = name.charAt(0);
        if (cc == 'C' || cc == 'c') {
            if (checkSpecialHeader(name, value)) {
                return;
            }
        }
        MessageBytes mb = headers.addValue(name);
        if (charset != null) {
            mb.setCharset(charset);
        }
        mb.setString(value);
    }

    /**
     * 设置尾部字段供给器
     * 用于HTTP分块传输的尾部头
     */
    public void setTrailerFields(Supplier<Map<String, String>> supplier) {
        AtomicBoolean trailerFieldsSupported = new AtomicBoolean(false);
        action(ActionCode.IS_TRAILER_FIELDS_SUPPORTED, trailerFieldsSupported);
        if (!trailerFieldsSupported.get()) {
            throw new IllegalStateException(sm.getString("response.noTrailers.notSupported"));
        }
        this.trailerFieldsSupplier = supplier;
    }

    /**
     * 获取尾部字段供给器
     */
    public Supplier<Map<String, String>> getTrailerFields() {
        return trailerFieldsSupplier;
    }

    /**
     * 处理特殊头字段（Content-Type/Content-Length）
     *
     * @return true表示已处理特殊头，无需添加到headers
     */
    private boolean checkSpecialHeader(String name, String value) {
        if (name.equalsIgnoreCase("Content-Type")) {
            setContentType(value);
            return true;
        }
        if (name.equalsIgnoreCase("Content-Length")) {
            try {
                long cL = Long.parseLong(value);
                setContentLength(cL);
                return true;
            } catch (NumberFormatException ex) {
                // 忽略格式错误，交由上层处理
                return false;
            }
        }
        return false;
    }

    /**
     * 提交响应头（已弃用）
     * 等效于调用commit()，Tomcat 11将移除
     */
    @Deprecated
    public void sendHeaders() {
        commit();
    }

    /**
     * 提交响应
     * 触发底层协议提交头信息，并标记为已提交
     */
    public void commit() {
        action(ActionCode.COMMIT, this);
        setCommitted(true);
    }

    // -------------------- I18N --------------------

    /**
     * 获取响应语言环境
     */
    public Locale getLocale() {
        return locale;
    }

    /**
     * 设置响应语言环境
     * 同时更新Content-Language头字段
     */
    public void setLocale(Locale locale) {
        if (locale == null) {
            this.locale = null;
            this.contentLanguage = null;
            return;
        }
        this.locale = locale;
        this.contentLanguage = locale.toLanguageTag();
    }

    /**
     * 获取Content-Language头字段值
     */
    public String getContentLanguage() {
        return contentLanguage;
    }

    /**
     * 设置响应字符编码
     * 必须在使用getWriter()前调用
     *
     * @throws UnsupportedEncodingException 当指定编码不支持时
     */
    public void setCharacterEncoding(String characterEncoding) throws UnsupportedEncodingException {
        if (isCommitted()) {
            return;
        }
        if (characterEncoding == null) {
            this.charset = null;
            this.characterEncoding = null;
            return;
        }
        this.characterEncoding = characterEncoding;
        this.charset = B2CConverter.getCharset(characterEncoding);
    }

    /**
     * 获取响应字符集
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * 获取响应字符编码名称
     */
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    /**
     * 设置响应内容类型
     * 自动处理字符集部分，保留用户指定的编码
     */
    public void setContentType(String type) {
        if (type == null) {
            this.contentType = null;
            return;
        }
        MediaType m = null;
        try {
            m = MediaType.parseMediaType(new StringReader(type));
        } catch (IOException e) {
            // 解析失败时直接使用原始类型
        }
        if (m == null) {
            this.contentType = type;
            return;
        }
        String charsetValue = m.getCharset();
        if (charsetValue == null) {
            // 无字符集时直接使用原始类型
            this.contentType = type;
        } else {
            // 有字符集时重建不含charset的类型，并解析字符集
            this.contentType = m.toStringNoCharset();
            charsetValue = charsetValue.trim();
            if (!charsetValue.isEmpty()) {
                try {
                    charset = B2CConverter.getCharset(charsetValue);
                } catch (UnsupportedEncodingException e) {
                    log.warn(sm.getString("response.encoding.invalid", charsetValue), e);
                }
            }
        }
    }

    /**
     * 设置不含字符集的内容类型
     * 不解析字符集部分，直接使用原始值
     */
    public void setContentTypeNoCharset(String type) {
        this.contentType = type;
    }

    /**
     * 获取完整的Content-Type头字段值
     * 包含字符集部分（如果有）
     */
    public String getContentType() {
        String ret = contentType;
        if (ret != null && charset != null) {
            ret = ret + ";charset=" + characterEncoding;
        }
        return ret;
    }

    /**
     * 设置响应内容长度
     */
    public void setContentLength(long contentLength) {
        this.contentLength = contentLength;
    }

    /**
     * 获取响应内容长度（整数形式）
     * 超过Integer.MAX_VALUE时返回-1
     */
    public int getContentLength() {
        long length = getContentLengthLong();
        if (length < Integer.MAX_VALUE) {
            return (int) length;
        }
        return -1;
    }

    /**
     * 获取响应内容长度（长整型形式）
     */
    public long getContentLengthLong() {
        return contentLength;
    }

    /**
     * 写入响应内容
     * 从ByteBuffer中读取数据并写入输出缓冲区
     *
     * @param chunk 包含响应数据的ByteBuffer
     * @throws IOException 当写入发生I/O错误时抛出
     */
    public void doWrite(ByteBuffer chunk) throws IOException {
        int len = chunk.remaining();
        outputBuffer.doWrite(chunk);
        contentWritten += len - chunk.remaining();
    }

    /**
     * 重置响应对象（准备复用）
     * 清空状态并回收资源
     */
    public void recycle() {
        recycle(true);
    }

    /**
     * 响应对象回收核心逻辑
     *
     * @param responseComplete 是否为完整响应回收（影响钩子和缓冲区清理）
     */
    private void recycle(boolean responseComplete) {
        // 重置内容相关属性
        contentType = null;
        contentLanguage = null;
        locale = DEFAULT_LOCALE;
        charset = null;
        characterEncoding = null;
        contentLength = -1;

        // 重置状态码和提交状态
        status = 200;
        message = null;
        committed = false;
        commitTimeNanos = -1;

        // 重置错误相关状态
        errorException = null;
        resetError();

        // 回收响应头和尾部字段
        headers.recycle();
        trailerFieldsSupplier = null;

        // 重置异步写状态
        listener = null;
        synchronized (nonBlockingStateLock) {
            fireListener = false;
            registeredForWrite = false;
        }

        // 更新性能统计
        contentWritten = 0;

        // 对于非流水线处理器，清理钩子和输出缓冲区以帮助GC
        if (responseComplete && hook instanceof NonPipeliningProcessor) {
            setHook(null);
            setOutputBuffer(null);
        }
    }

    /**
     * 获取应用层写入的字节数
     * 该值为应用程序写入的原始数据量，不包含协议处理添加的数据
     */
    public long getContentWritten() {
        return contentWritten;
    }

    /**
     * 获取实际写入网络的字节数
     * 包含协议处理（如压缩、分块）添加的数据
     *
     * @param flush 是否先刷新缓冲区
     * @return 实际写入网络的字节数
     */
    public long getBytesWritten(boolean flush) {
        if (flush) {
            action(ActionCode.CLIENT_FLUSH, this);
        }
        return outputBuffer.getBytesWritten();
    }

    /*
     * 非阻塞输出状态管理
     * 这些变量用于协调异步写入操作和事件触发
     */
    volatile WriteListener listener;               // 异步写入监听器
    private boolean fireListener = false;         // 是否触发监听器
    private boolean registeredForWrite = false;   // 是否已注册写入事件
    private final Object nonBlockingStateLock = new Object();  // 状态锁

    /**
     * 获取异步写入监听器
     */
    public WriteListener getWriteListener() {
        return listener;
    }

    /**
     * 设置异步写入监听器
     * <p>
     * 处理逻辑：
     * 1. 验证监听器不为空且未设置过
     * 2. 确认响应处于异步模式
     * 3. 初始化监听器并根据当前状态决定是否立即触发
     */
    public void setWriteListener(WriteListener listener) {
        if (listener == null) {
            throw new NullPointerException(sm.getString("response.nullWriteListener"));
        }
        if (getWriteListener() != null) {
            throw new IllegalStateException(sm.getString("response.writeListenerSet"));
        }
        // 验证响应是否处于异步模式
        AtomicBoolean result = new AtomicBoolean(false);
        action(ActionCode.ASYNC_IS_ASYNC, result);
        if (!result.get()) {
            throw new IllegalStateException(sm.getString("response.notAsync"));
        }

        this.listener = listener;

        // 容器负责首次调用onWritePossible()
        // 如果isReady()返回true，需在新线程中触发监听器
        // 如果isReady()返回false，注册写入事件，可写时触发监听器
        if (isReady()) {
            synchronized (nonBlockingStateLock) {
                // 确保不重复注册写入事件
                registeredForWrite = true;
                // 设置触发标志，确保容器尝试触发onWritePossible()时有效
                fireListener = true;
            }
            action(ActionCode.DISPATCH_WRITE, null);
            if (!req.isRequestThread()) {
                // 不在容器线程上，需要执行调度
                action(ActionCode.DISPATCH_EXECUTE, null);
            }
        }
    }

    /**
     * 检查是否可以立即写入数据
     * <p>
     * 返回值说明：
     * - true：可以立即写入数据
     * - false：不能立即写入，已注册写入事件，可写时会触发监听器
     */
    public boolean isReady() {
        if (listener == null) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("response.notNonBlocking"));
            }
            return false;
        }
        boolean ready;
        synchronized (nonBlockingStateLock) {
            if (registeredForWrite) {
                fireListener = true;
                return false;
            }
            ready = checkRegisterForWrite();
            fireListener = !ready;
        }
        return ready;
    }

    /**
     * 检查并注册写入事件
     * <p>
     * 返回值说明：
     * - true：数据已就绪，可以写入
     * - false：数据未就绪，已注册写入事件
     */
    public boolean checkRegisterForWrite() {
        AtomicBoolean ready = new AtomicBoolean(false);
        synchronized (nonBlockingStateLock) {
            if (!registeredForWrite) {
                action(ActionCode.NB_WRITE_INTEREST, ready);
                registeredForWrite = !ready.get();
            }
        }
        return ready.get();
    }

    /**
     * 数据可写时的回调方法
     * 由底层I/O系统在可写时调用
     */
    public void onWritePossible() throws IOException {
        boolean fire = false;
        synchronized (nonBlockingStateLock) {
            registeredForWrite = false;
            if (fireListener) {
                fireListener = false;
                fire = true;
            }
        }
        if (fire) {
            listener.onWritePossible();
        }
    }
}