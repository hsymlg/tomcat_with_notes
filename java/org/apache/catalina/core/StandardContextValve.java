/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina.core;

import java.io.IOException;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ContinueResponseTiming;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.res.StringManager;

/**
 * StandardContext容器的默认基础阀门实现
 *
 * 核心职责：
 * 1. 阻止对WEB-INF和META-INF目录的直接访问
 * 2. 根据请求URI选择对应的Servlet(Wrapper)
 * 3. 将请求转发到选中的Wrapper容器进行处理
 * 4. 处理请求确认(Acknowledgement)机制
 *
 * 使用约束：
 * - 主要用于处理HTTP请求
 * - 依赖Request对象中已解析的Wrapper信息
 */
final class StandardContextValve extends ValveBase {

    // 字符串资源管理器
    private static final StringManager sm = StringManager.getManager(StandardContextValve.class);

    // 构造函数，设置asyncSupported为true表示支持异步处理
    StandardContextValve() {
        super(true);
    }

    /**
     * 核心请求处理方法
     * 实现逻辑：
     * 1. 禁止对WEB-INF和META-INF目录的直接访问（确保 Web 应用的私有资源（如依赖 JAR 包）只能被服务器端代码加载，避免客户端绕过安全限制直接调用。）
     * 2. 从Request中获取目标Wrapper容器
     * 3. 若Wrapper不存在或不可用，返回HTTP 404错误
     * 4. 发送HTTP 100 Continue确认（如果客户端请求了）
     * 5. 检查异步支持状态并传递给Request
     * 6. 将请求传递给Wrapper容器的Pipeline处理
     *
     * @param request  Servlet请求对象
     * @param response Servlet响应对象
     * @throws IOException      输入/输出异常
     * @throws ServletException Servlet处理异常
     */
    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {

        // ------------------------- 步骤1：禁止访问受保护目录 -------------------------
        // 获取请求路径的MessageBytes表示（高效操作字节/字符）
        MessageBytes requestPathMB = request.getRequestPathMB();

        // 检查是否直接访问WEB-INF或META-INF目录
        if ((requestPathMB.startsWithIgnoreCase("/META-INF/", 0)) || (requestPathMB.equalsIgnoreCase("/META-INF")) ||
            (requestPathMB.startsWithIgnoreCase("/WEB-INF/", 0)) || (requestPathMB.equalsIgnoreCase("/WEB-INF"))) {
            // 直接返回404错误，禁止访问
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // ------------------------- 步骤2：获取目标Servlet(Wrapper) -------------------------
        // 从Request中获取对应的Wrapper容器（基于请求URI解析）
        Wrapper wrapper = request.getWrapper();

        // 若Wrapper为空或不可用（如正在卸载）
        if (wrapper == null || wrapper.isUnavailable()) {
            // 返回HTTP 404错误（未找到资源）
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // ------------------------- 步骤3：发送HTTP 100 Continue确认 -------------------------
        try {
            // 发送请求确认（用于HTTP 1.1的Expect: 100-continue机制）
            // 立即发送确认，避免客户端等待
            response.sendAcknowledgement(ContinueResponseTiming.IMMEDIATELY);
        } catch (IOException ioe) {
            // 记录确认失败的异常
            container.getLogger().error(sm.getString("standardContextValve.acknowledgeException"), ioe);
            // 设置错误属性
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, ioe);
            // 返回内部服务器错误
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // ------------------------- 步骤4：处理异步支持 -------------------------
        // 若请求支持异步处理
        if (request.isAsyncSupported()) {
            // 设置请求的异步支持状态为Wrapper容器Pipeline的异步支持状态
            // 确保整个处理链的异步一致性
            request.setAsyncSupported(wrapper.getPipeline().isAsyncSupported());
        }

        // ------------------------- 步骤5：转发请求到Wrapper -------------------------
        // 获取Wrapper容器的Pipeline并调用其第一个Valve
        // 启动Wrapper容器的责任链处理流程（最终会调用Servlet的service方法）
        wrapper.getPipeline().getFirst().invoke(request, response);
    }
}