/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.catalina.util;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.catalina.Globals;
import org.apache.catalina.JmxEnabled;
import org.apache.catalina.LifecycleException;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.res.StringManager;

/**
 * LifecycleMBeanBase是一个抽象基类，为支持JMX注册的组件提供生命周期管理功能
 * 它继承自LifecycleBase并实现了JmxEnabled接口
 *
 * 该类提供了MBean注册和注销的基础实现，主要功能包括：
 * 1. 管理MBean的注册状态
 * 2. 提供便捷的注册和注销方法
 * 3. 处理MBean域名和对象名
 *
 * 子类必须实现getDomainInternal()和getObjectNameKeyProperties()方法
 * 以提供特定于组件的JMX注册信息
 */
public abstract class LifecycleMBeanBase extends LifecycleBase implements JmxEnabled {

    private static final Log log = LogFactory.getLog(LifecycleMBeanBase.class);
    private static final StringManager sm = StringManager.getManager("org.apache.catalina.util");

    /* 缓存MBean注册的组件信息 */
    private String domain = null;
    private ObjectName oname = null;

    /**
     * 初始化内部组件
     * 在生命周期的初始化阶段调用，负责MBean的注册
     * 如果oname为null，则使用getObjectNameKeyProperties()方法获取的键属性注册MBean
     * @throws LifecycleException 如果初始化过程中发生错误
     */
    @Override
    protected void initInternal() throws LifecycleException {
        // 如果oname不为null，则表示已经通过preRegister()方法完成注册
        if (oname == null) {
            oname = register(this, getObjectNameKeyProperties());
        }
    }

    /**
     * 销毁内部组件
     * 在生命周期的销毁阶段调用，负责MBean的注销
     * @throws LifecycleException 如果销毁过程中发生错误
     */
    @Override
    protected void destroyInternal() throws LifecycleException {
        unregister(oname);
    }

    /**
     * 设置MBean的域名
     * 此方法由JMX框架调用，最终用户不应直接调用
     * @param domain 要设置的域名
     */
    @Override
    public final void setDomain(String domain) {
        this.domain = domain;
    }

    /**
     * 获取MBean的域名
     * 如果域名为null，则调用getDomainInternal()方法获取
     * 如果仍为null，则使用默认域名
     * @return MBean的域名
     */
    @Override
    public final String getDomain() {
        if (domain == null) {
            domain = getDomainInternal();
        }

        if (domain == null) {
            domain = Globals.DEFAULT_MBEAN_DOMAIN;
        }

        return domain;
    }

    /**
     * 由子类实现的抽象方法，用于确定MBean应注册的域名
     * @return 用于注册MBean的域名
     */
    protected abstract String getDomainInternal();

    /**
     * 获取此组件的ObjectName
     * @return 组件的ObjectName，如果未注册则返回null
     */
    @Override
    public final ObjectName getObjectName() {
        return oname;
    }

    /**
     * 允许子类指定用于注册此组件的ObjectName的键属性部分
     * @return 所需ObjectName的键属性部分的字符串表示
     */
    protected abstract String getObjectNameKeyProperties();

    /**
     * 实用方法，使子类能够轻松地将不实现JmxEnabled的附加组件注册到MBean服务器
     * 注意：此方法只能在initInternal()调用之后和destroyInternal()调用之前使用
     * @param obj 要注册的对象
     * @param objectNameKeyProperties 对象名的键属性部分
     * @return 用于注册对象的ObjectName
     */
    protected final ObjectName register(Object obj, String objectNameKeyProperties) {
        // 构造带有正确域名的对象名
        StringBuilder name = new StringBuilder(getDomain());
        name.append(':');
        name.append(objectNameKeyProperties);

        ObjectName on = null;

        try {
            on = new ObjectName(name.toString());
            Registry.getRegistry(null).registerComponent(obj, on, null);
        } catch (Exception e) {
            log.warn(sm.getString("lifecycleMBeanBase.registerFail", obj, name), e);
        }

        return on;
    }

    /**
     * 实用方法，使子类能够轻松地将不实现JmxEnabled的附加组件从MBean服务器注销
     * 注意：此方法只能在initInternal()调用之后和destroyInternal()调用之前使用
     * @param objectNameKeyProperties 对象名的键属性部分
     */
    protected final void unregister(String objectNameKeyProperties) {
        // 构造带有正确域名的对象名
        String name = getDomain() + ':' + objectNameKeyProperties;
        Registry.getRegistry(null).unregisterComponent(name);
    }

    /**
     * 实用方法，使子类能够轻松地将不实现JmxEnabled的附加组件从MBean服务器注销
     * 注意：此方法只能在initInternal()调用之后和destroyInternal()调用之前使用
     * @param on 要注销的组件的ObjectName
     */
    protected final void unregister(ObjectName on) {
        Registry.getRegistry(null).unregisterComponent(on);
    }

    /**
     * JmxEnabled接口方法的实现，此处为空操作
     */
    @Override
    public final void postDeregister() {
        // NOOP
    }

    /**
     * JmxEnabled接口方法的实现，此处为空操作
     */
    @Override
    public final void postRegister(Boolean registrationDone) {
        // NOOP
    }

    /**
     * JmxEnabled接口方法的实现，此处为空操作
     */
    @Override
    public final void preDeregister() throws Exception {
        // NOOP
    }

    /**
     * 在MBean注册到MBeanServer之前调用
     * 设置ObjectName和domain属性
     * @param server MBeanServer实例
     * @param name 提议的ObjectName
     * @return 实际使用的ObjectName
     * @throws Exception 如果注册过程中发生错误
     */
    @Override
    public final ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
        this.oname = name;
        this.domain = name.getDomain().intern();
        return oname;
    }
}