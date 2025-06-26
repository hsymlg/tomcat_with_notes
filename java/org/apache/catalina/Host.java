/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.catalina;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * Host接口表示Catalina servlet引擎中的虚拟主机
 * 适用于以下场景：
 * <ul>
 * <li>需要拦截器监控此虚拟主机处理的每个请求
 * <li>使用独立HTTP连接器运行Catalina，但需要支持多个虚拟主机
 * </ul>
 * 通常，当Catalina连接到Web服务器(如Apache)时不使用Host，
 * 因为连接器会利用Web服务器功能确定处理请求的Context或Wrapper
 *
 * Host的父容器通常是Engine，其子容器通常是Context实现
 *
 * @author Craig R. McClanahan
 */
public interface Host extends Container {

    // ----------------------------------------------------- 常量定义

    /** 当通过addAlias()添加新别名时发送的ContainerEvent事件类型 */
    String ADD_ALIAS_EVENT = "addAlias";

    /** 当通过removeAlias()移除旧别名时发送的ContainerEvent事件类型 */
    String REMOVE_ALIAS_EVENT = "removeAlias";

    // ------------------------------------------------------------- 属性方法

    /**
     * 获取此Host的XML根目录
     * 可以是绝对路径或相对路径，若为null则默认为${catalina.base}/conf/&lt;engine name&gt;/&lt;host name&gt;目录
     * @return XML根目录路径
     */
    String getXmlBase();

    /**
     * 设置此Host的XML根目录
     * 可以是绝对路径或相对路径，若为null则使用默认目录
     * @param xmlBase 新的XML根目录路径
     */
    void setXmlBase(String xmlBase);

    /**
     * 获取此Host的默认配置路径文件
     * 文件将尽可能返回规范形式
     * @return 配置基础文件
     */
    File getConfigBaseFile();

    /**
     * 获取此Host的应用程序根目录
     * 可以是绝对路径、相对路径或URL
     * @return 应用程序根目录路径
     */
    String getAppBase();

    /**
     * 获取此Host的应用程序根目录的绝对文件表示
     * 文件将尽可能返回规范形式，不保证目录存在
     * @return 应用程序根目录文件
     */
    File getAppBaseFile();

    /**
     * 设置此Host的应用程序根目录
     * 可以是绝对路径、相对路径或URL
     * @param appBase 新的应用程序根目录路径
     */
    void setAppBase(String appBase);

    /**
     * 获取此Host的传统(Java EE)应用程序根目录
     * 可以是绝对路径、相对路径或URL
     * @return 传统应用程序根目录路径
     */
    String getLegacyAppBase();

    /**
     * 获取此Host的传统(Java EE)应用程序根目录的绝对文件表示
     * 文件将尽可能返回规范形式，不保证目录存在
     * @return 传统应用程序根目录文件
     */
    File getLegacyAppBaseFile();

    /**
     * 设置此Host的传统(Java EE)应用程序根目录
     * 可以是绝对路径、相对路径或URL
     * @param legacyAppBase 新的传统应用程序根目录路径
     */
    void setLegacyAppBase(String legacyAppBase);

    /**
     * 获取自动部署标志值
     * 若为true，表示应自动发现并部署此主机的子Web应用程序
     * @return 自动部署标志
     */
    boolean getAutoDeploy();

    /**
     * 设置此主机的自动部署标志值
     * @param autoDeploy 新的自动部署标志
     */
    void setAutoDeploy(boolean autoDeploy);

    /**
     * 获取新Web应用程序的上下文配置类的Java类名
     * @return 上下文配置类名
     */
    String getConfigClass();

    /**
     * 设置新Web应用程序的上下文配置类的Java类名
     * @param configClass 新的上下文配置类名
     */
    void setConfigClass(String configClass);

    /**
     * 获取启动时部署标志值
     * 若为true，表示应自动发现并部署此主机的子Web应用程序
     * @return 启动时部署标志
     */
    boolean getDeployOnStartup();

    /**
     * 设置此主机的启动时部署标志值
     * @param deployOnStartup 新的启动时部署标志
     */
    void setDeployOnStartup(boolean deployOnStartup);

    /**
     * 获取自动部署过程中忽略的文件和目录的正则表达式
     * @return 忽略模式字符串
     */
    String getDeployIgnore();

    /**
     * 获取自动部署过程中忽略的文件和目录的编译正则表达式
     * @return 编译后的忽略模式
     */
    Pattern getDeployIgnorePattern();

    /**
     * 设置自动部署过程中忽略的文件和目录的正则表达式
     * @param deployIgnore 匹配文件名的正则表达式
     */
    void setDeployIgnore(String deployIgnore);

    /**
     * 获取用于启动和停止上下文的执行器
     * 主要供需要以多线程方式部署上下文的组件使用
     * @return 启动/停止执行器
     */
    ExecutorService getStartStopExecutor();

    /**
     * 获取是否尝试创建appBase和xmlBase目录的标志
     * @return 若Host将尝试创建目录则为true
     */
    boolean getCreateDirs();

    /**
     * 设置Host是否应在启动时尝试创建xmlBase和appBase目录
     * @param createDirs 此标志的新值
     */
    void setCreateDirs(boolean createDirs);

    /**
     * 获取是否自动取消部署旧版本应用程序的标志
     * 仅当getAutoDeploy()也返回true时生效
     * @return 若自动取消部署旧版本则为true
     */
    boolean getUndeployOldVersions();

    /**
     * 设置是否自动取消部署使用并行部署的应用程序的旧版本
     * 仅当getAutoDeploy()返回true时生效
     * @param undeployOldVersions 此标志的新值
     */
    void setUndeployOldVersions(boolean undeployOldVersions);

    // --------------------------------------------------------- 公共方法

    /**
     * 添加应映射到此Host的别名
     * @param alias 要添加的别名
     */
    void addAlias(String alias);

    /**
     * 获取此Host的别名数组
     * 若无别名定义，返回零长度数组
     * @return 别名数组
     */
    String[] findAliases();

    /**
     * 从此Host的别名中移除指定的别名
     * @param alias 要移除的别名
     */
    void removeAlias(String alias);
}