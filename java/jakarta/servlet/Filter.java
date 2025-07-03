/*
 * 版权声明：本接口由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package jakarta.servlet;

import java.io.IOException;

/**
 * 过滤器接口（Servlet规范核心接口，实现面向切面编程的基础）
 *
 * 核心职责：
 * 1. 定义过滤器的生命周期方法（init/destroy）
 * 2. 定义请求响应过滤的核心方法（doFilter）
 * 3. 提供访问过滤器配置和Servlet上下文的接口
 *
 * 应用场景：
 * 1. 认证过滤器（Authentication Filters）
 * 2. 日志与审计过滤器（Logging and Auditing Filters）
 * 3. 图像转换过滤器（Image conversion Filters）
 * 4. 数据压缩过滤器（Data compression Filters）
 * 5. 加密过滤器（Encryption Filters）
 * 6. 令牌化过滤器（Tokenizing Filters）
 * 7. 资源访问事件触发器（Resource access event Filters）
 * 8. XSL/T转换过滤器（XSL/T filters）
 * 9. MIME类型链过滤器（Mime-type chain Filter）
 *
 * @since Servlet 2.3 规范（2001年引入）
 */
public interface Filter {

    /**
     * 过滤器初始化方法（容器启动时调用）
     *
     * 调用时机：
     * - 过滤器实例化后，首次处理请求前调用
     * - 每个过滤器实例仅调用一次
     *
     * 实现要求：
     * - 必须成功完成初始化才能处理请求
     * - 若抛出ServletException或超时，容器将无法启用该过滤器
     *
     * @param filterConfig 过滤器配置对象（包含初始化参数和ServletContext引用）
     * @throws ServletException 初始化失败时抛出
     */
    default void init(FilterConfig filterConfig) throws ServletException {
        // 默认实现为空操作（NO-OP），子类可覆盖
    }

    /**
     * 过滤器核心处理方法（请求响应过滤的入口）
     *
     * 处理逻辑模式：
     * 1. 检查请求（Examine the request）
     * 2. 可选：包装请求对象以过滤输入内容或头部
     * 3. 可选：包装响应对象以过滤输出内容或头部
     * 4. 选择：
     *    a) 通过FilterChain调用下一个过滤器/资源（chain.doFilter()）
     *    b) 阻止请求处理（不调用chain.doFilter()）
     * 5. 可选：在调用后续组件后直接设置响应头部
     *
     * @param request 当前请求对象（可被包装修改）
     * @param response 当前响应对象（可被包装修改）
     * @param chain 过滤器链对象（用于调用下一个过滤器或资源）
     * @throws IOException 处理请求响应时发生I/O错误（如文件读写异常）
     * @throws ServletException 处理请求响应时发生业务逻辑错误
     */
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException;

    /**
     * 过滤器销毁方法（容器关闭时调用）
     *
     * 调用时机：
     * - 所有doFilter线程退出后或超时后调用
     * - 调用后容器不再使用该过滤器实例
     *
     * 实现要求：
     * - 释放持有的资源（内存、文件句柄、线程等）
     * - 同步持久化状态（如有）
     */
    default void destroy() {
        // 默认实现为空操作（NO-OP），子类可覆盖
    }
}