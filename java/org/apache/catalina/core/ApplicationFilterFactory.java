/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina.core;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRequest;

import org.apache.catalina.Globals;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Request;
import org.apache.catalina.util.FilterUtil;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.res.StringManager;

/**
 * 过滤器工厂类（创建过滤器链和管理过滤器缓存）
 *
 * 核心职责：
 * 1. 根据请求和Servlet创建对应的过滤器链
 * 2. 匹配web.xml中定义的FilterMap规则
 * 3. 管理过滤器实例的生命周期
 * 4. 支持不同调度类型（FORWARD/INCLUDE等）的过滤器匹配
 *
 * 设计特点：
 * - 工具类（私有构造函数），提供静态方法
 * - 结合FilterMap规则实现过滤器的动态匹配
 * - 支持过滤器链的复用（非安全模式下）
 */
public final class ApplicationFilterFactory {

    // 日志记录器和字符串资源管理器
    private static final Log log = LogFactory.getLog(ApplicationFilterFactory.class);
    private static final StringManager sm = StringManager.getManager(ApplicationFilterFactory.class);

    // 私有构造函数，防止实例化（工具类）
    private ApplicationFilterFactory() {
        // 防止实例创建，这是一个工具类
    }

    /**
     * 创建并配置过滤器链（核心方法）
     *
     * @param request  当前请求对象
     * @param wrapper  Servlet包装器（Wrapper）
     * @param servlet  Servlet实例
     * @return 配置好的过滤器链，若无过滤器则返回null
     */
    public static ApplicationFilterChain createFilterChain(ServletRequest request, Wrapper wrapper, Servlet servlet) {

        // 若Servlet为null，直接返回null（无过滤器链）
        if (servlet == null) {
            return null;
        }

        // ------------------------- 创建过滤器链实例 -------------------------
        ApplicationFilterChain filterChain;
        if (request instanceof Request) {
            // 请求为Tomcat内部Request对象时
            Request req = (Request) request;
            if (Globals.IS_SECURITY_ENABLED) {
                // 安全模式下：不回收过滤器链，每次新建
                filterChain = new ApplicationFilterChain();
            } else {
                // 非安全模式下：从请求中获取已存在的过滤器链（支持复用）
                filterChain = (ApplicationFilterChain) req.getFilterChain();
                if (filterChain == null) {
                    // 首次访问时创建新的过滤器链并绑定到请求
                    filterChain = new ApplicationFilterChain();
                    req.setFilterChain(filterChain);
                }
            }
        } else {
            // 使用RequestDispatcher时（如转发/包含），新建过滤器链
            filterChain = new ApplicationFilterChain();
        }

        // ------------------------- 初始化过滤器链属性 -------------------------
        filterChain.setServlet(servlet);  // 设置目标Servlet
        filterChain.setServletSupportsAsync(wrapper.isAsyncSupported());  // 设置Servlet异步支持状态

        // ------------------------- 获取Context和FilterMap配置 -------------------------
        StandardContext context = (StandardContext) wrapper.getParent();  // 获取所属的Context
        filterChain.setDispatcherWrapsSameObject(context.getDispatcherWrapsSameObject());  // 设置调度器对象包装策略
        FilterMap[] filterMaps = context.findFilterMaps();  // 获取Context中所有FilterMap配置

        // 若无FilterMap配置，直接返回空过滤器链
        if (filterMaps == null || filterMaps.length == 0) {
            return filterChain;
        }

        // ------------------------- 获取请求匹配所需信息 -------------------------
        DispatcherType dispatcher = (DispatcherType) request.getAttribute(Globals.DISPATCHER_TYPE_ATTR);  // 获取调度类型
        String requestPath = FilterUtil.getRequestPath(request);  // 获取请求路径
        String servletName = wrapper.getName();  // 获取Servlet名称

        // ------------------------- 匹配URL模式的过滤器 -------------------------
        // 先处理按URL模式映射的过滤器
        for (FilterMap filterMap : filterMaps) {
            if (!matchDispatcher(filterMap, dispatcher)) {
                // 调度类型不匹配，跳过
                continue;
            }
            if (!FilterUtil.matchFiltersURL(filterMap, requestPath)) {
                // URL模式不匹配，跳过
                continue;
            }
            // 根据FilterMap获取过滤器配置
            ApplicationFilterConfig filterConfig =
                (ApplicationFilterConfig) context.findFilterConfig(filterMap.getFilterName());
            if (filterConfig == null) {
                // 过滤器配置不存在，记录警告
                log.warn(sm.getString("applicationFilterFactory.noFilterConfig", filterMap.getFilterName()));
                continue;
            }
            // 将过滤器添加到链中
            filterChain.addFilter(filterConfig);
        }

        // ------------------------- 匹配Servlet名称的过滤器 -------------------------
        // 再处理按Servlet名称映射的过滤器（优先级低于URL模式）
        for (FilterMap filterMap : filterMaps) {
            if (!matchDispatcher(filterMap, dispatcher)) {
                // 调度类型不匹配，跳过
                continue;
            }
            if (!matchFiltersServlet(filterMap, servletName)) {
                // Servlet名称不匹配，跳过
                continue;
            }
            // 根据FilterMap获取过滤器配置
            ApplicationFilterConfig filterConfig =
                (ApplicationFilterConfig) context.findFilterConfig(filterMap.getFilterName());
            if (filterConfig == null) {
                // 过滤器配置不存在，记录警告
                log.warn(sm.getString("applicationFilterFactory.noFilterConfig", filterMap.getFilterName()));
                continue;
            }
            // 将过滤器添加到链中
            filterChain.addFilter(filterConfig);
        }

        // 返回配置完成的过滤器链
        return filterChain;
    }

    // -------------------------------------------------------- 私有辅助方法

    /**
     * 检查过滤器映射是否匹配Servlet名称
     *
     * @param filterMap   过滤器映射配置
     * @param servletName 目标Servlet名称
     * @return 是否匹配
     */
    private static boolean matchFiltersServlet(FilterMap filterMap, String servletName) {

        if (servletName == null) {
            // Servlet名称为空，不匹配
            return false;
        }
        // 检查是否匹配所有Servlet（web.xml中<servlet-name>*</servlet-name>）
        else if (filterMap.getMatchAllServletNames()) {
            return true;
        } else {
            // 检查是否匹配具体的Servlet名称列表
            String[] servletNames = filterMap.getServletNames();
            for (String name : servletNames) {
                if (servletName.equals(name)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 检查过滤器映射是否匹配调度类型（FORWARD/INCLUDE等）
     *
     * @param filterMap 过滤器映射配置
     * @param type      调度类型
     * @return 是否匹配
     */
    private static boolean matchDispatcher(FilterMap filterMap, DispatcherType type) {
        // 根据不同调度类型检查FilterMap中的对应标志位
        switch (type) {
            case FORWARD:
                if ((filterMap.getDispatcherMapping() & FilterMap.FORWARD) != 0) {
                    return true;
                }
                break;
            case INCLUDE:
                if ((filterMap.getDispatcherMapping() & FilterMap.INCLUDE) != 0) {
                    return true;
                }
                break;
            case REQUEST:
                if ((filterMap.getDispatcherMapping() & FilterMap.REQUEST) != 0) {
                    return true;
                }
                break;
            case ERROR:
                if ((filterMap.getDispatcherMapping() & FilterMap.ERROR) != 0) {
                    return true;
                }
                break;
            case ASYNC:
                if ((filterMap.getDispatcherMapping() & FilterMap.ASYNC) != 0) {
                    return true;
                }
                break;
        }
        return false;
    }
}