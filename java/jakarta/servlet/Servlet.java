/*
 * 版权所有至 Apache 软件基金会（ASF），根据一个或多个贡献者许可协议。有关版权所有权的额外信息，请参阅随附的 NOTICE 文件。
 * ASF 根据 Apache 许可证 2.0 版（“许可证”）向您许可本文件；除非符合许可证，否则您不得使用本文件。
 * 您可以在以下地址获取许可证副本：
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * 除非适用法律要求或书面同意，否则根据许可证分发的软件按“原样”分发，
 * 不附带任何明示或暗示的保证或条件。请参阅许可证，了解管理权限和限制的特定语言。
 */
package jakarta.servlet;

import java.io.IOException;

/**
 * 定义所有 Servlet 必须实现的方法。
 * <p>
 * Servlet 是在 Web 服务器中运行的小型 Java 程序。Servlet 通常通过 HTTP（超文本传输协议）接收和响应来自 Web 客户端的请求。
 * <p>
 * 要实现此接口，您可以编写一个扩展 <code>jakarta.servlet.GenericServlet</code> 的通用 Servlet，
 * 或扩展 <code>jakarta.servlet.http.HttpServlet</code> 的 HTTP Servlet。
 * <p>
 * 此接口定义了初始化 Servlet、处理请求和从服务器移除 Servlet 的方法。这些方法称为生命周期方法，调用顺序如下：
 * <ol>
 * <li>构造 Servlet，然后使用 <code>init</code> 方法初始化。
 * <li>处理来自客户端对 <code>service</code> 方法的任何调用。
 * <li>将 Servlet 停止服务，然后使用 <code>destroy</code> 方法销毁，之后进行垃圾回收和终结。
 * </ol>
 * <p>
 * 除了生命周期方法外，此接口还提供 <code>getServletConfig</code> 方法（Servlet 可用于获取任何启动信息）
 * 和 <code>getServletInfo</code> 方法（允许 Servlet 返回关于自身的基本信息，如作者、版本和版权）。
 *
 * @see GenericServlet
 * @see jakarta.servlet.http.HttpServlet
 */
public interface Servlet {

    /**
     * 由 Servlet 容器调用，指示 Servlet 正在投入服务。
     * <p>
     * Servlet 容器在实例化 Servlet 后仅调用一次 <code>init</code> 方法。
     * <code>init</code> 方法必须成功完成，之后 Servlet 才能接收任何请求。
     * <p>
     * 如果 <code>init</code> 方法：
     * <ol>
     * <li>抛出 <code>ServletException</code>
     * <li>未在 Web 服务器定义的时间段内返回
     * </ol>
     * 则 Servlet 容器无法将 Servlet 投入服务。
     *
     * @param config 包含 Servlet 配置和初始化参数的 <code>ServletConfig</code> 对象
     * @exception ServletException 如果发生干扰 Servlet 正常操作的异常
     * @see UnavailableException
     * @see #getServletConfig
     */
    void init(ServletConfig config) throws ServletException;

    /**
     * 返回一个 {@link ServletConfig} 对象，其中包含此 Servlet 的初始化和启动参数。
     * 返回的 <code>ServletConfig</code> 对象是传递给 <code>init</code> 方法的对象。
     * <p>
     * 此接口的实现负责存储 <code>ServletConfig</code> 对象，以便此方法可以返回它。
     * 实现此接口的 {@link GenericServlet} 类已完成此操作。
     *
     * @return 初始化此 Servlet 的 <code>ServletConfig</code> 对象
     * @see #init
     */
    ServletConfig getServletConfig();

    /**
     * 由 Servlet 容器调用，允许 Servlet 响应请求。
     * <p>
     * 仅在 Servlet 的 <code>init()</code> 方法成功完成后才调用此方法。
     * <p>
     * 对于抛出或发送错误的 Servlet，始终应设置响应的状态码。
     * <p>
     * Servlet 通常在多线程 Servlet 容器中运行，该容器可以同时处理多个请求。
     * 开发人员必须注意同步访问任何共享资源，如文件、网络连接以及 Servlet 的类和实例变量。
     * Java 中多线程编程的更多信息可在 <a href="http://java.sun.com/Series/Tutorial/java/threads/multithreaded.html">
     * Java 多线程编程教程</a> 中找到。
     *
     * @param req 包含客户端请求的 <code>ServletRequest</code> 对象
     * @param res 包含 Servlet 响应的 <code>ServletResponse</code> 对象
     * @exception ServletException 如果发生干扰 Servlet 正常操作的异常
     * @exception IOException      如果发生输入或输出异常
     */
    void service(ServletRequest req, ServletResponse res) throws ServletException, IOException;

    /**
     * 返回关于 Servlet 的信息，如作者、版本和版权。
     * <p>
     * 此方法返回的字符串应为纯文本，不含任何标记（如 HTML、XML 等）。
     *
     * @return 包含 Servlet 信息的 <code>String</code>
     */
    String getServletInfo();

    /**
     * 由 Servlet 容器调用，指示 Servlet 正在停止服务。
     * 仅在 Servlet 的 <code>service</code> 方法内的所有线程已退出或经过超时时间后才调用此方法。
     * Servlet 容器调用此方法后，将不再对此 Servlet 调用 <code>service</code> 方法。
     * <p>
     * 此方法为 Servlet 提供了清理所持有的任何资源（例如内存、文件句柄、线程）的机会，
     * 并确保任何持久状态与内存中的 Servlet 当前状态同步。
     */
    void destroy();
}