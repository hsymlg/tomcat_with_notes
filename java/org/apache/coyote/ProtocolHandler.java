/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.coyote;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.tomcat.util.net.SSLHostConfig;

/**
 * 协议处理器接口，抽象了协议实现，包括线程管理等
 * 这是Coyote协议需要实现的主要接口
 * Adapter是Coyote servlet容器需要实现的主要接口
 *
 * @author Remy Maucherat
 * @author Costin Manolache
 *
 * @see Adapter
 */
public interface ProtocolHandler {

    /**
     * 获取与此协议处理器关联的适配器
     * @return 适配器实例
     */
    Adapter getAdapter();

    /**
     * 设置适配器，用于调用连接器
     * @param adapter 要关联的适配器
     */
    void setAdapter(Adapter adapter);

    /**
     * 获取执行器，提供对底层线程池的访问
     * @return 用于处理请求的执行器
     */
    Executor getExecutor();

    /**
     * 设置连接器将使用的可选执行器
     * @param executor 执行器实例
     */
    void setExecutor(Executor executor);

    /**
     * 获取协议处理器应使用的工具执行器
     * @return 工具执行器
     */
    ScheduledExecutorService getUtilityExecutor();

    /**
     * 设置协议处理器应使用的工具执行器
     * @param utilityExecutor 工具执行器
     */
    void setUtilityExecutor(ScheduledExecutorService utilityExecutor);

    /**
     * 初始化协议
     * @throws Exception 如果协议处理器初始化失败
     */
    void init() throws Exception;

    /**
     * 启动协议
     * @throws Exception 如果协议处理器启动失败
     */
    void start() throws Exception;

    /**
     * 暂停协议（可选操作）
     * @throws Exception 如果协议处理器暂停失败
     */
    void pause() throws Exception;

    /**
     * 恢复协议（可选操作）
     * @throws Exception 如果协议处理器恢复失败
     */
    void resume() throws Exception;

    /**
     * 停止协议
     * @throws Exception 如果协议处理器停止失败
     */
    void stop() throws Exception;

    /**
     * 销毁协议（可选操作）
     * @throws Exception 如果协议处理器销毁失败
     */
    void destroy() throws Exception;

    /**
     * 如果服务器套接字是在start()方法（而不是init()）上绑定的，
     * 则关闭服务器套接字（以防止进一步连接），但不执行任何进一步的关闭操作
     */
    void closeServerSocketGraceful();

    /**
     * 等待客户端连接优雅地关闭。
     * 当所有客户端连接都已关闭或方法已等待了{@code waitTimeMillis}毫秒时，方法将返回。
     * @param waitMillis 等待客户端连接关闭的最大时间（毫秒）
     * @return 方法返回时剩余的等待时间（如果有）
     */
    long awaitConnectionsClose(long waitMillis);

    /**
     * 此协议处理器是否支持sendfile?
     * @return 如果支持sendfile返回true，否则返回false
     */
    boolean isSendfileSupported();

    /**
     * 为虚拟主机添加新的SSL配置
     * @param sslHostConfig SSL配置
     */
    void addSslHostConfig(SSLHostConfig sslHostConfig);

    /**
     * 为虚拟主机添加新的SSL配置
     * @param sslHostConfig SSL配置
     * @param replace 如果为true，则允许替换现有配置，否则任何此类尝试都将触发异常
     * @throws IllegalArgumentException 如果主机名无效，或者已为此主机提供了配置且不允许替换
     */
    void addSslHostConfig(SSLHostConfig sslHostConfig, boolean replace);

    /**
     * 查找将由SNI使用的所有已配置的SSL虚拟主机配置
     * @return SSL配置数组
     */
    SSLHostConfig[] findSslHostConfigs();

    /**
     * 添加用于HTTP/1.1升级或ALPN的新协议
     * @param upgradeProtocol 升级协议
     */
    void addUpgradeProtocol(UpgradeProtocol upgradeProtocol);

    /**
     * 返回所有已配置的升级协议
     * @return 升级协议数组
     */
    UpgradeProtocol[] findUpgradeProtocols();

    /**
     * 某些协议（如AJP）有不应超过的数据包长度，这可用于调整应用层使用的缓冲区
     * @return 所需的缓冲区大小，如果不相关则返回-1
     */
    default int getDesiredBufferSize() {
        return -1;
    }

    /**
     * 默认行为是使用地址和端口唯一标识连接器。
     * 但是，某些连接器不使用此方法，需要一些其他标识符，然后可以用作替换
     * @return 连接器ID
     */
    default String getId() {
        return null;
    }

    /**
     * 为给定协议创建新的ProtocolHandler
     * @param protocol 协议名称或类名
     * @return 新实例化的协议处理器
     * @throws ClassNotFoundException    指定的协议未找到
     * @throws InstantiationException    指定的协议无法实例化
     * @throws IllegalAccessException    发生异常
     * @throws IllegalArgumentException  发生异常
     * @throws InvocationTargetException 发生异常
     * @throws NoSuchMethodException     发生异常
     * @throws SecurityException         发生异常
     */
    static ProtocolHandler create(String protocol)
        throws ClassNotFoundException, InstantiationException, IllegalAccessException, IllegalArgumentException,
        InvocationTargetException, NoSuchMethodException, SecurityException {
        if (protocol == null || "HTTP/1.1".equals(protocol) ||
            org.apache.coyote.http11.Http11NioProtocol.class.getName().equals(protocol)) {
            return new org.apache.coyote.http11.Http11NioProtocol();
        } else if ("AJP/1.3".equals(protocol) ||
            org.apache.coyote.ajp.AjpNioProtocol.class.getName().equals(protocol)) {
            return new org.apache.coyote.ajp.AjpNioProtocol();
        } else {
            // 实例化协议处理器
            Class<?> clazz = Class.forName(protocol);
            return (ProtocolHandler) clazz.getConstructor().newInstance();
        }
    }
}