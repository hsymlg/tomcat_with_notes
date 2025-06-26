/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.catalina;

import javax.management.MBeanRegistration;
import javax.management.ObjectName;

/**
 * JmxEnabled接口定义了可注册到JMX服务器的组件规范
 * 实现此接口的组件将在创建时注册到MBean服务器，在销毁时注销
 * 该接口主要由实现Lifecycle接口的组件实现，但不限于此
 *
 * 组件通过实现此接口，可提供以下JMX相关功能：
 * 1. 注册到特定JMX域名
 * 2. 获取已注册的ObjectName
 * 3. 参与JMX生命周期管理
 */
public interface JmxEnabled extends MBeanRegistration {

    /**
     * 获取组件将被注册到的JMX域名
     * @return JMX域名
     */
    String getDomain();

    /**
     * 设置组件应注册到的JMX域名
     * 用于无法(或难以)通过组件层次结构确定正确域名的组件
     * @param domain 组件应注册到的JMX域名
     */
    void setDomain(String domain);

    /**
     * 获取组件已注册到JMX的ObjectName
     * @return 已注册的ObjectName，如果未注册则返回null
     */
    ObjectName getObjectName();
}