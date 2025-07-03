/*
 * 版权声明：本接口由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package jakarta.servlet;

import java.util.Enumeration;

/**
 * 过滤器配置接口（Servlet容器用于在过滤器初始化时传递配置信息）
 *
 * 核心职责：
 * 1. 提供过滤器的基本信息（名称）
 * 2. 暴露ServletContext接口（访问容器上下文）
 * 3. 提供初始化参数的访问接口
 *
 * 设计理念：
 * - 解耦过滤器与容器的具体实现
 * - 规范过滤器获取配置信息的标准接口
 * - 支持过滤器在初始化时获取必要的配置参数
 *
 * @see Filter 过滤器接口（与本接口配合使用）
 * @since Servlet 2.3 规范（2001年引入）
 */
public interface FilterConfig {

    /**
     * 获取过滤器名称（由部署描述符中配置的filter-name决定）
     *
     * @return 过滤器在web.xml中配置的名称
     */
    String getFilterName();

    /**
     * 获取ServletContext实例（过滤器通过此接口访问容器环境）
     *
     * @return 当前过滤器所属的ServletContext对象
     * @see ServletContext Servlet上下文接口（提供容器环境访问）
     */
    ServletContext getServletContext();

    /**
     * 获取过滤器初始化参数值（由部署描述符中filter-init-param配置）
     *
     * @param name 初始化参数的名称
     * @return 参数对应的值，若参数不存在则返回null
     */
    String getInitParameter(String name);

    /**
     * 获取所有初始化参数名称的枚举（用于遍历过滤器的所有初始化参数）
     *
     * @return 包含所有初始化参数名称的Enumeration，若无参数则返回空枚举
     */
    Enumeration<String> getInitParameterNames();
}