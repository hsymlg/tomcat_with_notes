/*
 * 版权声明：该代码受 Apache 软件基金会（ASF）许可，遵循 Apache License 2.0 协议。
 * 版权信息：贡献者许可协议详见随附的 NOTICE 文件，版权归属信息通过该文件提供。
 * 许可证链接：http://www.apache.org/licenses/LICENSE-2.0
 * 协议说明：在遵守许可证的前提下，软件按"原样"分发，不提供任何明示或暗示的保证。
 */
package org.apache.coyote.http11;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.ServletConnection;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ActionCode;
import org.apache.coyote.Adapter;
import org.apache.coyote.ContinueResponseTiming;
import org.apache.coyote.ErrorState;
import org.apache.coyote.Request;
import org.apache.coyote.RequestInfo;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.UpgradeToken;
import org.apache.coyote.http11.filters.BufferedInputFilter;
import org.apache.coyote.http11.filters.ChunkedInputFilter;
import org.apache.coyote.http11.filters.ChunkedOutputFilter;
import org.apache.coyote.http11.filters.GzipOutputFilter;
import org.apache.coyote.http11.filters.IdentityInputFilter;
import org.apache.coyote.http11.filters.IdentityOutputFilter;
import org.apache.coyote.http11.filters.SavedRequestInputFilter;
import org.apache.coyote.http11.filters.VoidInputFilter;
import org.apache.coyote.http11.filters.VoidOutputFilter;
import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.coyote.http11.upgrade.UpgradeApplicationBufferHandler;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.http.parser.TokenList;
import org.apache.tomcat.util.log.UserDataHelper;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.ApplicationBufferHandler;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SendfileDataBase;
import org.apache.tomcat.util.net.SendfileKeepAliveState;
import org.apache.tomcat.util.net.SendfileState;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.apache.tomcat.util.res.StringManager;

/**
 * HTTP/1.1 协议处理器
 * 负责处理 HTTP/1.1 请求的解析、处理和响应生成，实现了 HTTP 协议的核心逻辑，
 * 包括请求行解析、头部处理、连接管理、升级协议支持等功能。
 */
public class Http11Processor extends AbstractProcessor {

    // 日志记录器，用于记录处理器运行时的信息和错误
    private static final Log log = LogFactory.getLog(Http11Processor.class);

    // 字符串资源管理器，用于获取国际化的错误信息和提示
    private static final StringManager sm = StringManager.getManager(Http11Processor.class);


    /**
     * 协议处理器关联的 HTTP/1.1 协议实现
     * 用于获取协议配置参数和升级协议支持
     */
    private final AbstractHttp11Protocol<?> protocol;


    /**
     * 请求输入缓冲区
     * 负责读取和解析 HTTP 请求数据，包括请求行、头部和正文
     */
    private final Http11InputBuffer inputBuffer;


    /**
     * 响应输出缓冲区
     * 负责构建和发送 HTTP 响应数据，包括状态行、头部和正文
     */
    private final Http11OutputBuffer outputBuffer;


    /**
     * 可插拔过滤器索引
     * 用于跟踪内部过滤器数量，以便在查找可插拔过滤器时跳过它们
     */
    private final int pluggableFilterIndex;


    /**
     * 保持连接标志
     * 指示是否保持 HTTP 连接以处理多个请求
     */
    private volatile boolean keepAlive = true;


    /**
     * 套接字保持打开标志
     * 指示套接字是否应保持打开状态（如保持连接或文件发送）
     */
    private volatile boolean openSocket = false;


    /**
     * 请求头部读取完成标志
     * 指示请求头部是否已完全读取
     */
    private volatile boolean readComplete = true;

    /**
     * HTTP/1.1 协议标志
     * 指示当前是否使用 HTTP/1.1 协议
     */
    private boolean http11 = true;


    /**
     * HTTP/0.9 协议标志
     * 指示当前是否使用 HTTP/0.9 协议
     */
    private boolean http09 = false;


    /**
     * 请求内容定界标志
     * 指示请求内容是否有定界符（若为 false，请求结束后关闭连接）
     */
    private boolean contentDelimitation = true;


    /**
     * 协议升级令牌
     * 存储升级后的协议实例，用于处理协议升级（如 WebSocket）
     */
    private UpgradeToken upgradeToken = null;


    /**
     * 文件发送数据
     * 存储文件发送操作的相关数据
     */
    private SendfileDataBase sendfileData = null;


    /**
     * HTTP 解析器
     * 用于解析 HTTP 请求和响应的头部和内容
     */
    private final HttpParser httpParser;


    /**
     * 构造函数：初始化 HTTP/1.1 处理器
     *
     * @param protocol HTTP/1.1 协议实现
     * @param adapter  适配器，用于处理请求和响应
     */
    @SuppressWarnings("deprecation")
    public Http11Processor(AbstractHttp11Protocol<?> protocol, Adapter adapter) {
        super(adapter);
        this.protocol = protocol;

        // 获取或创建 HTTP 解析器
        HttpParser httpParser = protocol.getHttpParser();
        if (httpParser == null) {
            log.info(sm.getString("http11processor.noParser"));
            httpParser = new HttpParser(protocol.getRelaxedPathChars(), protocol.getRelaxedQueryChars());
        }
        this.httpParser = httpParser;

        // 初始化输入缓冲区并关联到请求对象
        inputBuffer = new Http11InputBuffer(request, protocol.getMaxHttpRequestHeaderSize(),
            protocol.getRejectIllegalHeader(), httpParser);
        request.setInputBuffer(inputBuffer);

        // 初始化输出缓冲区并关联到响应对象
        outputBuffer = new Http11OutputBuffer(response, protocol.getMaxHttpResponseHeaderSize());
        response.setOutputBuffer(outputBuffer);

        // 添加身份过滤器（不修改数据的过滤器）
        inputBuffer.addFilter(new IdentityInputFilter(protocol.getMaxSwallowSize()));
        outputBuffer.addFilter(new IdentityOutputFilter());

        // 添加分块编码过滤器（处理 chunked 编码）
        inputBuffer.addFilter(new ChunkedInputFilter(request, protocol.getMaxTrailerSize(),
            protocol.getAllowedTrailerHeadersInternal(), protocol.getMaxExtensionSize(),
            protocol.getMaxSwallowSize()));
        outputBuffer.addFilter(new ChunkedOutputFilter());

        // 添加空过滤器（忽略数据的过滤器）
        inputBuffer.addFilter(new VoidInputFilter());
        outputBuffer.addFilter(new VoidOutputFilter());

        // 添加缓冲输入过滤器（缓存输入数据）
        inputBuffer.addFilter(new BufferedInputFilter(protocol.getMaxSwallowSize()));

        // 添加 GZIP 输出过滤器（压缩响应数据）
        // inputBuffer.addFilter(new GzipInputFilter());
        outputBuffer.addFilter(new GzipOutputFilter());

        // 记录可插拔过滤器的起始索引
        pluggableFilterIndex = inputBuffer.getFilters().length;
    }


    /**
     * 判断 HTTP 状态码是否需要断开连接
     * 参考 Apache/httpd 的状态码列表
     *
     * @param status HTTP 状态码
     * @return true 表示需要断开连接，false 表示不需要
     */
    private static boolean statusDropsConnection(int status) {
        return status == 400 /* SC_BAD_REQUEST */ || status == 408 /* SC_REQUEST_TIMEOUT */ ||
            status == 411 /* SC_LENGTH_REQUIRED */ || status == 413 /* SC_REQUEST_ENTITY_TOO_LARGE */ ||
            status == 414 /* SC_REQUEST_URI_TOO_LONG */ || status == 500 /* SC_INTERNAL_SERVER_ERROR */ ||
            status == 503 /* SC_SERVICE_UNAVAILABLE */ || status == 501 /* SC_NOT_IMPLEMENTED */;
    }


    /**
     * 添加输入过滤器到当前请求
     * 若编码不支持，返回 501 响应
     *
     * @param inputFilters 输入过滤器数组
     * @param encodingName 编码名称
     */
    private void addInputFilter(InputFilter[] inputFilters, String encodingName) {
        if (contentDelimitation) {
            // 已指定分块编码，且必须是最终编码，返回 400 错误
            response.setStatus(400);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("http11processor.request.alreadyChunked", encodingName));
            }
            return;
        }

        // 解析编码名称（修剪并转为小写）
        if (encodingName.equals("chunked")) {
            inputBuffer.addActiveFilter(inputFilters[Constants.CHUNKED_FILTER]);
            contentDelimitation = true;
        } else {
            // 查找可插拔过滤器
            for (int i = pluggableFilterIndex; i < inputFilters.length; i++) {
                if (inputFilters[i].getEncodingName().toString().equals(encodingName)) {
                    inputBuffer.addActiveFilter(inputFilters[i]);
                    return;
                }
            }
            // 不支持的传输编码，返回 501 错误
            response.setStatus(501);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("http11processor.request.unsupportedEncoding", encodingName));
            }
        }
    }


    /**
     * 处理 HTTP 请求的核心方法
     * 解析请求、处理业务逻辑、生成响应，并管理连接状态
     *
     * @param socketWrapper 套接字包装器，用于 I/O 操作
     * @return 套接字状态，指示后续操作（如保持连接、关闭、升级等）
     * @throws IOException I/O 操作异常
     */
    @Override
    public SocketState service(SocketWrapperBase<?> socketWrapper) throws IOException {
        RequestInfo rp = request.getRequestProcessor();
        rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

        // 设置 I/O 相关资源
        setSocketWrapper(socketWrapper);

        // 重置标志位
        keepAlive = true;
        openSocket = false;
        readComplete = true;
        boolean keptAlive = false;
        SendfileState sendfileState = SendfileState.DONE;

        // 主处理循环：处理请求直到错误、连接关闭或协议升级
        while (!getErrorState().isError() && keepAlive && !isAsync() && upgradeToken == null &&
            sendfileState == SendfileState.DONE && !protocol.isPaused()) {

            // 解析请求头部
            try {
                if (!inputBuffer.parseRequestLine(keptAlive, protocol.getConnectionTimeout(),
                    protocol.getKeepAliveTimeout())) {
                    if (inputBuffer.getParsingRequestLinePhase() == -1) {
                        return SocketState.UPGRADING;
                    } else if (handleIncompleteRequestLineRead()) {
                        break;
                    }
                }

                // 处理请求行中的协议组件
                prepareRequestProtocol();

                if (protocol.isPaused()) {
                    // 服务暂停，返回 503 状态码
                    response.setStatus(503);
                    setErrorState(ErrorState.CLOSE_CLEAN, null);
                } else {
                    keptAlive = true;
                    // 设置请求头部的最大数量限制（可能通过 JMX 修改）
                    request.getMimeHeaders().setLimit(protocol.getMaxHeaderCount());
                    // HTTP/0.9 不需要解析头部
                    if (!http09 && !inputBuffer.parseHeaders()) {
                        // 已读取部分请求，不回收处理器，关联到套接字
                        openSocket = true;
                        readComplete = false;
                        break;
                    }
                    if (!protocol.getDisableUploadTimeout()) {
                        socketWrapper.setReadTimeout(protocol.getConnectionUploadTimeout());
                    }
                }
            } catch (IOException e) {
                // 解析头部异常，记录日志并设置错误状态
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.header.parse"), e);
                }
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
                break;
            } catch (Throwable t) {
                // 其他异常处理
                ExceptionUtils.handleThrowable(t);
                UserDataHelper.Mode logMode = userDataHelper.getNextMode();
                if (logMode != null) {
                    String message = sm.getString("http11processor.header.parse");
                    switch (logMode) {
                        case INFO_THEN_DEBUG:
                            message += sm.getString("http11processor.fallToDebug");
                            //$FALL-THROUGH$
                        case INFO:
                            log.info(message, t);
                            break;
                        case DEBUG:
                            log.debug(message, t);
                    }
                }
                // 返回 400 错误请求
                response.setStatus(400);
                setErrorState(ErrorState.CLOSE_CLEAN, t);
            }

            // 检查是否请求了协议升级
            if (isConnectionToken(request.getMimeHeaders(), "upgrade")) {
                String requestedProtocol = request.getHeader("Upgrade");

                UpgradeProtocol upgradeProtocol = protocol.getUpgradeProtocol(requestedProtocol);
                if (upgradeProtocol != null) {
                    if (upgradeProtocol.accept(request)) {
                        // 创建升级请求的克隆
                        Request upgradeRequest = null;
                        try {
                            upgradeRequest = cloneRequest(request);
                        } catch (ByteChunk.BufferOverflowException ioe) {
                            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                            setErrorState(ErrorState.CLOSE_CLEAN, null);
                        } catch (IOException ioe) {
                            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                            setErrorState(ErrorState.CLOSE_CLEAN, ioe);
                        }

                        if (upgradeRequest != null) {
                            // 完成 HTTP/1.1 升级流程
                            response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
                            response.setHeader("Connection", "Upgrade");
                            response.setHeader("Upgrade", requestedProtocol);
                            action(ActionCode.CLOSE, null);
                            getAdapter().log(request, response, 0);

                            // 使用新协议继续处理
                            InternalHttpUpgradeHandler upgradeHandler = upgradeProtocol
                                .getInternalUpgradeHandler(socketWrapper, getAdapter(), upgradeRequest);
                            UpgradeToken upgradeToken = new UpgradeToken(upgradeHandler, null, null, requestedProtocol);
                            action(ActionCode.UPGRADE, upgradeToken);
                            return SocketState.UPGRADING;
                        }
                    }
                }
            }

            if (getErrorState().isIoAllowed()) {
                // 设置过滤器并解析请求头部
                rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
                try {
                    prepareRequest();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("http11processor.request.prepare"), t);
                    }
                    // 返回 500 内部服务器错误
                    response.setStatus(500);
                    setErrorState(ErrorState.CLOSE_CLEAN, t);
                }
            }

            // 处理保持连接的最大请求数
            int maxKeepAliveRequests = protocol.getMaxKeepAliveRequests();
            if (maxKeepAliveRequests == 1) {
                keepAlive = false;
            } else if (maxKeepAliveRequests > 0 && socketWrapper.decrementKeepAlive() <= 0) {
                keepAlive = false;
            }

            // 通过适配器处理请求
            if (getErrorState().isIoAllowed()) {
                try {
                    rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
                    getAdapter().service(request, response);
                    // 处理响应提交后的错误
                    if (keepAlive && !getErrorState().isError() && !isAsync() &&
                        statusDropsConnection(response.getStatus())) {
                        setErrorState(ErrorState.CLOSE_CLEAN, null);
                    }
                } catch (InterruptedIOException e) {
                    // 中断的I/O异常，设置关闭连接状态
                    setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
                } catch (HeadersTooLargeException e) {
                    // 头部过大异常
                    log.error(sm.getString("http11processor.request.process"), e);
                    if (response.isCommitted()) {
                        setErrorState(ErrorState.CLOSE_NOW, e);
                    } else {
                        response.reset();
                        response.setStatus(500);
                        setErrorState(ErrorState.CLOSE_CLEAN, e);
                        response.setHeader("Connection", "close");
                    }
                } catch (Throwable t) {
                    // 其他异常处理
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("http11processor.request.process"), t);
                    response.setStatus(500);
                    setErrorState(ErrorState.CLOSE_CLEAN, t);
                    getAdapter().log(request, response, 0);
                }
            }

            // 完成请求处理
            rp.setStage(org.apache.coyote.Constants.STAGE_ENDINPUT);
            if (!isAsync()) {
                // 非异步请求时结束请求处理
                endRequest();
            }
            rp.setStage(org.apache.coyote.Constants.STAGE_ENDOUTPUT);

            // 错误处理：更新计数器和状态
            if (getErrorState().isError()) {
                response.setStatus(500);
            }

            if (!isAsync() || getErrorState().isError()) {
                request.updateCounters();
                if (getErrorState().isIoAllowed()) {
                    inputBuffer.nextRequest();
                    outputBuffer.nextRequest();
                }
            }

            // 恢复读取超时设置
            if (!protocol.getDisableUploadTimeout()) {
                int connectionTimeout = protocol.getConnectionTimeout();
                socketWrapper.setReadTimeout(Math.max(connectionTimeout, 0));
            }

            rp.setStage(org.apache.coyote.Constants.STAGE_KEEPALIVE);

            // 处理文件发送
            sendfileState = processSendfile(socketWrapper);
        }

        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        // 根据不同状态返回套接字状态
        if (getErrorState().isError() || (protocol.isPaused() && !isAsync())) {
            return SocketState.CLOSED;
        } else if (isAsync()) {
            return SocketState.LONG;
        } else if (isUpgrade()) {
            return SocketState.UPGRADING;
        } else {
            if (sendfileState == SendfileState.PENDING) {
                return SocketState.SENDFILE;
            } else {
                if (openSocket) {
                    if (readComplete) {
                        return SocketState.OPEN;
                    } else {
                        return SocketState.LONG;
                    }
                } else {
                    return SocketState.CLOSED;
                }
            }
        }
    }


    /**
     * 设置套接字包装器
     * 初始化输入输出缓冲区
     */
    @Override
    protected final void setSocketWrapper(SocketWrapperBase<?> socketWrapper) {
        super.setSocketWrapper(socketWrapper);
        inputBuffer.init(socketWrapper);
        outputBuffer.init(socketWrapper);
    }


    /**
     * 克隆请求对象
     * 用于协议升级时创建请求副本
     *
     * @param source 源请求对象
     * @return 克隆的请求对象
     * @throws IOException I/O异常
     */
    private Request cloneRequest(Request source) throws IOException {
        Request dest = new Request();

        // 复制请求的基本信息
        dest.decodedURI().duplicate(source.decodedURI());
        dest.method().duplicate(source.method());
        dest.getMimeHeaders().duplicate(source.getMimeHeaders());
        dest.requestURI().duplicate(source.requestURI());
        dest.queryString().duplicate(source.queryString());

        // 准备读取请求体
        MimeHeaders headers = source.getMimeHeaders();
        prepareExpectation(headers);
        prepareInputFilters(headers);
        ack(ContinueResponseTiming.ALWAYS);

        // 读取并缓冲请求体（若有）
        ByteChunk body = new ByteChunk();
        int maxSavePostSize = protocol.getMaxSavePostSize();
        if (maxSavePostSize != 0) {
            body.setLimit(maxSavePostSize);
            ApplicationBufferHandler buffer = new UpgradeApplicationBufferHandler();

            while (source.getInputBuffer().doRead(buffer) >= 0) {
                body.append(buffer.getByteBuffer());
            }
        }

        // 为升级协议提供缓冲的请求体
        SavedRequestInputFilter srif = new SavedRequestInputFilter(body);
        dest.setInputBuffer(srif);

        return dest;
    }


    /**
     * 处理不完整的请求行读取
     *
     * @return true 表示继续处理，false 表示错误
     */
    private boolean handleIncompleteRequestLineRead() {
        // 未完成读取，保持套接字打开
        openSocket = true;
        // 检查是否已开始读取请求行
        if (inputBuffer.getParsingRequestLinePhase() > 1) {
            // 已开始读取请求行
            if (protocol.isPaused()) {
                // 服务暂停，返回503状态码
                response.setStatus(503);
                setErrorState(ErrorState.CLOSE_CLEAN, null);
                return false;
            } else {
                // 保持处理器与套接字关联
                readComplete = false;
            }
        }
        return true;
    }


    /**
     * 检查期望请求和响应状态
     * 处理Expect: 100-continue场景
     */
    private void checkExpectationAndResponseStatus() {
        if (request.hasExpectation() && !isRequestBodyFullyRead() &&
            (response.getStatus() < 200 || response.getStatus() > 299)) {
            // 客户端发送了Expect: 100-continue但收到非2xx响应，禁用保持连接
            inputBuffer.setSwallowInput(false);
            keepAlive = false;
        }
    }


    /**
     * 检查最大吞咽大小
     * 防止读取过多请求体数据
     */
    private void checkMaxSwallowSize() {
        // 解析Content-Length头部
        long contentLength = -1;
        try {
            contentLength = request.getContentLengthLong();
        } catch (Exception e) {
            // 忽略异常，Content-Length保持-1
        }
        if (contentLength > 0 && protocol.getMaxSwallowSize() > -1 &&
            (contentLength - request.getBytesRead() > protocol.getMaxSwallowSize())) {
            // 剩余数据超过最大允许值，关闭连接
            keepAlive = false;
        }
    }


    /**
     * 准备请求协议版本
     * 解析请求行中的HTTP版本（1.1/1.0/0.9）
     */
    private void prepareRequestProtocol() {

        MessageBytes protocolMB = request.protocol();
        if (protocolMB.equals(Constants.HTTP_11)) {
            // HTTP/1.1协议
            http09 = false;
            http11 = true;
            protocolMB.setString(Constants.HTTP_11);
        } else if (protocolMB.equals(Constants.HTTP_10)) {
            // HTTP/1.0协议，默认不保持连接
            http09 = false;
            http11 = false;
            keepAlive = false;
            protocolMB.setString(Constants.HTTP_10);
        } else if (protocolMB.equals("")) {
            // HTTP/0.9协议，默认不保持连接
            http09 = true;
            http11 = false;
            keepAlive = false;
        } else {
            // 不支持的协议版本，返回505状态码
            http09 = false;
            http11 = false;
            response.setStatus(505);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("http11processor.request.unsupportedVersion", protocolMB));
            }
        }
    }


    /**
     * 准备请求处理
     * 解析请求头部、设置过滤器、验证主机等
     *
     * @throws IOException I/O异常
     */
    @SuppressWarnings("deprecation")
    private void prepareRequest() throws IOException {

        if (protocol.isSSLEnabled()) {
            // HTTPS请求设置scheme为https
            request.scheme().setString("https");
        }

        MimeHeaders headers = request.getMimeHeaders();

        // 检查Connection头部
        MessageBytes connectionValueMB = headers.getValue(Constants.CONNECTION);
        if (connectionValueMB != null && !connectionValueMB.isNull()) {
            Set<String> tokens = new HashSet<>();
            TokenList.parseTokenList(headers.values(Constants.CONNECTION), tokens);
            if (tokens.contains(Constants.CLOSE)) {
                // 客户端请求关闭连接
                keepAlive = false;
            } else if (tokens.contains(Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN)) {
                // 客户端请求保持连接
                keepAlive = true;
            }
        }

        if (http11) {
            // 处理Expect头部
            prepareExpectation(headers);
        }

        // 检查User-Agent头部（限制特定用户代理）
        Pattern restrictedUserAgents = protocol.getRestrictedUserAgentsPattern();
        if (restrictedUserAgents != null && (http11 || keepAlive)) {
            MessageBytes userAgentValueMB = headers.getValue("user-agent");
            if (userAgentValueMB != null && !userAgentValueMB.isNull()) {
                String userAgentValue = userAgentValueMB.toString();
                if (restrictedUserAgents.matcher(userAgentValue).matches()) {
                    // 用户代理被限制，降级协议并关闭连接
                    http11 = false;
                    keepAlive = false;
                }
            }
        }


        // 检查Host头部（HTTP/1.1必需）
        MessageBytes hostValueMB = null;
        try {
            hostValueMB = headers.getUniqueValue("host");
        } catch (IllegalArgumentException iae) {
            // 多个Host头部，返回400错误
            badRequest("http11processor.request.multipleHosts");
        }
        if (http11 && hostValueMB == null) {
            // 缺少Host头部，返回400错误
            badRequest("http11processor.request.noHostHeader");
        }

        // 处理绝对URI（请求行中包含完整URL）
        ByteChunk uriBC = request.requestURI().getByteChunk();
        byte[] uriB = uriBC.getBytes();
        if (uriBC.startsWithIgnoreCase("http", 0)) {
            int pos = 4;
            // 检查是否为https
            if (uriBC.startsWithIgnoreCase("s", pos)) {
                pos++;
            }
            // 检查是否包含"://"
            if (uriBC.startsWith("://", pos)) {
                pos += 3;
                int uriBCStart = uriBC.getStart();

                // 查找路径分隔符和用户信息分隔符
                int slashPos = uriBC.indexOf('/', pos);
                int atPos = uriBC.indexOf('@', pos);
                if (slashPos > -1 && atPos > slashPos) {
                    // '@'在路径中，无用户信息
                    atPos = -1;
                }

                if (slashPos == -1) {
                    // 无路径，设置URI为"/"
                    slashPos = uriBC.getLength();
                    request.requestURI().setBytes(uriB, uriBCStart + 6, 1);
                } else {
                    // 设置URI为路径部分
                    request.requestURI().setBytes(uriB, uriBCStart + slashPos, uriBC.getLength() - slashPos);
                }

                // 跳过用户信息
                if (atPos != -1) {
                    for (; pos < atPos; pos++) {
                        byte c = uriB[uriBCStart + pos];
                        if (!HttpParser.isUserInfo(c)) {
                            // 用户信息无效，返回400错误
                            badRequest("http11processor.request.invalidUserInfo");
                            break;
                        }
                    }
                    pos = atPos + 1;
                }

                if (http11) {
                    // 验证Host头部与请求行中的主机一致性
                    if (hostValueMB != null) {
                        if (!hostValueMB.getByteChunk().equalsIgnoreCase(uriB, uriBCStart + pos, slashPos - pos)) {
                            if (protocol.getAllowHostHeaderMismatch()) {
                                // 允许主机不匹配时，使用请求行中的主机
                                hostValueMB = headers.setValue("host");
                                hostValueMB.setBytes(uriB, uriBCStart + pos, slashPos - pos);
                            } else {
                                // 不允许主机不匹配，返回400错误
                                badRequest("http11processor.request.inconsistentHosts");
                            }
                        }
                    }
                } else {
                    // 非HTTP/1.1协议，生成Host头部
                    try {
                        hostValueMB = headers.setValue("host");
                        hostValueMB.setBytes(uriB, uriBCStart + pos, slashPos - pos);
                    } catch (IllegalStateException e) {
                        // 头部数量过多，忽略（无法处理）
                    }
                }
            } else {
                // 无效的协议方案，返回400错误
                badRequest("http11processor.request.invalidScheme");
            }
        }

        // 验证URI字符合法性
        for (int i = uriBC.getStart(); i < uriBC.getEnd(); i++) {
            if (!httpParser.isAbsolutePathRelaxed(uriB[i])) {
                badRequest("http11processor.request.invalidUri");
                break;
            }
        }

        // 设置输入过滤器
        prepareInputFilters(headers);

        // 解析主机名和端口
        parseHost(hostValueMB);

        if (!getErrorState().isIoAllowed()) {
            // 错误状态，记录访问日志
            getAdapter().log(request, response, 0);
        }
    }


    /**
     * 处理Expect头部
     * 解析Expect: 100-continue请求
     *
     * @param headers 请求头部
     */
    private void prepareExpectation(MimeHeaders headers) {
        MessageBytes expectMB = headers.getValue("expect");
        if (expectMB != null && !expectMB.isNull()) {
            if (expectMB.toString().trim().equalsIgnoreCase("100-continue")) {
                // 客户端请求100-continue响应
                request.setExpectation(true);
            } else {
                // 不支持的Expect值，返回417错误
                response.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
                setErrorState(ErrorState.CLOSE_CLEAN, null);
            }
        }
    }

    /**
     * 准备输入过滤器
     * 根据请求头部设置合适的输入过滤器（分块、身份、空等）
     *
     * @param headers 请求头部
     * @throws IOException I/O异常
     */
    private void prepareInputFilters(MimeHeaders headers) throws IOException {

        contentDelimitation = false;

        InputFilter[] inputFilters = inputBuffer.getFilters();

        // 解析Transfer-Encoding头部
        if (!http09) {
            MessageBytes transferEncodingValueMB = headers.getValue("transfer-encoding");
            if (transferEncodingValueMB != null) {
                List<String> encodingNames = new ArrayList<>();
                if (TokenList.parseTokenList(headers.values("transfer-encoding"), encodingNames)) {
                    for (String encodingName : encodingNames) {
                        addInputFilter(inputFilters, encodingName);
                    }
                } else {
                    // 无效的Transfer-Encoding，返回400错误
                    badRequest("http11processor.request.invalidTransferEncoding");
                }
            }
        }

        // 解析Content-Length头部
        long contentLength = -1;
        try {
            contentLength = request.getContentLengthLong();
        } catch (NumberFormatException e) {
            // 非数字Content-Length，返回400错误
            badRequest("http11processor.request.nonNumericContentLength");
        } catch (IllegalArgumentException e) {
            // 多个Content-Length头部，返回400错误
            badRequest("http11processor.request.multipleContentLength");
        }
        if (contentLength >= 0) {
            if (contentDelimitation) {
                // 分块编码与Content-Length同时存在，忽略Content-Length
                headers.removeHeader("content-length");
                request.setContentLength(-1);
                keepAlive = false;
            } else {
                // 使用身份过滤器，设置内容定界
                inputBuffer.addActiveFilter(inputFilters[Constants.IDENTITY_FILTER]);
                contentDelimitation = true;
            }
        }

        if (!contentDelimitation) {
            // 无Content-Length，使用空过滤器（假设无请求体）
            inputBuffer.addActiveFilter(inputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }
    }


    /**
     * 处理错误请求
     * 设置400状态码并记录错误
     *
     * @param errorKey 错误信息键
     */
    private void badRequest(String errorKey) {
        response.setStatus(400);
        setErrorState(ErrorState.CLOSE_CLEAN, null);
        if (log.isDebugEnabled()) {
            log.debug(sm.getString(errorKey));
        }
    }


    /**
     * 准备响应处理
     * 设置输出过滤器、构建响应头部、处理压缩和保持连接
     *
     * @throws IOException I/O异常
     */
    @Override
    protected final void prepareResponse() throws IOException {

        boolean entityBody = true;
        contentDelimitation = false;

        OutputFilter[] outputFilters = outputBuffer.getFilters();

        if (http09) {
            // HTTP/0.9协议，使用身份过滤器并提交响应
            outputBuffer.addActiveFilter(outputFilters[Constants.IDENTITY_FILTER]);
            outputBuffer.commit();
            return;
        }

        int statusCode = response.getStatus();
        if (statusCode < 200 || statusCode == 204 || statusCode == 205 || statusCode == 304) {
            // 无实体响应（如204、304），使用空过滤器
            outputBuffer.addActiveFilter(outputFilters[Constants.VOID_FILTER]);
            entityBody = false;
            contentDelimitation = true;
            if (statusCode == 205) {
                // RFC 7231要求205状态码必须显式设置Content-Length为0
                response.setContentLength(0);
            } else {
                response.setContentLength(-1);
            }
        }

        MessageBytes methodMB = request.method();
        boolean head = methodMB.equals("HEAD");
        if (head) {
            // HEAD请求，不发送实体内容，使用空过滤器
            outputBuffer.addActiveFilter(outputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        // 启用Sendfile支持（零拷贝文件传输）
        if (protocol.getUseSendfile()) {
            prepareSendfile(outputFilters);
        }

        // 检查是否启用压缩
        boolean useCompression = false;
        if (entityBody && sendfileData == null) {
            useCompression = protocol.useCompression(request, response);
        }

        MimeHeaders headers = response.getMimeHeaders();
        // 添加Content-Type和Content-Language头部
        if (entityBody || statusCode == HttpServletResponse.SC_NO_CONTENT) {
            String contentType = response.getContentType();
            if (contentType != null) {
                headers.setValue("Content-Type").setString(contentType);
            }
            String contentLanguage = response.getContentLanguage();
            if (contentLanguage != null) {
                headers.setValue("Content-Language").setString(contentLanguage);
            }
        }

        long contentLength = response.getContentLengthLong();
        boolean connectionClosePresent = isConnectionToken(headers, Constants.CLOSE);
        if (http11 && response.getTrailerFields() != null) {
            // 有尾部字段，使用分块编码
            outputBuffer.addActiveFilter(outputFilters[Constants.CHUNKED_FILTER]);
            contentDelimitation = true;
            headers.addValue(Constants.TRANSFERENCODING).setString(Constants.CHUNKED);
        } else if (contentLength != -1) {
            // 已知内容长度，设置Content-Length头部
            headers.setValue("Content-Length").setLong(contentLength);
            outputBuffer.addActiveFilter(outputFilters[Constants.IDENTITY_FILTER]);
            contentDelimitation = true;
        } else if (head) {
            // HEAD请求不设置Transfer-Encoding头部
        } else {
            // HTTP/1.1且无Content-Length，使用分块编码（除非有Connection: close）
            if (http11 && entityBody && !connectionClosePresent) {
                outputBuffer.addActiveFilter(outputFilters[Constants.CHUNKED_FILTER]);
                contentDelimitation = true;
                headers.addValue(Constants.TRANSFERENCODING).setString(Constants.CHUNKED);
            } else {
                outputBuffer.addActiveFilter(outputFilters[Constants.IDENTITY_FILTER]);
            }
        }

        if (useCompression) {
            // 添加Gzip压缩过滤器
            outputBuffer.addActiveFilter(outputFilters[Constants.GZIP_FILTER]);
        }

        // 添加Date头部（如果应用未设置）
        if (headers.getValue("Date") == null) {
            headers.addValue("Date").setString(FastHttpDateFormat.getCurrentDate());
        }

        // 处理响应实体和保持连接
        if ((entityBody) && (!contentDelimitation) || connectionClosePresent) {
            // 无内容定界或有Connection: close，禁用保持连接
            keepAlive = false;
        }

        // 检查Expect和响应状态
        checkExpectationAndResponseStatus();

        // 检查最大吞咽大小
        checkMaxSwallowSize();

        // 特定状态码需要关闭连接
        if (keepAlive && statusDropsConnection(statusCode)) {
            keepAlive = false;
        }
        if (!keepAlive) {
            // 添加Connection: close头部
            if (!connectionClosePresent) {
                headers.addValue(Constants.CONNECTION).setString(Constants.CLOSE);
            }
        } else if (!getErrorState().isError()) {
            if (!http11) {
                // HTTP/1.0协议，添加Connection: keep-alive头部
                headers.addValue(Constants.CONNECTION).setString(Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);
            }

            // 添加Keep-Alive头部（如果启用）
            if (protocol.getUseKeepAliveResponseHeader()) {
                boolean connectionKeepAlivePresent =
                    isConnectionToken(request.getMimeHeaders(), Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);

                if (connectionKeepAlivePresent) {
                    int keepAliveTimeout = protocol.getKeepAliveTimeout();

                    if (keepAliveTimeout > 0) {
                        String value = "timeout=" + keepAliveTimeout / 1000L;
                        headers.setValue(Constants.KEEP_ALIVE_HEADER_NAME).setString(value);

                        if (http11) {
                            // 为HTTP/1.1添加Connection: keep-alive头部
                            MessageBytes connectionHeaderValue = headers.getValue(Constants.CONNECTION);
                            if (connectionHeaderValue == null) {
                                headers.addValue(Constants.CONNECTION)
                                    .setString(Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);
                            } else {
                                connectionHeaderValue.setString(connectionHeaderValue.getString() + ", " +
                                    Constants.KEEP_ALIVE_HEADER_VALUE_TOKEN);
                            }
                        }
                    }
                }
            }
        }

        // 添加Server头部
        String server = protocol.getServer();
        if (server == null) {
            if (protocol.getServerRemoveAppProvidedValues()) {
                headers.removeHeader("server");
            }
        } else {
            headers.setValue("Server").setString(server);
        }

        // 写入响应头部并提交
        writeHeaders(response.getStatus(), headers);
        outputBuffer.commit();
    }


    /**
     * 写入响应头部
     * 将状态行和所有头部写入输出缓冲区
     *
     * @param status  响应状态码
     * @param headers 响应头部
     */
    private void writeHeaders(int status, MimeHeaders headers) {
        try {
            // 发送状态行
            outputBuffer.sendStatus(status);

            // 发送所有头部
            int size = headers.size();
            for (int i = 0; i < size; i++) {
                try {
                    outputBuffer.sendHeader(headers.getName(i), headers.getValue(i));
                } catch (IllegalArgumentException iae) {
                    // 无效头部，记录警告并移除
                    log.warn(sm.getString("http11processor.response.invalidHeader", headers.getName(i),
                        headers.getValue(i)), iae);
                    headers.removeHeader(i);
                    size--;
                    // 重置头部缓冲区并重试
                    outputBuffer.resetHeaderBuffer();
                    i = -1;
                    outputBuffer.sendStatus(status);
                }
            }
            // 结束头部
            outputBuffer.endHeaders();
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // 发生错误，重置头部缓冲区
            outputBuffer.resetHeaderBuffer();
            throw t;
        }
    }


    /**
     * 检查Connection头部是否包含特定令牌
     *
     * @param headers 头部集合
     * @param token   要查找的令牌
     * @return true 表示包含，false 表示不包含
     * @throws IOException I/O异常
     */
    private static boolean isConnectionToken(MimeHeaders headers, String token) throws IOException {
        MessageBytes connection = headers.getValue(Constants.CONNECTION);
        if (connection == null) {
            return false;
        }

        Set<String> tokens = new HashSet<>();
        TokenList.parseTokenList(headers.values(Constants.CONNECTION), tokens);
        return tokens.contains(token);
    }


    /**
     * 准备文件发送
     * 设置文件发送相关参数和过滤器
     *
     * @param outputFilters 输出过滤器数组
     */
    private void prepareSendfile(OutputFilter[] outputFilters) {
        String fileName = (String) request.getAttribute(org.apache.coyote.Constants.SENDFILE_FILENAME_ATTR);
        if (fileName == null) {
            sendfileData = null;
        } else {
            // 使用空过滤器（文件内容通过sendfile直接发送）
            outputBuffer.addActiveFilter(outputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
            long pos = ((Long) request.getAttribute(org.apache.coyote.Constants.SENDFILE_FILE_START_ATTR)).longValue();
            long end = ((Long) request.getAttribute(org.apache.coyote.Constants.SENDFILE_FILE_END_ATTR)).longValue();
            sendfileData = socketWrapper.createSendfileData(fileName, pos, end - pos);
        }
    }


    /**
     * 填充端口信息
     * 设置服务器端口为本地端口
     */
    @Override
    protected void populatePort() {
        // 确保本地端口字段已填充
        request.action(ActionCode.REQ_LOCALPORT_ATTRIBUTE, request);
        request.setServerPort(request.getLocalPort());
    }


    /**
     * 刷新缓冲写入
     * 尝试将输出缓冲区的数据写入套接字
     *
     * @return true 表示仍有数据需要写入，false 表示已全部写入
     * @throws IOException I/O异常
     */
    @Override
    protected boolean flushBufferedWrite() throws IOException {
        if (outputBuffer.hasDataToWrite()) {
            if (outputBuffer.flushBuffer(false)) {
                // 缓冲区未完全刷新，注册写事件
                outputBuffer.registerWriteInterest();
                return true;
            }
        }
        return false;
    }


    /**
     * 调度结束请求
     * 根据连接状态决定是否关闭或保持连接
     *
     * @return 套接字状态
     */
    @Override
    protected SocketState dispatchEndRequest() {
        if (!keepAlive || protocol.isPaused()) {
            return SocketState.CLOSED;
        } else {
            endRequest();
            inputBuffer.nextRequest();
            outputBuffer.nextRequest();
            if (socketWrapper.isReadPending()) {
                return SocketState.LONG;
            } else {
                return SocketState.OPEN;
            }
        }
    }


    /**
     * 获取日志记录器
     *
     * @return 日志记录器
     */
    @Override
    protected Log getLog() {
        return log;
    }


    /**
     * 获取Servlet连接
     *
     * @return Servlet连接对象
     */
    @Override
    protected ServletConnection getServletConnection() {
        return socketWrapper.getServletConnection("http/1.1", "");
    }


    /**
     * 结束请求处理
     * 完成请求处理，清理资源
     */
    private void endRequest() {
        if (getErrorState().isError()) {
            // 错误状态，不读取剩余输入
            inputBuffer.setSwallowInput(false);
        } else {
            // 检查期望请求和响应状态
            checkExpectationAndResponseStatus();
        }

        // 完成请求处理
        if (getErrorState().isIoAllowed()) {
            try {
                inputBuffer.endRequest();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                response.setStatus(500);
                setErrorState(ErrorState.CLOSE_NOW, t);
                log.error(sm.getString("http11processor.request.finish"), t);
            }
        }
        if (getErrorState().isIoAllowed()) {
            try {
                // 提交响应并结束输出
                action(ActionCode.COMMIT, null);
                outputBuffer.end();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                setErrorState(ErrorState.CLOSE_NOW, t);
                log.error(sm.getString("http11processor.response.finish"), t);
            }
        }
    }


    /**
     * 完成响应处理
     * 结束响应输出
     *
     * @throws IOException I/O异常
     */
    @Override
    protected final void finishResponse() throws IOException {
        outputBuffer.end();
    }


    /**
     * 确认请求
     * 发送100 Continue响应
     *
     * @param continueResponseTiming 继续响应的时机
     */
    @Override
    protected final void ack(ContinueResponseTiming continueResponseTiming) {
        // 根据配置时机发送100 Continue响应
        if (continueResponseTiming == ContinueResponseTiming.ALWAYS ||
            continueResponseTiming == protocol.getContinueResponseTimingInternal()) {
            // 响应未提交且客户端请求了100-continue
            if (!response.isCommitted() && request.hasExpectation()) {
                try {
                    outputBuffer.sendAck();
                } catch (IOException e) {
                    setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
                }
            }
        }
    }


    /**
     * 发送103 Early Hints响应
     * 用于预加载资源提示
     *
     * @throws IOException I/O异常
     */
    @Override
    protected void earlyHints() throws IOException {
        writeHeaders(103, response.getMimeHeaders());
        outputBuffer.writeHeaders();
        outputBuffer.resetHeaderBuffer();
    }


    /**
     * 刷新输出缓冲区
     *
     * @throws IOException I/O异常
     */
    @Override
    protected final void flush() throws IOException {
        outputBuffer.flush();
    }


    /**
     * 获取可用字节数
     *
     * @param doRead 是否执行读取操作
     * @return 可用字节数
     */
    @Override
    protected final int available(boolean doRead) {
        return inputBuffer.available(doRead);
    }


    /**
     * 设置请求体
     *
     * @param body 请求体字节块
     */
    @Override
    protected final void setRequestBody(ByteChunk body) {
        InputFilter savedBody = new SavedRequestInputFilter(body);
        Http11InputBuffer internalBuffer = (Http11InputBuffer) request.getInputBuffer();
        internalBuffer.addActiveFilter(savedBody);
    }


    /**
     * 设置吞咽响应
     * 标记响应已完成
     */
    @Override
    protected final void setSwallowResponse() {
        outputBuffer.responseFinished = true;
    }


    /**
     * 禁用吞咽请求
     * 停止读取请求体
     */
    @Override
    protected final void disableSwallowRequest() {
        inputBuffer.setSwallowInput(false);
    }


    /**
     * 执行SSL重新握手
     * 用于处理需要重新验证的安全连接
     *
     * @throws IOException I/O异常
     */
    @Override
    protected final void sslReHandShake() throws IOException {
        if (sslSupport != null) {
            // 消费并缓冲请求体，避免干扰客户端握手消息
            InputFilter[] inputFilters = inputBuffer.getFilters();
            ((BufferedInputFilter) inputFilters[Constants.BUFFERED_FILTER]).setLimit(protocol.getMaxSavePostSize());
            inputBuffer.addActiveFilter(inputFilters[Constants.BUFFERED_FILTER]);

            // 执行客户端认证
            socketWrapper.doClientAuth(sslSupport);
            try {
                // 获取并设置客户端证书链
                Object sslO = sslSupport.getPeerCertificateChain();
                if (sslO != null) {
                    request.setAttribute(SSLSupport.CERTIFICATE_KEY, sslO);
                }
            } catch (IOException ioe) {
                log.warn(sm.getString("http11processor.socket.ssl"), ioe);
            }
        }
    }


    /**
     * 检查请求体是否已完全读取
     *
     * @return true 表示已完全读取，false 表示未完全读取
     */
    @Override
    protected final boolean isRequestBodyFullyRead() {
        return inputBuffer.isFinished();
    }


    /**
     * 注册读取兴趣
     * 通知套接字有读取操作需要处理
     */
    @Override
    protected final void registerReadInterest() {
        socketWrapper.registerReadInterest();
    }


    /**
     * 检查是否准备好写入
     *
     * @return true 表示准备好，false 表示未准备好
     */
    @Override
    protected final boolean isReadyForWrite() {
        return outputBuffer.isReady();
    }


    /**
     * 获取协议升级令牌
     *
     * @return 升级令牌
     */
    @Override
    public UpgradeToken getUpgradeToken() {
        return upgradeToken;
    }


    /**
     * 执行HTTP协议升级
     *
     * @param upgradeToken 升级令牌
     */
    @Override
    protected final void doHttpUpgrade(UpgradeToken upgradeToken) {
        this.upgradeToken = upgradeToken;
        // 停止进一步的HTTP输出
        outputBuffer.responseFinished = true;
    }


    /**
     * 获取剩余输入数据
     *
     * @return 剩余输入数据的ByteBuffer
     */
    @Override
    public ByteBuffer getLeftoverInput() {
        return inputBuffer.getLeftover();
    }


    /**
     * 检查是否已升级协议
     *
     * @return true 表示已升级，false 表示未升级
     */
    @Override
    public boolean isUpgrade() {
        return upgradeToken != null;
    }


    /**
     * 检查尾部字段是否准备好
     *
     * @return true 表示准备好，false 表示未准备好
     */
    @Override
    protected boolean isTrailerFieldsReady() {
        if (inputBuffer.isChunking()) {
            return inputBuffer.isFinished();
        } else {
            return true;
        }
    }


    /**
     * 检查是否支持尾部字段
     *
     * @return true 表示支持，false 表示不支持
     */
    @Override
    protected boolean isTrailerFieldsSupported() {
        // 必须是HTTP/1.1协议才能支持尾部字段
        if (!http11) {
            return false;
        }

        // 响应未提交时，可以使用分块编码并发送尾部字段
        if (!response.isCommitted()) {
            return true;
        }

        // 响应已提交，检查是否使用分块编码
        return outputBuffer.isChunking();
    }


    /**
     * 处理文件发送操作
     *
     * @param socketWrapper 套接字包装器
     * @return 文件发送状态
     */
    private SendfileState processSendfile(SocketWrapperBase<?> socketWrapper) {
        openSocket = keepAlive;
        SendfileState result = SendfileState.DONE;
        // 执行文件发送操作
        if (sendfileData != null && !getErrorState().isError()) {
            if (keepAlive) {
                if (available(false) == 0) {
                    sendfileData.keepAliveState = SendfileKeepAliveState.OPEN;
                } else {
                    sendfileData.keepAliveState = SendfileKeepAliveState.PIPELINED;
                }
            } else {
                sendfileData.keepAliveState = SendfileKeepAliveState.NONE;
            }
            result = socketWrapper.processSendfile(sendfileData);
            if (Objects.requireNonNull(result) == SendfileState.ERROR) {
                // 文件发送失败
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.sendfile.error"));
                }
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, null);
            }
            sendfileData = null;
        }
        return result;
    }


    /**
     * 回收处理器资源
     * 重置状态，释放资源
     */
    @Override
    public final void recycle() {
        getAdapter().checkRecycled(request, response);
        super.recycle();
        inputBuffer.recycle();
        outputBuffer.recycle();
        upgradeToken = null;
        socketWrapper = null;
        sendfileData = null;
        sslSupport = null;
    }


    /**
     * 暂停处理器
     * HTTP处理器不支持暂停操作
     */
    @Override
    public void pause() {
        // HTTP协议不支持暂停操作
    }
}