/*
 * 版权声明：本接口由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina;

import java.io.IOException;

import jakarta.servlet.ServletException;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

/**
 * Tomcat请求处理的阀门接口（责任链模式实现）
 *
 * 设计理念：
 * - 类比真实管道中的阀门，控制和修改请求流
 * - 多个Valve组成Pipeline（管道），形成责任链
 * - 每个Valve可以处理请求、修改请求/响应或传递给下一个Valve
 *
 * 核心方法：invoke(Request request, Response response)，用于传递请求到下一个节点
 * 关键实现：StandardEngineValve、StandardHostValve、StandardContextValve等
 *
 * 典型应用场景：
 * - 日志记录（AccessLogValve）
 * - 安全认证（RemoteAddrValve）
 * - 请求编码处理（CharacterEncodingValve）
 * - 错误处理（ErrorReportValve）
 */
public interface Valve {

    // -------------------------------------------------------------- Properties

    /**
     * 获取责任链中的下一个Valve
     *
     * 返回值说明：
     * - 非null：存在后续Valve
     * - null：当前Valve是责任链的最后一个
     */
    Valve getNext();

    /**
     * 设置责任链中的下一个Valve
     *
     * @param valve 下一个Valve，null表示当前是最后一个
     */
    void setNext(Valve valve);

    // ---------------------------------------------------------- Public Methods

    /**
     * 执行周期性后台任务
     *
     * 应用场景：
     * - 资源监控
     * - 配置重新加载
     * - 状态统计更新
     *
     * 实现注意：
     * - 在容器的类加载上下文中执行
     * - 需处理所有异常并记录日志
     */
    void backgroundProcess();

    /**
     * 核心请求处理方法
     *
     * 处理逻辑规范：
     * 1. 可以检查/修改Request和Response属性
     * 2. 可以完全生成响应并返回（不再调用后续Valve）
     * 3. 可以包装Request/Response后传递给后续Valve
     * 4. 若未生成响应，必须调用getNext().invoke()传递请求
     * 5. 可以检查（但不能修改）后续Valve生成的响应
     *
     * 禁止操作：
     * - 修改已用于请求路由的属性
     * - 生成响应后继续传递请求
     * - 消费请求输入流（除非完全生成响应）
     * - 调用后续Valve后修改响应头
     * - 调用后续Valve后操作响应输出流
     *
     * @param request  Servlet请求对象
     * @param response Servlet响应对象
     * @throws IOException      输入/输出错误
     * @throws ServletException Servlet处理错误
     */
    void invoke(Request request, Response response) throws IOException, ServletException;

    /**
     * 检查是否支持异步处理
     *
     * 返回值说明：
     * - true：支持异步请求处理
     * - false：不支持异步，会导致整个Pipeline不支持异步
     */
    boolean isAsyncSupported();
}