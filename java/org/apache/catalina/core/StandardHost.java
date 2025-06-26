/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.catalina.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

import javax.management.ObjectName;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Globals;
import org.apache.catalina.Host;
import org.apache.catalina.JmxEnabled;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Valve;
import org.apache.catalina.loader.WebappClassLoaderBase;
import org.apache.catalina.util.ContextName;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;

/**
 * Host接口的标准实现
 * 每个子容器必须是Context实现，用于处理指向特定Web应用程序的请求
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public class StandardHost extends ContainerBase implements Host {

    private static final Log log = LogFactory.getLog(StandardHost.class);

    // ----------------------------------------------------------- 构造函数

    /**
     * 创建带有默认基础Valve的StandardHost组件
     */
    public StandardHost() {
        super();
        pipeline.setBasic(new StandardHostValve()); // 设置基础Valve为StandardHostValve
    }

    // ----------------------------------------------------- 实例变量

    /** 此Host的别名集合 */
    private String[] aliases = new String[0];
    private final Object aliasesLock = new Object(); // 别名操作锁

    /** 此Host的应用程序根目录 */
    private String appBase = "webapps";
    private volatile File appBaseFile = null; // 应用程序根目录文件对象

    /** 此Host的传统(Java EE)应用程序根目录 */
    private String legacyAppBase = "webapps-javaee";
    private volatile File legacyAppBaseFile = null; // 传统应用程序根目录文件对象

    /** 此Host的XML根目录 */
    private String xmlBase = null;
    private volatile File hostConfigBase = null; // 主机配置基础文件

    /** 此Host的自动部署标志 */
    private boolean autoDeploy = true;

    /** 已部署Web应用程序的默认上下文配置类的Java类名 */
    private String configClass = "org.apache.catalina.startup.ContextConfig";

    /** 已部署Web应用程序的默认Context实现类的Java类名 */
    private String contextClass = "org.apache.catalina.core.StandardContext";

    /** 此Host的启动时部署标志 */
    private boolean deployOnStartup = true;

    /** 部署Context XML配置文件的属性 */
    private boolean deployXML = !Globals.IS_SECURITY_ENABLED;

    /** 默认情况下部署Web应用程序时是否将XML文件复制到$CATALINA_BASE/conf/&lt;engine&gt;/&lt;host&gt; */
    private boolean copyXML = false;

    /** 已部署Web应用程序的默认错误报告器实现类的Java类名 */
    private String errorReportValveClass = "org.apache.catalina.valves.ErrorReportValve";

    /** 解压WAR文件的属性 */
    private boolean unpackWARs = true;

    /** 应用程序的工作目录基础 */
    private String workDir = null;

    /** 启动时是否为appBase和xmlBase创建目录 */
    private boolean createDirs = true;

    /** 跟踪子Web应用程序的类加载器，以便检测内存泄漏 */
    private final Map<ClassLoader, String> childClassLoaders = new WeakHashMap<>();

    /** 自动部署过程中忽略的文件或目录的模式 */
    private Pattern deployIgnore = null;

    /** 是否自动取消部署旧版本应用程序的标志 */
    private boolean undeployOldVersions = false;

    /** 如果Servlet启动失败，是否使Context失败的标志 */
    private boolean failCtxIfServletStartFails = false;

    // ------------------------------------------------------------- 属性方法

    @Override
    public boolean getUndeployOldVersions() {
        return undeployOldVersions;
    }

    @Override
    public void setUndeployOldVersions(boolean undeployOldVersions) {
        this.undeployOldVersions = undeployOldVersions;
    }

    @Override
    public ExecutorService getStartStopExecutor() {
        return startStopExecutor;
    }

    @Override
    public String getAppBase() {
        return this.appBase;
    }

    @Override
    public File getAppBaseFile() {
        // 如果已缓存应用程序根目录文件，直接返回
        if (appBaseFile != null) {
            return appBaseFile;
        }
        // 创建应用程序根目录文件对象
        File file = new File(getAppBase());
        // 如果不是绝对路径，转换为基于Catalina Base的绝对路径
        if (!file.isAbsolute()) {
            file = new File(getCatalinaBase(), file.getPath());
        }
        // 尽可能返回规范形式的文件
        try {
            file = file.getCanonicalFile();
        } catch (IOException ioe) {
            // 忽略异常
        }
        this.appBaseFile = file;
        return file;
    }

    @Override
    public void setAppBase(String appBase) {
        // 处理空应用程序根目录的情况
        if (appBase.trim().isEmpty()) {
            log.warn(sm.getString("standardHost.problematicAppBase", getName()));
        }
        String oldAppBase = this.appBase;
        this.appBase = appBase;
        // 通知属性变更监听器
        support.firePropertyChange("appBase", oldAppBase, this.appBase);
        this.appBaseFile = null; // 重置缓存
    }

    @Override
    public String getLegacyAppBase() {
        return this.legacyAppBase;
    }

    @Override
    public File getLegacyAppBaseFile() {
        if (legacyAppBaseFile != null) {
            return legacyAppBaseFile;
        }
        File file = new File(getLegacyAppBase());
        if (!file.isAbsolute()) {
            file = new File(getCatalinaBase(), file.getPath());
        }
        try {
            file = file.getCanonicalFile();
        } catch (IOException ioe) {
            // 忽略异常
        }
        this.legacyAppBaseFile = file;
        return file;
    }

    @Override
    public void setLegacyAppBase(String legacyAppBase) {
        if (legacyAppBase.trim().isEmpty()) {
            log.warn(sm.getString("standardHost.problematicLegacyAppBase", getName()));
        }
        String oldLegacyAppBase = this.legacyAppBase;
        this.legacyAppBase = legacyAppBase;
        support.firePropertyChange("legacyAppBase", oldLegacyAppBase, this.legacyAppBase);
        this.legacyAppBaseFile = null; // 重置缓存
    }

    @Override
    public String getXmlBase() {
        return this.xmlBase;
    }

    @Override
    public void setXmlBase(String xmlBase) {
        String oldXmlBase = this.xmlBase;
        this.xmlBase = xmlBase;
        support.firePropertyChange("xmlBase", oldXmlBase, this.xmlBase);
    }

    @Override
    public File getConfigBaseFile() {
        if (hostConfigBase != null) {
            return hostConfigBase;
        }
        String path;
        // 优先使用显式设置的xmlBase
        if (getXmlBase() != null) {
            path = getXmlBase();
        } else {
            // 否则使用默认配置路径
            StringBuilder xmlDir = new StringBuilder("conf");
            Container parent = getParent();
            if (parent instanceof Engine) {
                xmlDir.append('/');
                xmlDir.append(parent.getName());
            }
            xmlDir.append('/');
            xmlDir.append(getName());
            path = xmlDir.toString();
        }
        File file = new File(path);
        if (!file.isAbsolute()) {
            file = new File(getCatalinaBase(), path);
        }
        try {
            file = file.getCanonicalFile();
        } catch (IOException e) {
            // 忽略异常
        }
        this.hostConfigBase = file;
        return file;
    }

    /**
     * 获取是否在启动时创建目录的标志
     * 此实现的默认值为true
     */
    @Override
    public boolean getCreateDirs() {
        return createDirs;
    }

    @Override
    public void setCreateDirs(boolean createDirs) {
        this.createDirs = createDirs;
    }

    /**
     * 获取自动部署标志
     * 此实现的默认值为true
     */
    @Override
    public boolean getAutoDeploy() {
        return this.autoDeploy;
    }

    @Override
    public void setAutoDeploy(boolean autoDeploy) {
        boolean oldAutoDeploy = this.autoDeploy;
        this.autoDeploy = autoDeploy;
        support.firePropertyChange("autoDeploy", oldAutoDeploy, this.autoDeploy);
    }

    @Override
    public String getConfigClass() {
        return this.configClass;
    }

    @Override
    public void setConfigClass(String configClass) {
        String oldConfigClass = this.configClass;
        this.configClass = configClass;
        support.firePropertyChange("configClass", oldConfigClass, this.configClass);
    }

    /**
     * 获取新Web应用程序的Context实现类的Java类名
     * @return Context实现类名
     */
    public String getContextClass() {
        return this.contextClass;
    }

    /**
     * 设置新Web应用程序的Context实现类的Java类名
     * @param contextClass 新的Context实现类名
     */
    public void setContextClass(String contextClass) {
        String oldContextClass = this.contextClass;
        this.contextClass = contextClass;
        support.firePropertyChange("contextClass", oldContextClass, this.contextClass);
    }

    /**
     * 获取启动时部署标志
     * 此实现的默认值为true
     */
    @Override
    public boolean getDeployOnStartup() {
        return this.deployOnStartup;
    }

    @Override
    public void setDeployOnStartup(boolean deployOnStartup) {
        boolean oldDeployOnStartup = this.deployOnStartup;
        this.deployOnStartup = deployOnStartup;
        support.firePropertyChange("deployOnStartup", oldDeployOnStartup, this.deployOnStartup);
    }

    /**
     * 获取是否应部署XML上下文描述符的标志
     * @return 若应部署XML上下文描述符则为true
     */
    public boolean isDeployXML() {
        return deployXML;
    }

    /**
     * 设置是否应部署XML上下文描述符的标志
     * @param deployXML 新的部署XML标志
     */
    public void setDeployXML(boolean deployXML) {
        this.deployXML = deployXML;
    }

    /**
     * 获取此组件的复制XML配置文件标志
     * @return 复制XML标志
     */
    public boolean isCopyXML() {
        return this.copyXML;
    }

    /**
     * 设置此组件的复制XML配置文件标志
     * @param copyXML 新的复制XML标志
     */
    public void setCopyXML(boolean copyXML) {
        this.copyXML = copyXML;
    }

    /**
     * 获取新Web应用程序的错误报告Valve类的Java类名
     * @return 错误报告Valve类名
     */
    public String getErrorReportValveClass() {
        return this.errorReportValveClass;
    }

    /**
     * 设置新Web应用程序的错误报告Valve类的Java类名
     * @param errorReportValveClass 新的错误报告Valve类名
     */
    public void setErrorReportValveClass(String errorReportValveClass) {
        String oldErrorReportValveClassClass = this.errorReportValveClass;
        this.errorReportValveClass = errorReportValveClass;
        support.firePropertyChange("errorReportValveClass", oldErrorReportValveClassClass, this.errorReportValveClass);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        if (name == null) {
            throw new IllegalArgumentException(sm.getString("standardHost.nullName"));
        }
        // 内部所有名称均为小写
        name = name.toLowerCase(Locale.ENGLISH);
        String oldName = this.name;
        this.name = name;
        support.firePropertyChange("name", oldName, this.name);
    }

    /**
     * 获取部署时是否应解压WAR文件的标志
     * @return 若应解压WAR文件则为true
     */
    public boolean isUnpackWARs() {
        return unpackWARs;
    }

    /**
     * 设置部署时是否应解压WAR文件的标志
     * @param unpackWARs 新的解压WAR文件标志
     */
    public void setUnpackWARs(boolean unpackWARs) {
        this.unpackWARs = unpackWARs;
    }

    /**
     * 获取主机工作目录基础
     * @return 工作目录基础路径
     */
    public String getWorkDir() {
        return workDir;
    }

    /**
     * 设置主机工作目录基础
     * @param workDir 此主机新的基础工作文件夹
     */
    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    @Override
    public String getDeployIgnore() {
        if (deployIgnore == null) {
            return null;
        }
        return this.deployIgnore.toString();
    }

    @Override
    public Pattern getDeployIgnorePattern() {
        return this.deployIgnore;
    }

    @Override
    public void setDeployIgnore(String deployIgnore) {
        String oldDeployIgnore;
        if (this.deployIgnore == null) {
            oldDeployIgnore = null;
        } else {
            oldDeployIgnore = this.deployIgnore.toString();
        }
        if (deployIgnore == null) {
            this.deployIgnore = null;
        } else {
            this.deployIgnore = Pattern.compile(deployIgnore);
        }
        support.firePropertyChange("deployIgnore", oldDeployIgnore, deployIgnore);
    }

    /**
     * 获取如果Servlet启动失败是否使Context失败的标志
     * @return 若Servlet启动失败则使Context失败为true
     */
    public boolean isFailCtxIfServletStartFails() {
        return failCtxIfServletStartFails;
    }

    /**
     * 设置Web应用程序启动时Servlet启动错误的行为
     * @param failCtxIfServletStartFails 若为false，忽略Web应用程序启动时声明的Servlet错误
     */
    public void setFailCtxIfServletStartFails(boolean failCtxIfServletStartFails) {
        boolean oldFailCtxIfServletStartFails = this.failCtxIfServletStartFails;
        this.failCtxIfServletStartFails = failCtxIfServletStartFails;
        support.firePropertyChange("failCtxIfServletStartFails", oldFailCtxIfServletStartFails, failCtxIfServletStartFails);
    }

    // --------------------------------------------------------- 公共方法

    @Override
    public void addAlias(String alias) {
        // 转换为小写以保持一致性
        alias = alias.toLowerCase(Locale.ENGLISH);
        synchronized (aliasesLock) {
            // 跳过重复的别名
            for (String s : aliases) {
                if (s.equals(alias)) {
                    return;
                }
            }
            // 添加此别名到列表
            String[] newAliases = Arrays.copyOf(aliases, aliases.length + 1);
            newAliases[aliases.length] = alias;
            aliases = newAliases;
        }
        // 通知感兴趣的监听器
        fireContainerEvent(ADD_ALIAS_EVENT, alias);
    }

    /**
     * 添加子容器
     * 子容器必须是Context实现
     */
    @Override
    public void addChild(Container child) {
        if (!(child instanceof Context)) {
            throw new IllegalArgumentException(sm.getString("standardHost.notContext"));
        }
        // 添加生命周期监听器以跟踪内存泄漏
        child.addLifecycleListener(new MemoryLeakTrackingListener());
        // 处理Context路径为null的情况
        Context context = (Context) child;
        if (context.getPath() == null) {
            ContextName cn = new ContextName(context.getDocBase(), true);
            context.setPath(cn.getPath());
        }
        super.addChild(child);
    }

    /**
     * 内存泄漏跟踪监听器
     * 用于确保无论Context实现如何，每次Context启动时都记录使用的类加载器
     */
    private class MemoryLeakTrackingListener implements LifecycleListener {
        @Override
        public void lifecycleEvent(LifecycleEvent event) {
            if (event.getType().equals(AFTER_START_EVENT)) {
                if (event.getSource() instanceof Context) {
                    Context context = ((Context) event.getSource());
                    // 记录Context的类加载器以检测内存泄漏
                    childClassLoaders.put(context.getLoader().getClassLoader(),
                        context.getServletContext().getContextPath());
                }
            }
        }
    }

    /**
     * 尝试识别有类加载器内存泄漏的Context
     * 通常在Context重新加载时触发
     * 注意：此方法尝试强制完全垃圾回收，在生产系统上应谨慎使用
     * @return 可能泄漏的Context数组
     */
    public String[] findReloadedContextMemoryLeaks() {
        System.gc(); // 强制垃圾回收
        List<String> result = new ArrayList<>();
        // 检查所有子Context的类加载器状态
        for (Map.Entry<ClassLoader, String> entry : childClassLoaders.entrySet()) {
            ClassLoader cl = entry.getKey();
            if (cl instanceof WebappClassLoaderBase) {
                if (!((WebappClassLoaderBase) cl).getState().isAvailable()) {
                    result.add(entry.getValue());
                }
            }
        }
        return result.toArray(new String[0]);
    }

    @Override
    public String[] findAliases() {
        synchronized (aliasesLock) {
            return this.aliases;
        }
    }

    @Override
    public void removeAlias(String alias) {
        alias = alias.toLowerCase(Locale.ENGLISH);
        synchronized (aliasesLock) {
            // 确保此别名当前存在
            int n = -1;
            for (int i = 0; i < aliases.length; i++) {
                if (aliases[i].equals(alias)) {
                    n = i;
                    break;
                }
            }
            if (n < 0) {
                return;
            }
            // 移除指定的别名
            int j = 0;
            String[] results = new String[aliases.length - 1];
            for (int i = 0; i < aliases.length; i++) {
                if (i != n) {
                    results[j++] = aliases[i];
                }
            }
            aliases = results;
        }
        // 通知感兴趣的监听器
        fireContainerEvent(REMOVE_ALIAS_EVENT, alias);
    }

    @Override
    protected void startInternal() throws LifecycleException {
        // 设置错误报告Valve
        String errorValve = getErrorReportValveClass();
        if ((errorValve != null) && (!errorValve.isEmpty())) {
            try {
                boolean found = false;
                Valve[] valves = getPipeline().getValves();
                // 检查是否已存在指定的错误报告Valve
                for (Valve valve : valves) {
                    if (errorValve.equals(valve.getClass().getName())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // 创建并添加错误报告Valve
                    Valve valve = ErrorReportValve.class.getName().equals(errorValve) ? new ErrorReportValve() :
                        (Valve) Class.forName(errorValve).getConstructor().newInstance();
                    getPipeline().addValve(valve);
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("standardHost.invalidErrorReportValveClass", errorValve), t);
            }
        }
        super.startInternal(); // 调用父类启动方法
    }

    // -------------------- JMX相关方法 --------------------

    /**
     * 获取与此Host关联的Valves的MBean名称
     * @return Valve的MBean名称数组
     * @exception Exception 如果无法创建或注册MBean
     */
    public String[] getValveNames() throws Exception {
        Valve[] valves = this.getPipeline().getValves();
        String[] mbeanNames = new String[valves.length];
        for (int i = 0; i < valves.length; i++) {
            if (valves[i] instanceof JmxEnabled) {
                ObjectName oname = ((JmxEnabled) valves[i]).getObjectName();
                if (oname != null) {
                    mbeanNames[i] = oname.toString();
                }
            }
        }
        return mbeanNames;
    }

    public String[] getAliases() {
        synchronized (aliasesLock) {
            return aliases;
        }
    }

    @Override
    protected String getObjectNameKeyProperties() {
        return "type=Host" + getMBeanKeyProperties();
    }
}