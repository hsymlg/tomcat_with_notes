/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.coyote.http11;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;

/**
 * 使用NIO实现的HTTP/1.1协议处理器
 * 该类负责处理HTTP/1.1协议相关的请求和响应，基于Java NIO（非阻塞I/O）技术实现
 * 通过Selector和Channel实现高效的并发连接处理
 *
 * ProtocolHandler (接口)
 * 最顶层的接口，定义了协议处理器的基本行为
 * 包含生命周期方法 (init/start/stop 等)、组件协作方法、SSL 和协议升级支持等
 * 提供了静态工厂方法用于创建具体协议处理器实例
 *     ↓
 * AbstractProtocol (抽象类)
 * 实现了 ProtocolHandler 接口
 * 提供了协议处理器的通用实现
 * 包含连接处理、处理器管理、线程池管理等核心功能
 * 定义了抽象方法，由具体协议实现类完成特定协议处理逻辑
 *     ↓
 * AbstractHttp11Protocol (抽象类)
 * 继承自 AbstractProtocol
 * 专门为 HTTP/1.1 协议提供通用实现
 * 包含 HTTP/1.1 特定的解析逻辑、请求处理和响应生成
 *     ↓
 * AbstractHttp11JsseProtocol (抽象类)
 * 继承自 AbstractHttp11Protocol
 * 专门为支持 SSL/TLS 的 HTTP/1.1 协议提供通用实现
 * 包含 SSL 配置管理、证书处理、加密通信等功能
 *     ↓
 * Http11NioProtocol (具体类)
 * 继承自 AbstractHttp11JsseProtocol
 * 实现了基于 NIO (Non-blocking I/O) 的 HTTP/1.1 协议处理器
 * 使用 Java NIO 库实现高性能、非阻塞的 HTTP 通信
 *
 */
public class Http11NioProtocol extends AbstractHttp11JsseProtocol<NioChannel> {

    // 日志记录器，用于输出协议处理器相关日志
    private static final Log log = LogFactory.getLog(Http11NioProtocol.class);

    /**
     * 默认构造函数
     * 创建一个新的Http11NioProtocol实例，并关联一个默认的NioEndpoint
     */
    public Http11NioProtocol() {
        this(new NioEndpoint());
    }

    /**
     * 带指定端点的构造函数
     * @param endpoint 关联的NioEndpoint实例，用于处理底层网络I/O
     */
    public Http11NioProtocol(NioEndpoint endpoint) {
        super(endpoint);
    }

    /**
     * 获取日志记录器实例
     * @return 日志记录器
     */
    @Override
    protected Log getLog() {
        return log;
    }

    // -------------------- 选择器设置 --------------------

    /**
     * 设置Selector的超时时间（毫秒）
     * 该超时时间用于控制Selector在没有事件发生时的阻塞时间
     * @param timeout 超时时间（毫秒）
     */
    public void setSelectorTimeout(long timeout) {
        ((NioEndpoint) getEndpoint()).setSelectorTimeout(timeout);
    }

    /**
     * 获取Selector的超时时间
     * @return 超时时间（毫秒）
     */
    public long getSelectorTimeout() {
        return ((NioEndpoint) getEndpoint()).getSelectorTimeout();
    }

    /**
     * 设置Poller线程的优先级
     * Poller线程负责监听Selector上的I/O事件
     * @param threadPriority 线程优先级（1-10）
     */
    public void setPollerThreadPriority(int threadPriority) {
        ((NioEndpoint) getEndpoint()).setPollerThreadPriority(threadPriority);
    }

    /**
     * 获取Poller线程的优先级
     * @return 线程优先级
     */
    public int getPollerThreadPriority() {
        return ((NioEndpoint) getEndpoint()).getPollerThreadPriority();
    }

    /**
     * 获取协议处理器的名称前缀
     * 用于在日志和JMX中标识该协议处理器实例
     * @return 名称前缀（如"http-nio"或"https-nio"）
     */
    @Override
    protected String getNamePrefix() {
        if (isSSLEnabled()) {
            // 如果启用了SSL/TLS，使用https前缀并包含SSL实现名称
            return "https-" + getSslImplementationShortName() + "-nio";
        } else {
            // 未启用SSL，使用http前缀
            return "http-nio";
        }
    }
}