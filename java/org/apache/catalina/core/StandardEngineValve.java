/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina.core;

import java.io.IOException;

import jakarta.servlet.ServletException;

import org.apache.catalina.Host;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

/**
 * StandardEngine容器的默认基础阀门实现
 *
 * 核心职责：
 * 1. 根据请求的Host头部选择对应的虚拟主机(Host)容器
 * 2. 将请求转发到选中的Host容器进行处理
 * 3. 处理Host未找到的错误情况
 *
 * 使用约束：
 * - 该实现主要用于处理HTTP请求
 * - 依赖于Request对象中已解析的Host信息
 */
final class StandardEngineValve extends ValveBase {

    // ------------------------------------------------------ Constructor

    /**
     * 构造StandardEngineValve实例
     * 调用父类构造函数并设置asyncSupported为true，表示支持异步处理
     */
    StandardEngineValve() {
        super(true);
    }

    // --------------------------------------------------------- Public Methods

    /**
     * 核心请求处理方法
     * 实现逻辑：
     * 1. 从Request中获取目标Host容器
     * 2. 若Host不存在，返回HTTP 404错误
     * 3. 若Host存在，检查异步支持状态
     * 4. 将请求传递给Host容器的Pipeline进行后续处理
     *
     * @param request  Servlet请求对象
     * @param response Servlet响应对象
     * @throws IOException      输入/输出异常
     * @throws ServletException Servlet处理异常
     */
    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {

        // ------------------------- 步骤1：获取请求目标Host -------------------------
        // 从Request中获取对应的Host容器（基于HTTP请求头的Host字段）
        Host host = request.getHost();

        // 若Host为空（如HTTP 0.9请求或无效请求）
        if (host == null) {
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
            // 设置请求的异步支持状态为Host容器Pipeline的异步支持状态
            // 确保整个处理链的异步一致性
            request.setAsyncSupported(host.getPipeline().isAsyncSupported());
        }

        // ------------------------- 步骤3：转发请求到Host -------------------------
        // 获取Host容器的Pipeline并调用其第一个Valve
        // 启动Host容器的责任链处理流程
        host.getPipeline().getFirst().invoke(request, response);
    }
}