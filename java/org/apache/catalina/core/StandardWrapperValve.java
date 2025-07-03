/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina.core;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.BadRequestException;
import org.apache.coyote.CloseNowException;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.log.SystemLogHandler;
import org.apache.tomcat.util.res.StringManager;

/**
 * StandardWrapper容器的默认基础阀门实现
 *
 * 核心职责：
 * 1. 管理Servlet实例的生命周期（分配/释放）
 * 2. 构建并执行过滤器链（Filter Chain）
 * 3. 调用Servlet的service方法处理请求
 * 4. 收集请求处理统计信息（耗时、次数等）
 * 5. 处理Servlet不可用和异常情况
 *
 * 设计特点：
 * - 作为Wrapper容器的责任链尾节点，最终调用Servlet
 * - 实现完整的Servlet规范生命周期管理
 * - 提供JMX统计信息收集功能
 */
final class StandardWrapperValve extends ValveBase {

    // 字符串资源管理器，用于加载国际化错误信息
    private static final StringManager sm = StringManager.getManager(StandardWrapperValve.class);

    // ------------------------------------------------------ Constructor

    /**
     * 构造StandardWrapperValve实例
     * 调用父类构造函数并设置asyncSupported为true，表示支持异步处理
     */
    StandardWrapperValve() {
        super(true);
    }

    // ----------------------------------------------------- Instance Variables

    // JMX统计相关字段（性能优化，避免每次请求都访问JMX接口）
    private final LongAdder processingTime = new LongAdder();  // 请求处理总耗时（纳秒）
    private volatile long maxTime = 0;                         // 最大请求处理时间
    private volatile long minTime = Long.MAX_VALUE;            // 最小请求处理时间
    private final AtomicInteger requestCount = new AtomicInteger(0);  // 请求计数
    private final AtomicInteger errorCount = new AtomicInteger(0);    // 错误请求计数

    // --------------------------------------------------------- Public Methods

    /**
     * 核心请求处理方法（最终调用Servlet的service方法）通过过滤器链（ApplicationFilterChain） 间接调用的Servlet的service方法
     * 实现逻辑：
     * 1. 检查Web应用和Servlet的可用性
     * 2. 分配Servlet实例（从Servlet容器中获取）
     * 3. 构建过滤器链（包含所有匹配的过滤器）
     * 4. 调用过滤器链（最终调用Servlet的service方法）
     * 5. 处理各种异常情况（Servlet不可用、IOException等）
     * 6. 释放Servlet实例和过滤器链资源
     * 7. 收集请求处理统计信息
     *
     * @param request  Servlet请求对象
     * @param response Servlet响应对象
     * @throws IOException      输入/输出异常
     * @throws ServletException Servlet处理异常
     */
    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {

        // 初始化本地变量
        boolean unavailable = false;           // Servlet不可用标志
        Throwable throwable = null;            // 异常捕获变量
        long t1 = System.currentTimeMillis();  // 请求开始时间
        requestCount.incrementAndGet();        // 请求计数加1
        StandardWrapper wrapper = (StandardWrapper) getContainer();  // 获取所属的Wrapper容器
        Servlet servlet = null;                // Servlet实例
        Context context = (Context) wrapper.getParent();  // 获取所属的Context容器

        // ------------------------- 步骤1：检查Web应用可用性 -------------------------
        if (!context.getState().isAvailable()) {
            // Web应用不可用（如正在启动/停止）
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                sm.getString("standardContext.isUnavailable"));
            unavailable = true;
        }

        // ------------------------- 步骤2：检查Servlet可用性 -------------------------
        if (!unavailable && wrapper.isUnavailable()) {
            // Servlet不可用（如正在重新加载）
            container.getLogger().info(sm.getString("standardWrapper.isUnavailable", wrapper.getName()));
            checkWrapperAvailable(response, wrapper);  // 处理不可用状态
            unavailable = true;
        }

        // ------------------------- 步骤3：分配Servlet实例 -------------------------
        try {
            if (!unavailable) {
                servlet = wrapper.allocate();  // 从Wrapper中分配Servlet实例
            }
        } catch (UnavailableException e) {
            // Servlet暂时不可用
            container.getLogger().error(sm.getString("standardWrapper.allocateException", wrapper.getName()), e);
            checkWrapperAvailable(response, wrapper);
        } catch (ServletException e) {
            // Servlet分配异常（如初始化失败）
            container.getLogger().error(sm.getString("standardWrapper.allocateException", wrapper.getName()),
                StandardWrapper.getRootCause(e));
            throwable = e;
            exception(request, response, e);  // 处理异常
        } catch (Throwable e) {
            // 其他分配异常（如内存不足）
            ExceptionUtils.handleThrowable(e);
            container.getLogger().error(sm.getString("standardWrapper.allocateException", wrapper.getName()), e);
            throwable = e;
            exception(request, response, e);  // 处理异常
        }

        // ------------------------- 步骤4：设置请求属性 -------------------------
        MessageBytes requestPathMB = request.getRequestPathMB();  // 获取请求路径
        DispatcherType dispatcherType = DispatcherType.REQUEST;
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            dispatcherType = DispatcherType.ASYNC;  // 异步请求处理
        }
        request.setAttribute(Globals.DISPATCHER_TYPE_ATTR, dispatcherType);  // 设置调度类型
        request.setAttribute(Globals.DISPATCHER_REQUEST_PATH_ATTR, requestPathMB);  // 设置请求路径

        // ------------------------- 步骤5：构建过滤器链 -------------------------
        ApplicationFilterChain filterChain = ApplicationFilterFactory.createFilterChain(request, wrapper, servlet);
        // ApplicationFilterFactory负责创建包含所有匹配过滤器的链

        // ------------------------- 步骤6：调用过滤器链（及Servlet） -------------------------
        Container container = this.container;
        try {
            if ((servlet != null) && (filterChain != null)) {
                // 处理输出吞入（如System.out.println会被捕获）
                if (context.getSwallowOutput()) {
                    try {
                        SystemLogHandler.startCapture();  // 开始捕获System.out/err
                        if (request.isAsyncDispatching()) {
                            // 异步请求调度
                            request.getAsyncContextInternal().doInternalDispatch();
                        } else {
                            // 同步请求处理，调用过滤器链
                            filterChain.doFilter(request.getRequest(), response.getResponse());
                        }
                    } finally {
                        // 停止捕获并记录输出
                        String log = SystemLogHandler.stopCapture();
                        if (log != null && !log.isEmpty()) {
                            context.getLogger().info(log);
                        }
                    }
                } else {
                    // 不吞入输出，直接处理
                    if (request.isAsyncDispatching()) {
                        request.getAsyncContextInternal().doInternalDispatch();
                    } else {
                        filterChain.doFilter(request.getRequest(), response.getResponse());
                    }
                }
            }
        } catch (BadRequestException e) {
            // 错误请求（如格式错误）
            if (container.getLogger().isDebugEnabled()) {
                container.getLogger().debug(
                    sm.getString("standardWrapper.serviceException", wrapper.getName(), context.getName()), e);
            }
            throwable = e;
            exception(request, response, e, HttpServletResponse.SC_BAD_REQUEST);  // 处理400错误
        } catch (CloseNowException e) {
            // 客户端请求立即关闭连接
            if (container.getLogger().isDebugEnabled()) {
                container.getLogger().debug(
                    sm.getString("standardWrapper.serviceException", wrapper.getName(), context.getName()), e);
            }
            throwable = e;
            exception(request, response, e);  // 处理异常
        } catch (IOException e) {
            // I/O异常（如网络错误）
            container.getLogger()
                .error(sm.getString("standardWrapper.serviceException", wrapper.getName(), context.getName()), e);
            throwable = e;
            exception(request, response, e);  // 处理异常
        } catch (UnavailableException e) {
            // Servlet不可用（如超时）
            container.getLogger()
                .error(sm.getString("standardWrapper.serviceException", wrapper.getName(), context.getName()), e);
            wrapper.unavailable(e);  // 标记Wrapper不可用
            checkWrapperAvailable(response, wrapper);  // 处理不可用状态
        } catch (ServletException e) {
            // Servlet处理异常
            Throwable rootCause = StandardWrapper.getRootCause(e);
            if (!(rootCause instanceof BadRequestException)) {
                // 记录根本原因（非400错误）
                container.getLogger().error(sm.getString("standardWrapper.serviceExceptionRoot", wrapper.getName(),
                    context.getName(), e.getMessage()), rootCause);
            }
            throwable = e;
            exception(request, response, e);  // 处理异常
        } catch (Throwable e) {
            // 其他未预期的错误
            ExceptionUtils.handleThrowable(e);
            container.getLogger()
                .error(sm.getString("standardWrapper.serviceException", wrapper.getName(), context.getName()), e);
            throwable = e;
            exception(request, response, e);  // 处理异常
        } finally {
            // ------------------------- 步骤7：释放资源 -------------------------
            // 释放过滤器链资源
            if (filterChain != null) {
                filterChain.release();
            }

            // 释放Servlet实例
            try {
                if (servlet != null) {
                    wrapper.deallocate(servlet);  // 将Servlet放回池或销毁
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                container.getLogger().error(sm.getString("standardWrapper.deallocateException", wrapper.getName()), e);
                if (throwable == null) {
                    throwable = e;
                    exception(request, response, e);  // 处理释放异常
                }
            }

            // 处理永久不可用的Servlet（如初始化失败）
            try {
                if ((servlet != null) && (wrapper.getAvailable() == Long.MAX_VALUE)) {
                    wrapper.unload();  // 卸载Servlet
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                container.getLogger().error(sm.getString("standardWrapper.unloadException", wrapper.getName()), e);
                if (throwable == null) {
                    exception(request, response, e);  // 处理卸载异常
                }
            }

            // ------------------------- 步骤8：统计信息收集 -------------------------
            long t2 = System.currentTimeMillis();  // 请求结束时间
            long time = t2 - t1;  // 请求处理耗时
            processingTime.add(time);  // 累加总耗时

            // 更新最大/最小耗时
            if (time > maxTime) {
                maxTime = time;
            }
            if (time < minTime) {
                minTime = time;
            }
        }
    }

    /**
     * 处理Wrapper不可用状态
     *
     * @param response Servlet响应对象
     * @param wrapper  Wrapper容器
     * @throws IOException I/O异常
     */
    private void checkWrapperAvailable(Response response, StandardWrapper wrapper) throws IOException {
        long available = wrapper.getAvailable();  // 获取可用时间（毫秒）
        if ((available > 0L) && (available < Long.MAX_VALUE)) {
            // 暂时不可用，设置重试时间
            response.setDateHeader("Retry-After", available);
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                sm.getString("standardWrapper.isUnavailable", wrapper.getName()));
        } else if (available == Long.MAX_VALUE) {
            // 永久不可用，返回404
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                sm.getString("standardWrapper.notFound", wrapper.getName()));
        }
    }

    // -------------------------------------------------------- Private Methods

    /**
     * 处理异常（默认使用500错误码）
     *
     * @param request    Servlet请求对象
     * @param response   Servlet响应对象
     * @param exception  捕获的异常
     */
    private void exception(Request request, Response response, Throwable exception) {
        exception(request, response, exception, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    /**
     * 处理指定错误码的异常
     *
     * @param request    Servlet请求对象
     * @param response   Servlet响应对象
     * @param exception  捕获的异常
     * @param errorCode  错误状态码
     */
    @SuppressWarnings("deprecation")
    private void exception(Request request, Response response, Throwable exception, int errorCode) {
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, exception);  // 设置错误属性
        response.setStatus(errorCode);  // 设置响应状态码
        response.setError();  // 标记响应为错误状态
    }

    // ----------------------------------------------------- JMX统计方法

    /**
     * 获取请求处理总耗时（毫秒）
     */
    public long getProcessingTime() {
        return processingTime.sum();
    }

    /**
     * 获取最大请求处理时间（毫秒）
     */
    public long getMaxTime() {
        return maxTime;
    }

    /**
     * 获取最小请求处理时间（毫秒）
     */
    public long getMinTime() {
        return minTime;
    }

    /**
     * 获取请求处理次数（JMX统计，Tomcat 11将改为long类型）
     * @deprecated 未来版本将变更返回类型为long
     */
    @Deprecated
    public int getRequestCount() {
        return requestCount.get();
    }

    /**
     * 获取错误请求次数（JMX统计，Tomcat 11将改为long类型）
     * @deprecated 未来版本将变更返回类型为long
     */
    @Deprecated
    public int getErrorCount() {
        return errorCount.get();
    }

    /**
     * 错误请求计数加1
     */
    public void incrementErrorCount() {
        errorCount.incrementAndGet();
    }

    /**
     * 初始化内部资源（覆盖父类方法，避免注册JMX）
     */
    @Override
    protected void initInternal() throws LifecycleException {
        // 空实现 - 不注册此Valve到JMX
    }
}