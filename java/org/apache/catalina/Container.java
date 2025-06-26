/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.catalina;

import java.beans.PropertyChangeListener;
import java.io.File;

import javax.management.ObjectName;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.juli.logging.Log;

/**
 * Container接口定义了处理客户端请求的核心组件
 * 容器是可以执行从客户端接收的请求并基于这些请求返回响应的对象
 * 容器可以选择支持Valve管道，通过实现Pipeline接口按运行时配置的顺序处理请求
 *
 * 容器在Catalina中存在于多个概念级别，常见示例包括：
 * <ul>
 * <li><b>Engine</b> - 表示整个Catalina servlet引擎，通常包含一个或多个子容器
 * <li><b>Host</b> - 表示包含多个Context的虚拟主机
 * <li><b>Context</b> - 表示单个ServletContext，通常包含多个Servlet的Wrapper
 * <li><b>Wrapper</b> - 表示单个Servlet定义
 * </ul>
 *
 * 容器还可以关联多种支持组件：
 * <ul>
 * <li><b>Loader</b> - 类加载器，用于将容器的新Java类集成到运行Catalina的JVM中
 * <li><b>Logger</b> - 实现ServletContext接口的log()方法
 * <li><b>Manager</b> - 管理与此容器关联的会话池
 * <li><b>Realm</b> - 安全域的只读接口，用于验证用户身份及其相应角色
 * <li><b>Resources</b> - JNDI目录上下文，支持访问静态资源
 * </ul>
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public interface Container extends Lifecycle {

    // ----------------------------------------------------- 常量定义

    /** 当通过addChild()添加子容器时发送的ContainerEvent事件类型 */
    String ADD_CHILD_EVENT = "addChild";

    /** 当通过addValve()添加Valve时发送的ContainerEvent事件类型（如果容器支持管道） */
    String ADD_VALVE_EVENT = "addValve";

    /** 当通过removeChild()移除子容器时发送的ContainerEvent事件类型 */
    String REMOVE_CHILD_EVENT = "removeChild";

    /** 当通过removeValve()移除Valve时发送的ContainerEvent事件类型（如果容器支持管道） */
    String REMOVE_VALVE_EVENT = "removeValve";

    // ------------------------------------------------------------- 属性方法

    /**
     * 获取此容器应记录事件的日志
     * @return 与此容器关联的Log，若无则返回父容器的Log（若有），否则返回null
     */
    Log getLogger();

    /**
     * 返回容器将使用的日志名称
     * @return 用于记录消息的容器缩写名称
     */
    String getLogName();

    /**
     * 获取此容器的JMX名称
     * @return 与此容器关联的JMX名称
     */
    ObjectName getObjectName();

    /**
     * 获取此容器注册所在的JMX域
     * @return JMX域名
     */
    String getDomain();

    /**
     * 计算要添加到对象ObjectName的键属性字符串，
     * 表示该对象与此容器相关联
     * @return 适合附加到ObjectName的字符串
     */
    String getMBeanKeyProperties();

    /**
     * 返回管理与此容器关联的Valve的Pipeline对象
     * @return Pipeline实例
     */
    Pipeline getPipeline();

    /**
     * 获取此容器的Cluster
     * @return 与此容器关联的Cluster，若无则返回父容器的Cluster（若有），否则返回null
     */
    Cluster getCluster();

    /**
     * 设置与此容器关联的Cluster
     * @param cluster 要关联的Cluster
     */
    void setCluster(Cluster cluster);

    /**
     * 获取在此容器及其子容器上调用backgroundProcess方法的延迟
     * @return 调用延迟（秒），非正值表示后台处理由父容器管理
     */
    int getBackgroundProcessorDelay();

    /**
     * 设置在此容器及其子容器上调用backgroundProcess方法的延迟
     * @param delay 调用延迟（秒）
     */
    void setBackgroundProcessorDelay(int delay);

    /**
     * 返回描述此容器的名称字符串（适合人类使用）
     * @return 容器的人类可读名称
     */
    String getName();

    /**
     * 设置描述此容器的名称字符串（适合人类使用）
     * @param name 容器的新名称
     * @exception IllegalStateException 如果此容器已添加到父容器的子容器中（之后名称不可更改）
     */
    void setName(String name);

    /**
     * 获取父容器
     * @return 此容器的父容器，若无则返回null
     */
    Container getParent();

    /**
     * 设置父容器
     * @param container 要添加为此容器父容器的Container
     * @exception IllegalArgumentException 如果此容器拒绝附加到指定容器
     */
    void setParent(Container container);

    /**
     * 获取父类加载器
     * @return 组件的父类加载器，若未设置则返回父容器的父类加载器，若无父容器则返回系统类加载器
     */
    ClassLoader getParentClassLoader();

    /**
     * 设置组件的父类加载器
     * @param parent 新的父类加载器
     */
    void setParentClassLoader(ClassLoader parent);

    /**
     * 获取与此容器关联的Realm
     * @return 关联的Realm，若无则返回父容器的Realm（若有），否则返回null
     */
    Realm getRealm();

    /**
     * 设置与此容器关联的Realm
     * @param realm 新关联的Realm
     */
    void setRealm(Realm realm);

    /**
     * 查找配置资源所在的配置路径
     * @param container 容器实例
     * @param resourceName 资源文件名
     * @return 配置路径
     */
    static String getConfigPath(Container container, String resourceName) {
        StringBuilder result = new StringBuilder();
        Container host = null;
        Container engine = null;
        // 遍历容器层次结构，查找Host和Engine
        while (container != null) {
            if (container instanceof Host) {
                host = container;
            } else if (container instanceof Engine) {
                engine = container;
            }
            container = container.getParent();
        }
        // 构建配置路径
        if (host != null && ((Host) host).getXmlBase() != null) {
            result.append(((Host) host).getXmlBase()).append('/');
        } else {
            result.append("conf/");
            if (engine != null) {
                result.append(engine.getName()).append('/');
            }
            if (host != null) {
                result.append(host.getName()).append('/');
            }
        }
        result.append(resourceName);
        return result.toString();
    }

    /**
     * 返回此容器所属的Service
     * @param container 起始容器
     * @return Service实例，若未找到则返回null
     */
    static Service getService(Container container) {
        // 向上遍历容器层次结构直到找到Engine
        while (container != null && !(container instanceof Engine)) {
            container = container.getParent();
        }
        return container == null ? null : ((Engine) container).getService();
    }

    // --------------------------------------------------------- 公共方法

    /**
     * 执行定期任务（如重新加载等）
     * 此方法将在此容器的类加载上下文中调用，意外异常将被捕获并记录
     */
    void backgroundProcess();

    /**
     * 添加新的子容器
     * 添前必须调用子容器的setParent()方法，传入此容器作为参数
     * @param child 要添加的新子容器
     * @exception IllegalArgumentException 如果子容器的setParent()方法抛出异常，
     * 或新子容器名称与现有子容器不唯一
     * @exception IllegalStateException 如果此容器不支持子容器
     */
    void addChild(Container child);

    /**
     * 添加容器事件监听器
     * @param listener 要添加的监听器
     */
    void addContainerListener(ContainerListener listener);

    /**
     * 添加属性变更监听器
     * @param listener 要添加的监听器
     */
    void addPropertyChangeListener(PropertyChangeListener listener);

    /**
     * 按名称获取子容器
     * @param name 要检索的子容器名称
     * @return 具有给定名称的子容器，若不存在则返回null
     */
    Container findChild(String name);

    /**
     * 获取与此容器关联的子容器
     * @return 包含所有子容器的数组，若无子容器则返回零长度数组
     */
    Container[] findChildren();

    /**
     * 获取与此容器关联的容器监听器
     * @return 包含所有容器监听器的数组，若无监听器则返回零长度数组
     */
    ContainerListener[] findContainerListeners();

    /**
     * 移除与父容器关联的现有子容器
     * @param child 要移除的现有子容器
     */
    void removeChild(Container child);

    /**
     * 移除容器事件监听器
     * @param listener 要移除的监听器
     */
    void removeContainerListener(ContainerListener listener);

    /**
     * 移除属性变更监听器
     * @param listener 要移除的监听器
     */
    void removePropertyChangeListener(PropertyChangeListener listener);

    /**
     * 通知所有容器事件监听器此容器发生了特定事件
     * 默认实现使用调用线程同步执行通知
     * @param type 事件类型
     * @param data 事件数据
     */
    void fireContainerEvent(String type, Object data);

    /**
     * 记录发往此容器但已在处理链中提前处理的请求/响应
     * @param request 要记录的请求（与响应关联）
     * @param response 要记录的响应（与请求关联）
     * @param time 处理请求/响应的时间（毫秒），未知则用0
     * @param useDefault 标志，表示应在引擎的默认访问日志中记录
     */
    void logAccess(Request request, Response response, long time, boolean useDefault);

    /**
     * 获取用于记录发往此容器的请求/响应的AccessLog
     * @return 用于记录请求/响应的AccessLog
     */
    AccessLog getAccessLog();

    /**
     * 获取可用于启动和停止与此容器关联的任何子容器的线程数
     * @return 当前配置的用于启动/停止子容器的线程数
     */
    int getStartStopThreads();

    /**
     * 设置可用于启动和停止与此容器关联的任何子容器的线程数
     * @param startStopThreads 新的线程数
     */
    void setStartStopThreads(int startStopThreads);

    /**
     * 获取CATALINA_BASE的位置
     * @return CATALINA_BASE的位置
     */
    File getCatalinaBase();

    /**
     * 获取CATALINA_HOME的位置
     * @return CATALINA_HOME的位置
     */
    File getCatalinaHome();
}