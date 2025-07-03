/*
 * 版权声明：本接口由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package jakarta.servlet;

import java.io.IOException;

/**
 * 过滤器链接口（Servlet规范核心接口）
 *
 * 核心职责：
 * 1. 定义过滤器之间的调用协议
 * 2. 提供请求和响应在过滤器链中的传递机制
 * 3. 控制过滤器链的执行流程（调用下一个过滤器或目标资源）
 *
 * 设计理念：
 * - 责任链模式的标准接口定义
 * - 解耦过滤器与具体执行逻辑
 * - 支持过滤器链的动态扩展
 *
 * @see Filter 过滤器接口
 * @since Servlet 2.3 规范（2001年引入）
 */
public interface FilterChain {

    /**
     * 执行过滤器链的下一个环节
     *
     * 调用逻辑：
     * 1. 若当前过滤器不是链中最后一个，调用下一个过滤器的doFilter方法
     * 2. 若当前过滤器是最后一个，调用目标资源（Servlet/JSP）的service方法
     *
     * @param request  当前请求对象（可被过滤器修改）
     * @param response 当前响应对象（可被过滤器修改）
     *
     * @throws IOException      处理请求时发生I/O错误（如网络异常）
     * @throws ServletException 处理请求时发生Servlet异常（如业务逻辑错误）
     */
    void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException;
}