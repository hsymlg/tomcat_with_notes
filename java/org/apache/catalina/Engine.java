/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.catalina;

/**
 * Engine接口表示整个Catalina servlet引擎
 * 它是一个顶级容器，适用于以下场景：
 * <ul>
 * <li>需要拦截器监控整个引擎处理的每个请求
 * <li>使用独立HTTP连接器运行Catalina，但仍需支持多虚拟主机
 * </ul>
 *
 * 通常，当Catalina连接到Web服务器(如Apache)时不使用Engine，
 * 因为连接器会利用Web服务器的功能来确定应该使用哪个Context处理请求。
 *
 * Engine附加的子容器通常是Host(表示虚拟主机)或Context(表示Servlet上下文)的实现。
 * Engine是Catalina容器层次结构中的顶层容器，因此其setParent()方法应抛出IllegalArgumentException。
 *
 * @author Craig R. McClanahan
 */
public interface Engine extends Container {

    /**
     * 获取此Engine的默认主机名
     * @return 默认主机名
     */
    String getDefaultHost();

    /**
     * 设置此Engine的默认主机名
     * @param defaultHost 新的默认主机名
     */
    void setDefaultHost(String defaultHost);

    /**
     * 获取此Engine的JVM路由ID
     * @return JVM路由ID
     */
    String getJvmRoute();

    /**
     * 设置此Engine的JVM路由ID
     * @param jvmRouteId 新的JVM路由ID，集群中的每个Engine必须有唯一的JVM路由ID
     */
    void setJvmRoute(String jvmRouteId);

    /**
     * 获取与此Engine关联的Service
     * @return 关联的Service，若没有则返回null
     */
    Service getService();

    /**
     * 设置与此Engine关联的Service
     * @param service 拥有此Engine的Service
     */
    void setService(Service service);
}