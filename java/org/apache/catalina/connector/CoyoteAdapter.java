/*
 * 版权声明：该文件由Apache软件基金会（ASF）授权，遵循Apache License 2.0协议。
 * 未经授权不得擅自使用，如需获取更多信息请查看NOTICE文件或访问官网。
 */
package org.apache.catalina.connector;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.ReadListener;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Authenticator;
import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Wrapper;
import org.apache.catalina.authenticator.AuthenticatorBase;
import org.apache.catalina.core.AsyncContextImpl;
import org.apache.catalina.util.ServerInfo;
import org.apache.catalina.util.SessionConfig;
import org.apache.catalina.util.URLEncoder;
import org.apache.coyote.ActionCode;
import org.apache.coyote.Adapter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.*;
import org.apache.tomcat.util.http.ServerCookie;
import org.apache.tomcat.util.http.ServerCookies;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.res.StringManager;


/**
 * CoyoteAdapter类实现：作为Coyote协议处理器与Tomcat容器之间的适配器，
 * 负责将Coyote请求/响应转换为Servlet规范的Request/Response，并处理请求分发。
 *
 * @author Craig R. McClanahan, Remy Maucherat 等开发者
 */
public class CoyoteAdapter implements Adapter {

    private static final Log log = LogFactory.getLog(CoyoteAdapter.class);

    // 常量定义
    private static final String POWERED_BY = "Servlet/6.0 JSP/3.1 " + "(" + ServerInfo.getServerInfo() + " Java/" +
        System.getProperty("java.vm.vendor") + "/" + System.getProperty("java.runtime.version") + ")";
    // 仅使用SSL进行会话跟踪的模式集合
    private static final EnumSet<SessionTrackingMode> SSL_ONLY = EnumSet.of(SessionTrackingMode.SSL);
    // 适配器在请求/响应中的标记键
    public static final int ADAPTER_NOTES = 1;


    // 构造函数：初始化适配器与Connector的关联
    public CoyoteAdapter(Connector connector) {
        super();
        this.connector = connector; // 保存所属的Connector引用
    }


    // 实例变量定义
    private final Connector connector; // 关联的Connector对象
    protected static final StringManager sm = StringManager.getManager(CoyoteAdapter.class); // 字符串资源管理器


    // Adapter接口方法：处理异步请求分发
    @Override
    public boolean asyncDispatch(org.apache.coyote.Request req, org.apache.coyote.Response res, SocketEvent status)
        throws Exception {
        // 获取包装后的Request/Response对象
        Request request = (Request) req.getNote(ADAPTER_NOTES);
        Response response = (Response) res.getNote(ADAPTER_NOTES);

        if (request == null) {
            throw new IllegalStateException(sm.getString("coyoteAdapter.nullRequest"));
        }

        boolean success = true;
        AsyncContextImpl asyncConImpl = request.getAsyncContextInternal(); // 获取异步上下文

        req.setRequestThread(); // 标记当前线程为处理请求的线程

        try {
            if (!request.isAsync()) {
                // 非异步请求时，取消响应挂起状态（如sendError后恢复）
                response.setSuspended(false);
            }

            if (status == SocketEvent.TIMEOUT) {
                // 处理超时事件：触发异步上下文超时逻辑
                if (!asyncConImpl.timeout()) {
                    asyncConImpl.setErrorState(null, false);
                }
            } else if (status == SocketEvent.ERROR) {
                // 处理I/O错误：标记请求失败并通知监听器
                success = false;
                Throwable t = (Throwable) req.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                Context context = request.getContext();
                ClassLoader oldCL = null;
                try {
                    oldCL = context.bind(false, null); // 绑定上下文类加载器
                    // 通知读取/写入监听器发生错误
                    if (req.getReadListener() != null) req.getReadListener().onError(t);
                    if (res.getWriteListener() != null) res.getWriteListener().onError(t);
                    res.action(ActionCode.CLOSE_NOW, t); // 立即关闭连接
                    asyncConImpl.setErrorState(t, true); // 设置异步上下文错误状态
                } finally {
                    context.unbind(false, oldCL); // 解除上下文类加载器绑定
                }
            }

            // 处理非阻塞读写事件（异步请求场景）
            if (!request.isAsyncDispatching() && request.isAsync()) {
                WriteListener writeListener = res.getWriteListener();
                ReadListener readListener = req.getReadListener();
                if (writeListener != null && status == SocketEvent.OPEN_WRITE) {
                    // 可写事件：触发写监听器处理
                    Context context = request.getContext();
                    ClassLoader oldCL = null;
                    try {
                        oldCL = context.bind(false, null);
                        res.onWritePossible(); // 通知写监听器可写
                        // 若请求已完成且需要发送全部数据读取事件，通知读监听器
                        if (request.isFinished() && req.sendAllDataReadEvent() && readListener != null) {
                            readListener.onAllDataRead();
                        }
                        // 检查响应是否存在异常（用户代码可能已捕获）
                        if (response.getCoyoteResponse().isExceptionPresent()) {
                            throw response.getCoyoteResponse().getErrorException();
                        }
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        // 写监听器错误处理：通知监听器并关闭连接
                        writeListener.onError(t);
                        res.action(ActionCode.CLOSE_NOW, t);
                        asyncConImpl.setErrorState(t, true);
                    } finally {
                        context.unbind(false, oldCL);
                    }
                } else if (readListener != null && status == SocketEvent.OPEN_READ) {
                    // 可读事件：触发读监听器处理
                    Context context = request.getContext();
                    ClassLoader oldCL = null;
                    try {
                        oldCL = context.bind(false, null);
                        // 若请求未完成，通知读监听器有数据可用
                        if (!request.isFinished()) req.onDataAvailable();
                        if (request.isFinished() && req.sendAllDataReadEvent()) {
                            readListener.onAllDataRead();
                        }
                        // 检查请求是否存在异常
                        if (request.getCoyoteRequest().isExceptionPresent()) {
                            throw request.getCoyoteRequest().getErrorException();
                        }
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        // 读监听器错误处理
                        readListener.onError(t);
                        res.action(ActionCode.CLOSE_NOW, t);
                        asyncConImpl.setErrorState(t, true);
                    } finally {
                        context.unbind(false, oldCL);
                    }
                }
            }

            // 处理异步请求中的错误，需要转发到错误页面
            if (!request.isAsyncDispatching() && request.isAsync() && response.isErrorReportRequired()) {
                connector.getService().getContainer().getPipeline().getFirst().invoke(request, response);
            }

            if (request.isAsyncDispatching()) {
                // 异步分发：调用容器管道处理请求
                connector.getService().getContainer().getPipeline().getFirst().invoke(request, response);
                if (response.isError()) {
                    // 分发过程中发生错误，设置异步上下文错误状态
                    Throwable t = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                    asyncConImpl.setErrorState(t, true);
                }
            }

            if (!request.isAsync()) {
                // 非异步请求：完成请求处理
                request.finishRequest();
                response.finishResponse();
            }

            // 检查处理器是否处于错误状态
            AtomicBoolean error = new AtomicBoolean(false);
            res.action(ActionCode.IS_ERROR, error);
            if (error.get()) {
                // 错误状态：触发异步后处理（若需要）
                if (request.isAsyncCompleting() || request.isAsyncDispatching()) {
                    res.action(ActionCode.ASYNC_POST_PROCESS, null);
                }
                success = false;
            }
        } catch (IOException e) {
            success = false; // 忽略IO异常，标记请求失败
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            success = false; // 其他错误，标记请求失败并记录日志
            log.error(sm.getString("coyoteAdapter.asyncDispatch"), t);
        } finally {
            if (!success) {
                res.setStatus(500); // 错误时设置500状态码
            }

            // 访问日志记录（非异步或失败时）
            if (!success || !request.isAsync()) {
                long time = 0;
                if (req.getStartTimeNanos() != -1) {
                    time = System.nanoTime() - req.getStartTimeNanos();
                }
                Context context = request.getContext();
                if (context != null) {
                    context.logAccess(request, response, time, false);
                } else {
                    log(req, res, time); // 无上下文时直接记录日志
                }
            }

            // 清理线程名称和请求线程标记
            req.getRequestProcessor().setWorkerThreadName(null);
            req.clearRequestThread();
            // 回收请求/响应对象（非异步或失败时）
            if (!success || !request.isAsync()) {
                updateWrapperErrorCount(request, response);
                request.recycle();
                response.recycle();
            }
        }
        return success;
    }


    // Adapter接口方法：处理标准请求服务
    @Override
    public void service(org.apache.coyote.Request req, org.apache.coyote.Response res) throws Exception {
        // 获取或创建包装后的Request/Response对象
        Request request = (Request) req.getNote(ADAPTER_NOTES);
        Response response = (Response) res.getNote(ADAPTER_NOTES);

        if (request == null) {
            // 首次请求时创建对象并建立关联
            request = connector.createRequest();
            request.setCoyoteRequest(req);
            response = connector.createResponse();
            response.setCoyoteResponse(res);
            request.setResponse(response);
            response.setRequest(request);
            req.setNote(ADAPTER_NOTES, request);
            res.setNote(ADAPTER_NOTES, response);
            // 设置查询字符串编码
            req.getParameters().setQueryStringCharset(connector.getURICharset());
        }

        if (connector.getXpoweredBy()) {
            // 添加X-Powered-By响应头（若启用）
            response.addHeader("X-Powered-By", POWERED_BY);
        }

        boolean async = false;
        boolean postParseSuccess = false;

        req.setRequestThread(); // 标记当前线程为请求处理线程

        try {
            // 解析请求并设置容器相关参数
            postParseSuccess = postParseRequest(req, request, res, response);
            if (postParseSuccess) {
                // 检查容器是否支持异步请求
                request.setAsyncSupported(connector.getService().getContainer().getPipeline().isAsyncSupported());
                // 调用容器管道处理请求
                connector.getService().getContainer().getPipeline().getFirst().invoke(request, response);
            }
            if (request.isAsync()) {
                // 异步请求处理
                async = true;
                ReadListener readListener = req.getReadListener();
                if (readListener != null && request.isFinished()) {
                    // 请求完成时通知读监听器
                    ClassLoader oldCL = null;
                    try {
                        oldCL = request.getContext().bind(false, null);
                        if (req.sendAllDataReadEvent()) {
                            req.getReadListener().onAllDataRead();
                        }
                    } finally {
                        request.getContext().unbind(false, oldCL);
                    }
                }

                Throwable throwable = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                // 异步请求中发生错误且未完成时，触发错误处理
                if (!request.isAsyncCompleting() && throwable != null) {
                    request.getAsyncContextInternal().setErrorState(throwable, true);
                }
            } else {
                // 非异步请求：完成请求处理
                request.finishRequest();
                response.finishResponse();
            }

        } catch (IOException e) {
            // 忽略IO异常（由finally块处理）
        } finally {
            // 检查响应是否处于错误状态
            AtomicBoolean error = new AtomicBoolean(false);
            res.action(ActionCode.IS_ERROR, error);

            if (request.isAsyncCompleting() && error.get()) {
                // 异步完成时发生错误：触发后处理并关闭连接
                res.action(ActionCode.ASYNC_POST_PROCESS, null);
                async = false;
            }

            // 访问日志记录（非异步且解析成功时）
            if (!async && postParseSuccess) {
                long time = System.nanoTime() - req.getStartTimeNanos();
                Context context = request.getContext();
                if (context != null) {
                    context.logAccess(request, response, time, false);
                } else if (response.isError()) {
                    // 无上下文时通过主机或容器记录日志
                    Host host = request.getHost();
                    if (host != null) {
                        host.logAccess(request, response, time, false);
                    } else {
                        connector.getService().getContainer().logAccess(request, response, time, false);
                    }
                }
            }

            // 清理线程相关资源
            req.getRequestProcessor().setWorkerThreadName(null);
            req.clearRequestThread();
            // 回收请求/响应对象（非异步时）
            if (!async) {
                updateWrapperErrorCount(request, response);
                request.recycle();
                response.recycle();
            }
        }
    }


    // 更新Wrapper错误计数（响应错误时）
    private void updateWrapperErrorCount(Request request, Response response) {
        if (response.isError()) {
            Wrapper wrapper = request.getWrapper();
            if (wrapper != null) {
                wrapper.incrementErrorCount();
            }
        }
    }


    // Adapter接口方法：准备请求处理（解析后验证）
    @Override
    public boolean prepare(org.apache.coyote.Request req, org.apache.coyote.Response res)
        throws IOException, ServletException {
        Request request = (Request) req.getNote(ADAPTER_NOTES);
        Response response = (Response) res.getNote(ADAPTER_NOTES);
        return postParseRequest(req, request, res, response); // 调用请求解析后处理
    }


    // Adapter接口方法：记录访问日志
    @Override
    public void log(org.apache.coyote.Request req, org.apache.coyote.Response res, long time) {
        // 获取或创建包装后的请求/响应对象
        Request request = (Request) req.getNote(ADAPTER_NOTES);
        Response response = (Response) res.getNote(ADAPTER_NOTES);

        if (request == null) {
            // 首次调用时创建对象并建立关联
            request = connector.createRequest();
            request.setCoyoteRequest(req);
            response = connector.createResponse();
            response.setCoyoteResponse(res);
            request.setResponse(response);
            response.setRequest(request);
            req.setNote(ADAPTER_NOTES, request);
            res.setNote(ADAPTER_NOTES, response);
            req.getParameters().setQueryStringCharset(connector.getURICharset());
        }

        try {
            // 记录访问日志（最低级别，由父容器处理）
            boolean logged = false;
            Context context = request.mappingData.context;
            Host host = request.mappingData.host;
            if (context != null) {
                logged = true;
                context.logAccess(request, response, time, true);
            } else if (host != null) {
                logged = true;
                host.logAccess(request, response, time, true);
            }
            if (!logged) {
                connector.getService().getContainer().logAccess(request, response, time, true);
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.warn(sm.getString("coyoteAdapter.accesslogFail"), t); // 日志记录失败时警告
        } finally {
            updateWrapperErrorCount(request, response);
            request.recycle();
            response.recycle(); // 回收对象
        }
    }

    private static class RecycleRequiredException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    // 检查请求/响应是否已回收（防止重复使用）
    @Override
    public void checkRecycled(org.apache.coyote.Request req, org.apache.coyote.Response res) {
        Request request = (Request) req.getNote(ADAPTER_NOTES);
        Response response = (Response) res.getNote(ADAPTER_NOTES);
        String messageKey = null;

        // 检查请求或响应是否存在异常使用情况
        if (request != null && request.getHost() != null) {
            messageKey = "coyoteAdapter.checkRecycled.request";
        } else if (response != null && response.getContentWritten() != 0) {
            messageKey = "coyoteAdapter.checkRecycled.response";
        }

        if (messageKey != null) {
            // 记录可能未正常回收的请求/响应，并触发日志记录（会自动回收）
            log(req, res, 0L);

            if (connector.getState().isAvailable()) {
                // 连接器正常运行时，记录信息级日志
                if (log.isInfoEnabled()) {
                    log.info(sm.getString(messageKey), new RecycleRequiredException());
                }
            } else {
                // 连接器关闭时，仅记录调试级日志（可能存在中止的请求）
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString(messageKey), new RecycleRequiredException());
                }
            }
        }
    }


    @Override
    public String getDomain() {
        return connector.getDomain(); // 返回连接器关联的域名
    }


    // 核心方法：请求解析后处理（关键处理逻辑）
    @SuppressWarnings("deprecation")
    protected boolean postParseRequest(org.apache.coyote.Request req, Request request, org.apache.coyote.Response res,
                                       Response response) throws IOException, ServletException {

        // 设置请求协议和安全标志（优先使用处理器设置，否则用连接器配置）
        if (req.scheme().isNull()) {
            req.scheme().setString(connector.getScheme());
            request.setSecure(connector.getSecure());
        } else {
            // 使用处理器指定的协议确定安全状态
            request.setSecure(req.scheme().equals("https"));
        }

        // 处理代理设置：覆盖服务器名称和端口（如果配置了代理）
        String proxyName = connector.getProxyName();
        int proxyPort = connector.getProxyPort();
        if (proxyPort != 0) {
            req.setServerPort(proxyPort);
        } else if (req.getServerPort() == -1) {
            // 未明确设置端口时，使用协议默认端口
            if (req.scheme().equals("https")) {
                req.setServerPort(443);
            } else {
                req.setServerPort(80);
            }
        }
        if (proxyName != null) {
            req.serverName().setString(proxyName);
        }

        MessageBytes undecodedURI = req.requestURI();

        // 处理特殊请求：ping OPTIONS * 请求（用于检查服务器可用性）
        if (undecodedURI.equals("*")) {
            if (req.method().equals("OPTIONS")) {
                // 构建允许的HTTP方法列表
                StringBuilder allow = new StringBuilder();
                allow.append("GET, HEAD, POST, PUT, DELETE, OPTIONS");
                // 如果允许TRACE方法，则添加
                if (connector.getAllowTrace()) {
                    allow.append(", TRACE");
                }
                res.setHeader("Allow", allow.toString());
                // 直接记录访问日志（不通过AccessLogValve）
                connector.getService().getContainer().logAccess(request, response, 0, true);
                return false;
            } else {
                // 无效的请求方法，返回400错误
                response.sendError(400, sm.getString("coyoteAdapter.invalidURI"));
            }
        }

        MessageBytes decodedURI = req.decodedURI();

        // 过滤CONNECT方法（不支持）
        if (req.method().equals("CONNECT")) {
            response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED, sm.getString("coyoteAdapter.connect"));
        } else {
            // 非CONNECT请求处理URI
            if (undecodedURI.getType() == MessageBytes.T_BYTES) {
                // 检查可疑URI（如果配置了拒绝策略）
                if (connector.getRejectSuspiciousURIs()) {
                    if (checkSuspiciousURIs(undecodedURI.getByteChunk())) {
                        response.sendError(400, sm.getString("coyoteAdapter.invalidURI"));
                    }
                }

                // 复制原始URI到解码URI
                decodedURI.duplicate(undecodedURI);

                // 解析并提取路径参数（如分号后的参数）
                parsePathParameters(req, request);

                // URI解码处理：
                // 1. 处理%xx格式的编码
                try {
                    req.getURLDecoder().convert(decodedURI.getByteChunk(),
                        connector.getEncodedSolidusHandlingInternal(),
                        connector.getEncodedReverseSolidusHandlingInternal());
                } catch (IOException ioe) {
                    response.sendError(400, sm.getString("coyoteAdapter.invalidURIWithMessage", ioe.getMessage()));
                }

                // 2. 路径规范化（处理../、./等）
                if (normalize(req.decodedURI(), connector.getAllowBackslash())) {
                    // 3. 字符解码（将字节转换为字符）
                    convertURI(decodedURI, request);
                    // 由于URIEncoding为US-ASCII超集，字符解码后无需再次检查规范化
                } else {
                    // 规范化失败，返回400错误
                    response.sendError(400, sm.getString("coyoteAdapter.invalidURI"));
                }
            } else {
                // 对于字符或字符串类型的URI（内存协议处理器），假设已完成解码和规范化
                decodedURI.toChars();
            }
        }

        // 请求映射：确定请求的目标主机和上下文
        MessageBytes serverName;
        if (connector.getUseIPVHosts()) {
            serverName = req.localName();
            if (serverName.isNull()) {
                // 获取本地名称（如果启用了IPVHosts）
                res.action(ActionCode.REQ_LOCAL_NAME_ATTRIBUTE, null);
            }
        } else {
            serverName = req.serverName();
        }

        // 版本映射相关变量（处理不同版本的上下文）
        String version = null;
        Context versionContext = null;
        boolean mapRequired = true;

        if (response.isError()) {
            // 如果响应已处于错误状态，清空URI（防止无效数据传递给映射器）
            decodedURI.recycle();
        }

        // 循环处理映射请求（可能需要多次映射以找到正确的上下文）
        while (mapRequired) {
            // 使用映射器映射请求到目标主机、上下文和Servlet
            connector.getService().getMapper().map(serverName, decodedURI, version, request.getMappingData());

            // 如果未找到上下文，可能是404错误或URI无效
            if (request.getContext() == null) {
                // 允许继续处理，可能由Rewrite Valve重写为有效请求
                // 或由StandardEngineValve/StandardHostValve处理缺失的主机/上下文
                return true;
            }

            // 解析URL中的会话ID（如果启用了URL会话跟踪）
            if (request.getServletContext().getEffectiveSessionTrackingModes().contains(SessionTrackingMode.URL)) {
                // 获取会话ID参数（如果存在）
                String sessionID = request.getPathParameter(SessionConfig.getSessionUriParamName(request.getContext()));
                if (sessionID != null) {
                    request.setRequestedSessionId(sessionID);
                    request.setRequestedSessionURL(true);
                }
            }

            // 从Cookie和SSL会话中查找会话ID
            try {
                parseSessionCookiesId(request);
            } catch (IllegalArgumentException e) {
                // 处理Cookie过多的情况
                if (!response.isError()) {
                    response.setError();
                    response.sendError(400, e.getMessage());
                }
                return true;
            }
            parseSessionSslId(request);

            String sessionID = request.getRequestedSessionId();

            mapRequired = false;
            if (version != null && request.getContext() == versionContext) {
                // 已找到指定版本的上下文，结束映射
            } else {
                version = null;
                versionContext = null;

                // 处理多版本上下文的会话映射
                Context[] contexts = request.getMappingData().contexts;
                if (contexts != null && sessionID != null) {
                    // 查找与会话ID关联的上下文
                    for (int i = contexts.length; i > 0; i--) {
                        Context ctxt = contexts[i - 1];
                        if (ctxt.getManager().findSession(sessionID) != null) {
                            // 找到匹配的上下文，但不是当前映射的上下文，需要重新映射
                            if (!ctxt.equals(request.getMappingData().context)) {
                                version = ctxt.getWebappVersion();
                                versionContext = ctxt;
                                request.getMappingData().recycle(); // 重置映射数据
                                mapRequired = true;
                                // 重置会话和Cookie信息（可能新上下文配置不同）
                                request.recycleSessionInfo();
                                request.recycleCookieInfo(true);
                            }
                            break;
                        }
                    }
                }
            }

            // 检查上下文是否暂停（暂停时需要重新映射）
            if (!mapRequired && request.getContext().getPaused()) {
                try {
                    Thread.sleep(1000); // 等待1秒
                } catch (InterruptedException e) {
                    // 不应该发生中断
                }
                request.getMappingData().recycle(); // 重置映射
                mapRequired = true;
            }
        }

        // 处理可能的重定向（如路径规范化需要）
        MessageBytes redirectPathMB = request.getMappingData().redirectPath;
        if (!redirectPathMB.isNull()) {
            // 构建重定向URL（编码路径和添加会话ID）
            String redirectPath = URLEncoder.DEFAULT.encode(redirectPathMB.toString(), StandardCharsets.UTF_8);
            String query = request.getQueryString();
            if (request.isRequestedSessionIdFromURL()) {
                // 添加会话ID参数
                redirectPath = redirectPath + ";" + SessionConfig.getSessionUriParamName(request.getContext()) + "=" +
                    request.getRequestedSessionId();
            }
            if (query != null) {
                // 添加查询字符串
                redirectPath = redirectPath + "?" + query;
            }
            // 发送重定向响应
            response.sendRedirect(redirectPath);
            request.getContext().logAccess(request, response, 0, true);
            return false;
        }

        // 过滤TRACE方法（如果不允许）
        if (!connector.getAllowTrace() && req.method().equals("TRACE")) {
            Wrapper wrapper = request.getWrapper();
            StringBuilder header = null;
            if (wrapper != null) {
                // 获取Servlet支持的方法
                String[] methods = wrapper.getServletMethods();
                if (methods != null) {
                    for (String method : methods) {
                        if ("TRACE".equals(method)) continue;
                        if (header == null) {
                            header = new StringBuilder(method);
                        } else {
                            header.append(", ").append(method);
                        }
                    }
                }
            }
            if (header != null) {
                res.addHeader("Allow", header.toString());
            }
            // 不允许TRACE方法，返回405错误
            response.sendError(405, sm.getString("coyoteAdapter.trace"));
            return true;
        }

        // 执行连接器级别的认证和授权
        doConnectorAuthenticationAuthorization(req, request);

        return true;
    }


    // 执行连接器级别的认证和授权
    private void doConnectorAuthenticationAuthorization(org.apache.coyote.Request req, Request request) {
        // 设置远程用户主体
        String username = req.getRemoteUser().toString();
        if (username != null) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("coyoteAdapter.authenticate", username));
            }
            if (req.getRemoteUserNeedsAuthorization()) {
                // 需要授权：通过验证器进行授权
                Authenticator authenticator = request.getContext().getAuthenticator();
                if (!(authenticator instanceof AuthenticatorBase)) {
                    // 自定义验证器可能不会触发授权，这里手动执行
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("coyoteAdapter.authorize", username));
                    }
                    request.setUserPrincipal(request.getContext().getRealm().authenticate(username));
                }
                // 如果是AuthenticatorBase的实例，它会在适当的时候检查并触发授权
            } else {
                // 连接器未配置授权：创建无角色的用户主体
                request.setUserPrincipal(new CoyotePrincipal(username));
            }
        }

        // 设置认证类型
        String authType = req.getAuthType().toString();
        if (authType != null) {
            request.setAuthType(authType);
        }
    }


    /**
     * 解析请求路径中的参数（如/path;param=value形式）
     * 主要关注会话ID参数，其他参数会被忽略
     */
    protected void parsePathParameters(org.apache.coyote.Request req, Request request) {
        // 处理字节形式的URI（默认格式）
        req.decodedURI().toBytes();

        ByteChunk uriBC = req.decodedURI().getByteChunk();
        // 从位置1开始查找分号（第一个字符必须是'/'）
        int semicolon = uriBC.indexOf(';', 1);
        // 性能优化：无分号则直接返回（无路径参数）
        if (semicolon == -1) {
            return;
        }

        // 使用明确的字符集（避免平台默认编码问题）
        Charset charset = connector.getURICharset();

        if (log.isTraceEnabled()) {
            log.trace(sm.getString("coyoteAdapter.debug", "uriBC", uriBC.toString()));
            log.trace(sm.getString("coyoteAdapter.debug", "semicolon", String.valueOf(semicolon)));
            log.trace(sm.getString("coyoteAdapter.debug", "enc", charset.name()));
        }

        // 循环处理所有路径参数
        while (semicolon > -1) {
            int start = uriBC.getStart();
            int end = uriBC.getEnd();

            int pathParamStart = semicolon + 1;
            int pathParamEnd =
                ByteChunk.findBytes(uriBC.getBuffer(), start + pathParamStart, end, new byte[] { ';', '/' });

            String pv = null;

            if (pathParamEnd >= 0) {
                // 提取路径参数并从URI中移除
                if (charset != null) {
                    pv = new String(uriBC.getBuffer(), start + pathParamStart, pathParamEnd - pathParamStart, charset);
                }
                // 从URI中移除参数部分
                byte[] buf = uriBC.getBuffer();
                for (int i = 0; i < end - start - pathParamEnd; i++) {
                    buf[start + semicolon + i] = buf[start + i + pathParamEnd];
                }
                uriBC.setBytes(buf, start, end - start - pathParamEnd + semicolon);
            } else {
                // 参数位于URI末尾
                if (charset != null) {
                    pv = new String(uriBC.getBuffer(), start + pathParamStart, (end - start) - pathParamStart, charset);
                }
                uriBC.setEnd(start + semicolon);
            }

            if (log.isTraceEnabled()) {
                log.trace(sm.getString("coyoteAdapter.debug", "pathParamStart", String.valueOf(pathParamStart)));
                log.trace(sm.getString("coyoteAdapter.debug", "pathParamEnd", String.valueOf(pathParamEnd)));
                log.trace(sm.getString("coyoteAdapter.debug", "pv", pv));
            }

            if (pv != null) {
                // 解析参数名和值（格式：name=value）
                int equals = pv.indexOf('=');
                if (equals > -1) {
                    String name = pv.substring(0, equals);
                    String value = pv.substring(equals + 1);
                    request.addPathParameter(name, value);
                    if (log.isTraceEnabled()) {
                        log.trace(sm.getString("coyoteAdapter.debug", "equals", String.valueOf(equals)));
                        log.trace(sm.getString("coyoteAdapter.debug", "name", name));
                        log.trace(sm.getString("coyoteAdapter.debug", "value", value));
                    }
                }
            }

            // 继续查找下一个分号
            semicolon = uriBC.indexOf(';', semicolon);
        }
    }


    /**
     * 从SSL会话中查找会话ID（仅当SSL是唯一跟踪模式时）
     */
    protected void parseSessionSslId(Request request) {
        if (request.getRequestedSessionId() == null &&
            SSL_ONLY.equals(request.getServletContext().getEffectiveSessionTrackingModes()) &&
            request.connector.secure) {
            // 从请求属性中获取SSL会话ID
            String sessionId = (String) request.getAttribute(SSLSupport.SESSION_ID_KEY);
            if (sessionId != null) {
                request.setRequestedSessionId(sessionId);
                request.setRequestedSessionSSL(true);
            }
        }
    }


    /**
     * 从Cookie中解析会话ID
     */
    protected void parseSessionCookiesId(Request request) {
        // 如果当前上下文禁用了Cookie会话跟踪，则不处理
        Context context = request.getMappingData().context;
        if (context != null &&
            !context.getServletContext().getEffectiveSessionTrackingModes().contains(SessionTrackingMode.COOKIE)) {
            return;
        }

        // 从Cookie中解析会话ID
        ServerCookies serverCookies = request.getServerCookies();
        int count = serverCookies.getCookieCount();
        if (count <= 0) {
            return;
        }

        String sessionCookieName = SessionConfig.getSessionCookieName(context);

        for (int i = 0; i < count; i++) {
            ServerCookie scookie = serverCookies.getCookie(i);
            if (scookie.getName().equals(sessionCookieName)) {
                // 覆盖URL中请求的会话ID（如果存在）
                if (!request.isRequestedSessionIdFromCookie()) {
                    // 仅接受第一个会话ID Cookie
                    convertMB(scookie.getValue());
                    request.setRequestedSessionId(scookie.getValue().toString());
                    request.setRequestedSessionCookie(true);
                    request.setRequestedSessionURL(false);
                    if (log.isTraceEnabled()) {
                        log.trace(" Requested cookie session id is " + request.getRequestedSessionId());
                    }
                } else {
                    if (!request.isRequestedSessionIdValid()) {
                        // 会话ID无效时，用后续Cookie中的ID替换
                        convertMB(scookie.getValue());
                        request.setRequestedSessionId(scookie.getValue().toString());
                    }
                }
            }
        }
    }


    /**
     * 将URI从字节转换为字符（使用指定字符集）
     */
    protected void convertURI(MessageBytes uri, Request request) throws IOException {
        ByteChunk bc = uri.getByteChunk();
        int length = bc.getLength();
        CharChunk cc = uri.getCharChunk();
        cc.allocate(length, -1);

        Charset charset = connector.getURICharset();

        // 获取或创建字符转换器
        B2CConverter conv = request.getURIConverter();
        if (conv == null) {
            conv = new B2CConverter(charset, false);
            request.setURIConverter(conv);
        } else {
            conv.recycle();
        }

        try {
            // 执行字节到字符的转换
            conv.convert(bc, cc, true);
            uri.setChars(cc.getBuffer(), cc.getStart(), cc.getLength());
        } catch (IOException ioe) {
            // 转换失败，返回400错误
            request.getResponse().sendError(HttpServletResponse.SC_BAD_REQUEST);
        }
    }


    /**
     * 将US-ASCII编码的MessageBytes转换为字符
     */
    protected void convertMB(MessageBytes mb) {
        // 仅处理字节类型的MessageBytes
        if (mb.getType() != MessageBytes.T_BYTES) {
            return;
        }

        ByteChunk bc = mb.getByteChunk();
        CharChunk cc = mb.getCharChunk();
        int length = bc.getLength();
        cc.allocate(length, -1);

        // 快速转换（默认编码）
        byte[] bbuf = bc.getBuffer();
        char[] cbuf = cc.getBuffer();
        int start = bc.getStart();
        for (int i = 0; i < length; i++) {
            cbuf[i] = (char) (bbuf[i + start] & 0xff);
        }
        mb.setChars(cbuf, 0, length);
    }


    /**
     * 规范化URI路径（处理"\", "//", "/./", "/../"等特殊情况）
     *
     * @return false表示规范化会导致路径超出根目录或包含空字节，否则返回true
     */
    public static boolean normalize(MessageBytes uriMB, boolean allowBackslash) {
        ByteChunk uriBC = uriMB.getByteChunk();
        final byte[] b = uriBC.getBytes();
        final int start = uriBC.getStart();
        int end = uriBC.getEnd();
        boolean appendedSlash = false;

        // 空URL是不可接受的
        if (start == end) {
            return false;
        }

        // URL必须以'/'或'\'开头（'\'会被替换为'/'）
        if (b[start] != (byte) '/' && b[start] != (byte) '\\') {
            return false;
        }

        int pos;

        // 替换'\'为'/'，检查空字节
        for (pos = start; pos < end; pos++) {
            if (b[pos] == (byte) '\\') {
                if (allowBackslash) {
                    b[pos] = (byte) '/';
                } else {
                    return false;
                }
            } else if (b[pos] == (byte) 0) {
                return false;
            }
        }

        // 替换"//"为"/"
        for (pos = start; pos < (end - 1); pos++) {
            if (b[pos] == (byte) '/') {
                while ((pos + 1 < end) && (b[pos + 1] == (byte) '/')) {
                    copyBytes(b, pos, pos + 1, end - pos - 1);
                    end--;
                }
            }
        }

        // 处理以"/."或"/.."结尾的URI（添加额外的'/'）
        if (((end - start) >= 2) && (b[end - 1] == (byte) '.')) {
            if ((b[end - 2] == (byte) '/') || ((b[end - 2] == (byte) '.') && (b[end - 3] == (byte) '/'))) {
                b[end] = (byte) '/';
                end++;
                appendedSlash = true;
            }
        }

        uriBC.setEnd(end);

        int index = 0;

        // 处理"/./"情况（移除中间的"./"）
        while (true) {
            index = uriBC.indexOf("/./", 0, 3, index);
            if (index < 0) {
                break;
            }
            // 移除"/./"中的"./"
            copyBytes(b, start + index, start + index + 2, end - start - index - 2);
            end = end - 2;
            uriBC.setEnd(end);
        }

        index = 0;

        // 处理"/../"情况（移除上级目录引用）
        while (true) {
            index = uriBC.indexOf("/../", 0, 4, index);
            if (index < 0) {
                break;
            }
            // 防止路径超出根目录
            if (index == 0) {
                return false;
            }
            // 查找上一个'/'位置
            int index2 = -1;
            for (pos = start + index - 1; (pos >= 0) && (index2 < 0); pos--) {
                if (b[pos] == (byte) '/') {
                    index2 = pos;
                }
            }
            // 移除"../"及其前面的路径部分
            copyBytes(b, start + index2, start + index + 3, end - start - index - 3);
            end = end + index2 - index - 3;
            uriBC.setEnd(end);
            index = index2;
        }

        // 如果之前添加了额外的'/'，且结果不是根路径，则移除尾部的'/'
        if (appendedSlash && end > 1 && b[end - 1] == '/') {
            uriBC.setEnd(end - 1);
        }

        return true;
    }


    /**
     * 数组复制方法（用于URI规范化）
     */
    protected static void copyBytes(byte[] b, int dest, int src, int len) {
        System.arraycopy(b, src, b, dest, len);
    }


    /**
     * 检查URI中是否包含可疑内容（如编码控制字符、非法路径等）
     */
    private static boolean checkSuspiciousURIs(ByteChunk undecodedURI) {
        byte[] bytes = undecodedURI.getBytes();
        int start = undecodedURI.getStart();
        int end = undecodedURI.getEnd();

        // 查找第一个路径段
        int segmentStart = undecodedURI.indexOf('/', 0);
        int segmentEnd = -1;
        if (segmentStart > -1) {
            segmentEnd = undecodedURI.indexOf('/', segmentStart + 1);
        }

        // 逐段检查URI
        while (segmentStart > -1) {
            int pos = start + segmentStart + 1;

            // 检查空路径段（除了带路径参数的最后一段）
            if (segmentEnd > 0 && bytes[pos] == ';') {
                return true;
            }

            // 检查编码的点段（如%2e）和带路径参数的点段
            int dotCount = 0;
            boolean encodedDot = false;
            while (pos < end) {
                if (bytes[pos] == '.') {
                    dotCount++;
                    pos++;
                } else if (pos + 2 < end && bytes[pos] == '%' && bytes[pos + 1] == '2' &&
                    (bytes[pos + 2] == 'e' || bytes[pos + 2] == 'E')) {
                    encodedDot = true;
                    dotCount++;
                    pos += 3;
                } else if (bytes[pos] == ';') {
                    if (dotCount > 0) {
                        return true;
                    }
                    break;
                } else if (bytes[pos] == '/') {
                    break;
                } else {
                    dotCount = 0;
                    break;
                }
            }
            if (dotCount > 0 && encodedDot) {
                return true;
            }

            // 检查%nn编码的控制字符或'/'
            pos = start + segmentStart + 1;
            while (pos < end) {
                if (pos + 2 < end && bytes[pos] == '%') {
                    byte b1 = bytes[pos + 1];
                    byte b2 = bytes[pos + 2];
                    pos += 3;
                    int decoded = (HexUtils.getDec(b1) << 4) + HexUtils.getDec(b2);
                    // 检查是否为控制字符、删除字符或'/'
                    if (decoded < 20 || decoded == 0x7F || decoded == 0x2F) {
                        return true;
                    }
                } else {
                    pos++;
                }
            }

            // 移动到下一个路径段
            if (segmentEnd == -1) {
                segmentStart = -1;
            } else {
                segmentStart = segmentEnd;
                if (segmentStart > -1) {
                    segmentEnd = undecodedURI.indexOf('/', segmentStart + 1);
                }
            }
        }

        return false;
    }
}