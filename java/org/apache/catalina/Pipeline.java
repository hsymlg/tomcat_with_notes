/*
 * 版权声明：本接口由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina;

import java.util.Set;

/**
 * Tomcat请求处理的管道模式接口
 *
 * 设计理念：
 * - 责任链模式的实现，允许请求在多个Valve间依次传递处理
 * - 每个Container（如Engine、Host、Context）都关联一个Pipeline
 * - 管道中的最后一个Valve（basic Valve）通常负责核心处理逻辑
 *
 * 工作流程：
 * 1. 请求进入Pipeline后，按添加顺序依次通过各个Valve
 * 2. 每个Valve可以选择处理请求、修改请求或直接传递给下一个Valve
 * 3. 最终由basic Valve完成请求的核心处理并生成响应
 * 4. 响应按相反顺序通过各个Valve返回客户端
 */
public interface Pipeline extends Contained {

    /**
     * 获取管道的基础Valve
     *
     * 基础Valve是管道中最后执行的Valve，通常负责核心处理逻辑
     * 例如，Context容器的基础Valve负责调用Servlet.service()方法
     */
    Valve getBasic();

    /**
     * 设置管道的基础Valve
     *
     * 实现注意：
     * - 设置前会调用Valve.setContainer()方法关联当前容器
     * - 可能抛出IllegalArgumentException或IllegalStateException
     * - 成功设置后应触发Container.ADD_VALVE_EVENT事件
     */
    void setBasic(Valve valve);

    /**
     * 向管道末尾添加一个Valve
     *
     * 添加顺序决定了Valve的执行顺序（basic Valve除外）
     *
     * 实现注意：
     * - 添加前会调用Valve.setContainer()方法关联当前容器
     * - 可能抛出IllegalArgumentException或IllegalStateException
     * - 成功添加后应触发Container.ADD_VALVE_EVENT事件
     */
    void addValve(Valve valve);

    /**
     * 获取管道中所有的Valve
     *
     * 返回数组包含所有普通Valve和基础Valve
     * 若管道为空，返回长度为0的数组
     */
    Valve[] getValves();

    /**
     * 从管道中移除指定的Valve
     *
     * 移除后会调用Valve.setContainer(null)方法解除关联
     * 若Valve不存在，不执行任何操作
     *
     * 实现注意：
     * - 成功移除后应触发Container.REMOVE_VALVE_EVENT事件
     */
    void removeValve(Valve valve);

    /**
     * 获取管道中第一个执行的Valve
     *
     * 通常是第一个添加的Valve，但实现可能有特殊处理
     */
    Valve getFirst();

    /**
     * 检查管道是否支持异步处理
     *
     * 当且仅当所有Valve都支持异步时返回true
     * 用于确定容器是否可以处理异步请求
     */
    boolean isAsyncSupported();

    /**
     * 查找不支持异步处理的Valve
     *
     * 将不支持异步的Valve类名添加到指定集合中
     * 用于诊断和调试异步处理相关问题
     */
    void findNonAsyncValves(Set<String> result);
}