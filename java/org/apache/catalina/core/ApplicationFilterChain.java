/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina.core;

import java.io.IOException;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.util.Arrays;
import java.util.Set;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityUtil;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

/**
 * 过滤器链的具体实现类（Servlet规范FilterChain接口的Tomcat实现）
 *
 * 核心职责：
 * 1. 管理过滤器链的执行流程（按顺序调用过滤器）
 * 2. 维护过滤器数组和当前执行位置
 * 3. 处理过滤器与Servlet之间的调用转换
 * 4. 支持安全模式下的权限控制
 * 5. 处理异步请求的过滤器支持检测
 *
 * 设计特点：
 * - 责任链模式的具体实现
 * - 支持过滤器链的复用（非安全模式下）
 * - 数组实现过滤器存储，支持动态扩容
 * - 线程本地变量跟踪请求响应状态
 */
public final class ApplicationFilterChain implements FilterChain {

    // 用于实现SRV.8.2/SRV.14.2.5.1规范要求的线程本地变量
    private static final ThreadLocal<ServletRequest> lastServicedRequest = new ThreadLocal<>();
    private static final ThreadLocal<ServletResponse> lastServicedResponse = new ThreadLocal<>();

    // -------------------------------------------------------------- 常量定义

    // 过滤器数组扩容步长（每次扩容增加10个位置）
    public static final int INCREMENT = 10;

    // ----------------------------------------------------- 实例变量

    /** 过滤器配置数组（存储ApplicationFilterConfig实例） */
    private ApplicationFilterConfig[] filters = new ApplicationFilterConfig[0];

    /** 当前执行的过滤器索引（从0开始） */
    private int pos = 0;

    /** 过滤器链中的过滤器数量 */
    private int n = 0;

    /** 过滤器链执行完毕后要调用的Servlet实例 */
    private Servlet servlet = null;

    /** 目标Servlet是否支持异步处理 */
    private boolean servletSupportsAsync = false;

    /** 调度器是否包装相同对象（用于请求作用域检查） */
    private boolean dispatcherWrapsSameObject = false;

    /** 字符串资源管理器（用于国际化错误信息） */
    private static final StringManager sm = StringManager.getManager(ApplicationFilterChain.class);

    /** 安全模式下doFilter方法的参数类型数组 */
    private static final Class<?>[] classType =
        new Class[] { ServletRequest.class, ServletResponse.class, FilterChain.class };

    /** 安全模式下Servlet service方法的参数类型数组 */
    private static final Class<?>[] classTypeUsedInService =
        new Class[] { ServletRequest.class, ServletResponse.class };

    // ---------------------------------------------------- FilterChain接口实现

    /**
     * 执行过滤器链的核心方法（实现FilterChain接口）
     *
     * 处理逻辑：
     * 1. 安全模式下使用AccessController执行特权操作
     * 2. 调用internalDoFilter执行实际的过滤器链逻辑
     * 3. 处理特权操作异常并转换为Servlet规范异常
     *
     * @param request 当前请求对象
     * @param response 当前响应对象
     * @throws IOException Servlet规范定义的I/O异常
     * @throws ServletException Servlet规范定义的处理异常
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {

        if (Globals.IS_SECURITY_ENABLED) {
            // 安全模式启用时，使用特权操作执行
            final ServletRequest req = request;
            final ServletResponse res = response;
            try {
                java.security.AccessController.doPrivileged((java.security.PrivilegedExceptionAction<Void>) () -> {
                    internalDoFilter(req, res);
                    return null;
                });
            } catch (PrivilegedActionException pe) {
                // 解包特权操作异常并转换为对应异常类型
                Exception e = pe.getException();
                if (e instanceof ServletException) {
                    throw (ServletException) e;
                } else if (e instanceof IOException) {
                    throw (IOException) e;
                } else if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                } else {
                    throw new ServletException(e.getMessage(), e);
                }
            }
        } else {
            // 非安全模式直接调用内部处理方法
            internalDoFilter(request, response);
        }
    }

    /**
     * 过滤器链的内部执行方法（核心逻辑）
     *
     * 执行流程：
     * 1. 若还有未执行的过滤器，调用下一个过滤器
     * 2. 若所有过滤器执行完毕，调用目标Servlet的service方法
     * 3. 处理过滤器和Servlet执行中的异常
     * 4. 维护线程本地变量跟踪请求状态
     *
     * @param request 当前请求对象
     * @param response 当前响应对象
     * @throws IOException Servlet规范定义的I/O异常
     * @throws ServletException Servlet规范定义的处理异常
     */
    private void internalDoFilter(ServletRequest request, ServletResponse response)
        throws IOException, ServletException {

        // 调用下一个过滤器（如果存在）
        if (pos < n) {
            ApplicationFilterConfig filterConfig = filters[pos++];  // 获取当前过滤器并递增索引
            try {
                Filter filter = filterConfig.getFilter();  // 获取过滤器实例

                // 检查异步支持：若过滤器不支持异步，标记请求异步支持为false
                if (request.isAsyncSupported() && !(filterConfig.getFilterDef().getAsyncSupportedBoolean())) {
                    request.setAttribute(Globals.ASYNC_SUPPORTED_ATTR, Boolean.FALSE);
                }

                // 安全模式下使用特权操作调用过滤器
                if (Globals.IS_SECURITY_ENABLED) {
                    final ServletRequest req = request;
                    final ServletResponse res = response;
                    Principal principal = ((HttpServletRequest) req).getUserPrincipal();  // 获取用户主体

                    Object[] args = new Object[] { req, res, this };  // 构建参数数组
                    SecurityUtil.doAsPrivilege("doFilter", filter, classType, args, principal);
                } else {
                    // 非安全模式直接调用过滤器的doFilter方法
                    filter.doFilter(request, response, this);
                }
            } catch (IOException | ServletException | RuntimeException e) {
                // 直接抛出已知类型异常
                throw e;
            } catch (Throwable e) {
                // 处理未知Throwable（如Error）
                e = ExceptionUtils.unwrapInvocationTargetException(e);
                ExceptionUtils.handleThrowable(e);
                throw new ServletException(sm.getString("filterChain.filter"), e);
            }
            return;
        }

        // 所有过滤器执行完毕，调用目标Servlet
        try {
            if (dispatcherWrapsSameObject) {
                // 记录当前请求响应到线程本地变量（用于作用域检查）
                lastServicedRequest.set(request);
                lastServicedResponse.set(response);
            }

            // 检查Servlet异步支持：若Servlet不支持异步，标记请求异步支持为false
            if (request.isAsyncSupported() && !servletSupportsAsync) {
                request.setAttribute(Globals.ASYNC_SUPPORTED_ATTR, Boolean.FALSE);
            }

            // 使用可能被包装的请求对象调用Servlet的service方法
            if ((request instanceof HttpServletRequest) && (response instanceof HttpServletResponse) &&
                Globals.IS_SECURITY_ENABLED) {
                final ServletRequest req = request;
                final ServletResponse res = response;
                Principal principal = ((HttpServletRequest) req).getUserPrincipal();  // 获取用户主体

                Object[] args = new Object[] { req, res };  // 构建参数数组
                SecurityUtil.doAsPrivilege("service", servlet, classTypeUsedInService, args, principal);
            } else {
                // 非安全模式直接调用Servlet的service方法
                servlet.service(request, response);
            }
        } catch (IOException | ServletException | RuntimeException e) {
            // 直接抛出已知类型异常
            throw e;
        } catch (Throwable e) {
            // 处理未知Throwable（如Error）
            e = ExceptionUtils.unwrapInvocationTargetException(e);
            ExceptionUtils.handleThrowable(e);
            throw new ServletException(sm.getString("filterChain.servlet"), e);
        } finally {
            if (dispatcherWrapsSameObject) {
                // 清除线程本地变量（防止内存泄漏）
                lastServicedRequest.set(null);
                lastServicedResponse.set(null);
            }
        }
    }

    /**
     * 获取当前线程中最后一个被服务的请求（用于规范要求的作用域检查）
     * @return 最后一个服务的请求对象，若无则为null
     */
    public static ServletRequest getLastServicedRequest() {
        return lastServicedRequest.get();
    }

    /**
     * 获取当前线程中最后一个被服务的响应（用于规范要求的作用域检查）
     * @return 最后一个服务的响应对象，若无则为null
     */
    public static ServletResponse getLastServicedResponse() {
        return lastServicedResponse.get();
    }

    // -------------------------------------------------------- 包级访问方法

    /**
     * 向过滤器链中添加一个过滤器（由ApplicationFilterFactory调用）
     * 1.通过 web.xml 中的<filter>和<filter-mapping>配置
     * 2.注解配置（Servlet 3.0+）：通过@WebFilter注解声明的过滤器，会被容器解析为等价的 filter-map 配置
     * 3.Tomcat 内置的过滤器（如CharacterEncodingFilter），由容器自动添加
     * @param filterConfig 要添加的过滤器配置
     */
    void addFilter(ApplicationFilterConfig filterConfig) {

        // 防止重复添加同一个过滤器
        for (int i = 0; i < n; i++) {
            if (filters[i] == filterConfig) {
                return;
            }
        }

        // 数组容量不足时扩容（每次增加INCREMENT个位置）
        if (n == filters.length) {
            filters = Arrays.copyOf(filters, n + INCREMENT);
        }
        filters[n++] = filterConfig;  // 添加过滤器并递增数量
    }

    /**
     * 释放过滤器链占用的资源（用于对象池复用）
     * 重置所有引用和计数器，准备重新使用
     */
    void release() {
        for (int i = 0; i < n; i++) {
            filters[i] = null;  // 清空过滤器引用
        }
        n = 0;  // 重置过滤器数量
        pos = 0;  // 重置当前位置
        servlet = null;  // 清空Servlet引用
        servletSupportsAsync = false;  // 重置异步支持状态
        dispatcherWrapsSameObject = false;  // 重置调度器包装状态
    }

    /**
     * 重置过滤器链用于复用（不清空引用，仅重置位置）
     */
    void reuse() {
        pos = 0;  // 重置当前执行位置
    }

    /**
     * 设置过滤器链执行完毕后要调用的Servlet
     * @param servlet 目标Servlet实例
     */
    void setServlet(Servlet servlet) {
        this.servlet = servlet;
    }

    /**
     * 设置目标Servlet的异步支持状态
     * @param servletSupportsAsync Servlet是否支持异步
     */
    void setServletSupportsAsync(boolean servletSupportsAsync) {
        this.servletSupportsAsync = servletSupportsAsync;
    }

    /**
     * 设置调度器是否包装相同对象（用于请求作用域检查）
     * @param dispatcherWrapsSameObject 调度器包装标志
     */
    void setDispatcherWrapsSameObject(boolean dispatcherWrapsSameObject) {
        this.dispatcherWrapsSameObject = dispatcherWrapsSameObject;
    }

    /**
     * 查找过滤器链中不支持异步处理的过滤器
     * @param result 用于存储不支持异步的过滤器类名的集合
     */
    public void findNonAsyncFilters(Set<String> result) {
        for (int i = 0; i < n; i++) {
            ApplicationFilterConfig filter = filters[i];
            if (!(filter.getFilterDef().getAsyncSupportedBoolean())) {
                result.add(filter.getFilterClass());  // 添加不支持异步的过滤器类名
            }
        }
    }
}