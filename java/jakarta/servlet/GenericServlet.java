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
import java.util.Enumeration;

/**
 * 定义一个通用的、与协议无关的 Servlet。要编写在 Web 上使用的 HTTP Servlet，请改为扩展 {@link jakarta.servlet.http.HttpServlet}。
 * <p>
 * <code>GenericServlet</code> 实现了 <code>Servlet</code> 和 <code>ServletConfig</code> 接口。
 * 尽管更常见的是扩展特定于协议的子类（如 <code>HttpServlet</code>），但 Servlet 也可以直接扩展 <code>GenericServlet</code>。
 * <p>
 * <code>GenericServlet</code> 使编写 Servlet 更加容易。它提供了生命周期方法 <code>init</code> 和 <code>destroy</code>
 * 以及 <code>ServletConfig</code> 接口中方法的简单实现。<code>GenericServlet</code> 还实现了
 * <code>ServletContext</code> 接口中声明的 <code>log</code> 方法。
 * <p>
 * 要编写通用 Servlet，只需覆盖抽象的 <code>service</code> 方法。
 */
public abstract class GenericServlet implements Servlet, ServletConfig, java.io.Serializable {

    // 序列化版本号，用于兼容不同序列化版本
    private static final long serialVersionUID = 1L;

    // 存储 Servlet 配置对象，由 init 方法初始化
    private transient ServletConfig config;

    /**
     * 空构造函数，所有 Servlet 初始化由 init 方法完成
     */
    public GenericServlet() {
        // 无操作
    }

    /**
     * 由 Servlet 容器调用，指示 Servlet 正在停止服务。
     * 默认为空实现，子类可覆盖以清理资源
     * 对应 Servlet 接口的 destroy 方法
     */
    @Override
    public void destroy() {
        // 默认无操作
    }

    /**
     * 获取指定名称的初始化参数值
     * 从 ServletConfig 对象中获取参数值，提供便利访问
     * 对应 ServletConfig 接口的 getInitParameter 方法
     *
     * @param name 初始化参数名称
     * @return 参数值，不存在时返回 null
     */
    @Override
    public String getInitParameter(String name) {
        return getServletConfig().getInitParameter(name);
    }

    /**
     * 获取所有初始化参数名称的枚举
     * 从 ServletConfig 对象中获取参数名称，提供便利访问
     * 对应 ServletConfig 接口的 getInitParameterNames 方法
     *
     * @return 初始化参数名称的枚举，若无参数则返回空枚举
     */
    @Override
    public Enumeration<String> getInitParameterNames() {
        return getServletConfig().getInitParameterNames();
    }

    /**
     * 返回此 Servlet 的 ServletConfig 对象
     * 实现 ServletConfig 接口的 getServletConfig 方法
     *
     * @return 初始化此 Servlet 的 ServletConfig 对象
     */
    @Override
    public ServletConfig getServletConfig() {
        return config;
    }

    /**
     * 返回此 Servlet 运行所在的 ServletContext 对象
     * 从 ServletConfig 中获取上下文，提供便利访问
     * 对应 ServletConfig 接口的 getServletContext 方法
     *
     * @return ServletContext 对象
     */
    @Override
    public ServletContext getServletContext() {
        return getServletConfig().getServletContext();
    }

    /**
     * 返回关于 Servlet 的信息（如作者、版本、版权）
     * 默认返回空字符串，子类可覆盖以提供有意义的信息
     * 实现 Servlet 接口的 getServletInfo 方法
     *
     * @return Servlet 信息字符串，默认为空
     */
    @Override
    public String getServletInfo() {
        return "";
    }

    /**
     * 由 Servlet 容器调用，指示 Servlet 正在投入服务
     * 存储接收到的 ServletConfig 对象，并调用无参 init 方法
     * 实现 Servlet 接口的 init 方法，子类覆盖时需调用 super.init(config)
     *
     * @param config 包含 Servlet 配置信息的 ServletConfig 对象
     * @exception ServletException 初始化异常时抛出
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        this.config = config; // 存储配置对象
        this.init(); // 调用无参 init 方法
    }

    /**
     * 便利初始化方法，可覆盖以避免调用 super.init(config)
     * 由 init(ServletConfig config) 方法调用，子类可覆盖此方法
     *
     * @exception ServletException 初始化异常时抛出
     */
    public void init() throws ServletException {
        // 默认无操作
    }

    /**
     * 将指定消息写入 Servlet 日志文件，前缀为 Servlet 名称
     * 调用 ServletContext 的 log 方法，提供便利日志记录
     *
     * @param message 要写入日志的消息
     */
    public void log(String message) {
        getServletContext().log(getServletName() + ": " + message);
    }

    /**
     * 将解释性消息和异常堆栈跟踪写入 Servlet 日志文件，前缀为 Servlet 名称
     * 调用 ServletContext 的 log 方法，提供便利异常日志记录
     *
     * @param message 描述错误或异常的消息
     * @param t 错误或异常对象
     */
    public void log(String message, Throwable t) {
        getServletContext().log(getServletName() + ": " + message, t);
    }

    /**
     * 由 Servlet 容器调用，允许 Servlet 响应请求
     * 声明为抽象方法，子类（如 HttpServlet）必须覆盖
     * 实现 Servlet 接口的 service 方法
     *
     * @param req 包含客户端请求的 ServletRequest 对象
     * @param res 包含 Servlet 响应的 ServletResponse 对象
     * @exception ServletException 处理请求异常时抛出
     * @exception IOException 输入/输出异常时抛出
     */
    @Override
    public abstract void service(ServletRequest req, ServletResponse res) throws ServletException, IOException;

    /**
     * 返回此 Servlet 实例的名称
     * 从 ServletConfig 中获取 Servlet 名称
     * 实现 ServletConfig 接口的 getServletName 方法
     *
     * @return Servlet 实例名称
     */
    @Override
    public String getServletName() {
        return config.getServletName();
    }
}