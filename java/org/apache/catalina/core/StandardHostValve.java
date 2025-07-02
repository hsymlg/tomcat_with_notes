/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina.core;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.descriptor.web.ErrorPage;
import org.apache.tomcat.util.res.StringManager;

/**
 * StandardHost容器的默认基础阀门实现
 *
 * 核心职责：
 * 1. 根据请求URI选择对应的Web应用(Context)容器
 * 2. 将请求转发到选中的Context容器进行处理
 * 3. 处理请求过程中的异常和错误状态
 * 4. 实现错误页面映射和异常处理逻辑
 *
 * 使用约束：
 * - 主要用于处理HTTP请求
 * - 依赖Request对象中已解析的Context信息
 */
final class StandardHostValve extends ValveBase {

    // 日志记录器和字符串资源管理器
    private static final Log log = LogFactory.getLog(StandardHostValve.class);
    private static final StringManager sm = StringManager.getManager(StandardHostValve.class);

    // 类加载器缓存（避免每次请求都调用getClassLoader()）
    private static final ClassLoader MY_CLASSLOADER = StandardHostValve.class.getClassLoader();

    // ------------------------------------------------------ Constructor

    /**
     * 构造StandardHostValve实例
     * 调用父类构造函数并设置asyncSupported为true，表示支持异步处理
     */
    StandardHostValve() {
        super(true);
    }

    // --------------------------------------------------------- Public Methods

    /**
     * 核心请求处理方法
     * 实现逻辑：
     * 1. 从Request中获取目标Context容器
     * 2. 若Context不存在，返回HTTP 404错误
     * 3. 检查异步支持状态并传递给Request
     * 4. 绑定Context资源并触发请求初始化事件
     * 5. 将请求传递给Context容器的Pipeline处理
     * 6. 处理请求过程中的异常和错误状态
     * 7. 触发请求销毁事件并释放资源
     *
     * @param request  Servlet请求对象
     * @param response Servlet响应对象
     * @throws IOException      输入/输出异常
     * @throws ServletException Servlet处理异常
     */
    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {

        // ------------------------- 步骤1：获取请求目标Context -------------------------
        // 从Request中获取对应的Context容器（基于请求URI解析）
        Context context = request.getContext();

        // 若Context为空（如无效URI或应用未部署）
        if (context == null) {
            // 若响应尚未设置错误状态
            if (!response.isError()) {
                // 返回HTTP 404错误（未找到资源）
                response.sendError(404);
            }
            // 终止请求处理流程
            return;
        }

        // ------------------------- 步骤2：处理异步支持 -------------------------
        // 若请求支持异步处理
        if (request.isAsyncSupported()) {
            // 设置请求的异步支持状态为Context容器Pipeline的异步支持状态
            // 确保整个处理链的异步一致性
            request.setAsyncSupported(context.getPipeline().isAsyncSupported());
        }

        // 记录请求开始时的异步状态（用于后续对比）
        boolean asyncAtStart = request.isAsync();

        try {
            // ------------------------- 步骤3：绑定Context资源 -------------------------
            // 绑定Context到当前线程（用于安全上下文和类加载）
            context.bind(Globals.IS_SECURITY_ENABLED, MY_CLASSLOADER);

            // ------------------------- 步骤4：触发请求初始化事件 -------------------------
            // 若请求非异步且Context的请求初始化事件失败
            if (!asyncAtStart && !context.fireRequestInitEvent(request.getRequest())) {
                // 请求初始化监听器抛出异常，终止处理
                return;
            }

            // ------------------------- 步骤5：转发请求到Context -------------------------
            try {
                // 若响应不需要错误报告（即请求处理正常）
                if (!response.isErrorReportRequired()) {
                    // 获取Context容器的Pipeline并调用其第一个Valve
                    // 启动Context容器的责任链处理流程
                    context.getPipeline().getFirst().invoke(request, response);
                }
            } catch (Throwable t) {
                // 捕获请求处理中的所有异常
                ExceptionUtils.handleThrowable(t);
                // 记录异常信息（包含请求URI）
                container.getLogger().error(sm.getString("standardHostValve.exception", request.getRequestURI()), t);

                // 若当前不是在处理错误报告时发生的新错误
                if (!response.isErrorReportRequired()) {
                    // 设置错误属性并处理异常
                    request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, t);
                    throwable(request, response, t);
                }
            }

            // ------------------------- 步骤6：处理响应挂起状态 -------------------------
            // 恢复响应的正常状态（取消挂起）
            response.setSuspended(false);

            // 获取请求中的错误异常（若有）
            Throwable t = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

            // 若Context已不可用（如正在销毁），直接返回
            if (!context.getState().isAvailable()) {
                return;
            }

            // ------------------------- 步骤7：处理错误报告 -------------------------
            // 若响应需要生成错误报告
            if (response.isErrorReportRequired()) {
                // 检查是否允许进行I/O操作（如客户端未断开连接）
                AtomicBoolean result = new AtomicBoolean(false);
                response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, result);
                if (result.get()) {
                    // 存在异常时处理异常，否则处理状态码
                    if (t != null) {
                        throwable(request, response, t);
                    } else {
                        status(request, response);
                    }
                }
            }

            // ------------------------- 步骤8：触发请求销毁事件 -------------------------
            // 若请求非异步且初始状态也非异步
            if (!request.isAsync() && !asyncAtStart) {
                // 触发Context的请求销毁事件
                context.fireRequestDestroyEvent(request.getRequest());
            }
        } finally {
            // ------------------------- 步骤9：资源清理 -------------------------
            // 若Context配置为始终访问会话，更新会话最后访问时间
            if (context.getAlwaysAccessSession()) {
                request.getSession(false);
            }

            // 解除Context与当前线程的绑定（释放资源）
            context.unbind(Globals.IS_SECURITY_ENABLED, MY_CLASSLOADER);
        }
    }

    // -------------------------------------------------------- Private Methods

    /**
     * 处理HTTP状态码并生成错误页面
     *
     * @param request  Servlet请求对象
     * @param response Servlet响应对象
     */
    private void status(Request request, Response response) {
        // 获取响应状态码
        int statusCode = response.getStatus();

        // 获取请求对应的Context容器
        Context context = request.getContext();
        if (context == null) {
            return;
        }

        // 仅当响应为错误状态时处理自定义错误页面
        if (!response.isError()) {
            return;
        }

        // 查找匹配状态码的错误页面配置
        ErrorPage errorPage = context.findErrorPage(statusCode);
        if (errorPage == null) {
            // 未找到匹配状态码的错误页面，查找默认错误页面
            errorPage = context.findErrorPage(0);
        }

        // 若找到错误页面且需要生成错误报告
        if (errorPage != null && response.isErrorReportRequired()) {
            // 标记响应未被应用程序提交（允许Tomcat处理）
            response.setAppCommitted(false);
            // 设置请求错误属性（状态码、消息等）
            setRequestErrorAttributes(request, statusCode, null, response.getMessage(), null, errorPage.getLocation());

            // 转发到自定义错误页面
            if (custom(request, response, errorPage)) {
                // 标记错误已处理
                response.setErrorReported();
                try {
                    // 完成响应处理
                    response.finishResponse();
                } catch (ClientAbortException e) {
                    // 客户端中断请求，忽略
                } catch (IOException e) {
                    // 记录转发错误页面时的异常
                    container.getLogger().warn(sm.getString("standardHostValve.exception", errorPage), e);
                }
            }
        }
    }

    /**
     * 处理请求过程中抛出的异常并生成错误页面
     *
     * @param request   Servlet请求对象
     * @param response  Servlet响应对象
     * @param throwable 捕获的异常对象
     */
    @SuppressWarnings("deprecation")
    protected void throwable(Request request, Response response, Throwable throwable) {
        // 获取请求对应的Context容器
        Context context = request.getContext();
        if (context == null) {
            return;
        }

        // 解析真实错误（处理包装异常）
        Throwable realError = throwable;
        if (realError instanceof ServletException) {
            realError = ((ServletException) realError).getRootCause();
            if (realError == null) {
                realError = throwable;
            }
        }

        // 处理客户端中断请求（仅记录调试日志）
        if (realError instanceof ClientAbortException) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("standardHost.clientAbort", realError.getCause().getMessage()));
            }
            return;
        }

        // 查找匹配异常类型的错误页面配置
        ErrorPage errorPage = context.findErrorPage(throwable);
        if ((errorPage == null) && (realError != throwable)) {
            errorPage = context.findErrorPage(realError);
        }

        // 若找到错误页面
        if (errorPage != null) {
            // 标记错误已处理（避免重复处理）
            if (response.setErrorReported()) {
                response.setAppCommitted(false);
                // 设置请求错误属性（异常类型、消息等）
                setRequestErrorAttributes(request, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    realError.getClass(), throwable.getMessage(), realError, errorPage.getLocation());

                // 转发到自定义错误页面
                if (custom(request, response, errorPage)) {
                    try {
                        // 完成响应处理
                        response.finishResponse();
                    } catch (IOException e) {
                        // 记录转发错误页面时的异常
                        container.getLogger().warn(sm.getString("standardHostValve.exception", errorPage), e);
                    }
                }
            }
        } else {
            // 未找到匹配异常的错误页面，处理状态码错误
            if (response.getStatus() < HttpServletResponse.SC_BAD_REQUEST) {
                // 设置默认内部服务器错误状态码
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            // 标记响应为错误状态
            response.setError();
            // 处理状态码错误页面
            status(request, response);
        }
    }

    /**
     * 设置请求的错误属性（供错误页面使用）
     *
     * @param request        Servlet请求对象
     * @param statusCode     错误状态码
     * @param exceptionType  异常类型
     * @param message        错误消息
     * @param exception      异常对象
     * @param location       错误页面路径
     */
    private void setRequestErrorAttributes(Request request, int statusCode, Class<?> exceptionType, String message,
                                           Throwable exception, String location) {
        // 设置错误状态码属性
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, Integer.valueOf(statusCode));

        // 设置异常类型属性（非空时）
        if (exceptionType != null) {
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE, exceptionType);
        }

        // 设置错误消息属性（确保非空）
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, Objects.requireNonNullElse(message, ""));

        // 设置异常对象属性（非空时）
        if (exception != null) {
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, exception);
        }

        // 设置请求URI属性
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());

        // 设置Servlet名称属性（若有）
        Wrapper wrapper = request.getWrapper();
        if (wrapper != null) {
            request.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME, wrapper.getName());
        }

        // 设置错误页面路径和调度类型属性
        request.setAttribute(Globals.DISPATCHER_REQUEST_PATH_ATTR, location);
        request.setAttribute(Globals.DISPATCHER_TYPE_ATTR, DispatcherType.ERROR);
    }

    /**
     * 转发到自定义错误页面
     *
     * @param request   Servlet请求对象
     * @param response  Servlet响应对象
     * @param errorPage 错误页面配置
     * @return 是否成功处理自定义错误页面
     */
    private boolean custom(Request request, Response response, ErrorPage errorPage) {
        // 记录调试日志
        if (container.getLogger().isTraceEnabled()) {
            container.getLogger().trace("Processing " + errorPage);
        }

        try {
            // 获取Context的ServletContext
            ServletContext servletContext = request.getContext().getServletContext();
            // 获取错误页面的RequestDispatcher
            RequestDispatcher rd = servletContext.getRequestDispatcher(errorPage.getLocation());

            // 若无法获取RequestDispatcher
            if (rd == null) {
                container.getLogger()
                    .error(sm.getString("standardHostValve.customStatusFailed", errorPage.getLocation()));
                return false;
            }

            // 根据响应是否已提交选择不同的处理方式
            if (response.isCommitted()) {
                // 响应已提交，包含错误页面（不清除已有内容）
                rd.include(request.getRequest(), response.getResponse());
                // 刷新缓冲区确保内容发送
                try {
                    response.flushBuffer();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                }
                // 立即关闭连接（指示错误）
                response.getCoyoteResponse().action(ActionCode.CLOSE_NOW,
                    request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
            } else {
                // 响应未提交，重置缓冲区并转发
                response.resetBuffer(true);
                response.setContentLength(-1);
                rd.forward(request.getRequest(), response.getResponse());
                // 恢复响应的正常状态（取消挂起）
                response.setSuspended(false);
            }

            // 标记自定义错误页面处理成功
            return true;

        } catch (Throwable t) {
            // 捕获转发过程中的异常
            ExceptionUtils.handleThrowable(t);
            // 记录转发错误页面时的异常
            container.getLogger().error(sm.getString("standardHostValve.exception", errorPage), t);
            return false;
        }
    }
}