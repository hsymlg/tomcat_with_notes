/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.core;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.management.ListenerNotFoundException;
import javax.management.MBeanNotificationInfo;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.annotation.MultipartConfig;

import org.apache.catalina.Container;
import org.apache.catalina.ContainerServlet;
import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Wrapper;
import org.apache.catalina.security.SecurityUtil;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.PeriodicEventListener;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.log.SystemLogHandler;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.modeler.Util;

/**
 * StandardWrapper是Wrapper接口的标准实现，代表单个Servlet定义。
 * 不允许有子容器，父容器必须是Context。负责Servlet的生命周期管理、配置管理和请求处理。
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public class StandardWrapper extends ContainerBase implements ServletConfig, Wrapper, NotificationEmitter {

    // 日志记录器，必须是非静态的
    private final Log log = LogFactory.getLog(StandardWrapper.class);

    // 默认支持的Servlet方法（GET、HEAD、POST）
    protected static final String[] DEFAULT_SERVLET_METHODS = new String[] { "GET", "HEAD", "POST" };

    // ----------------------------------------------------------- 构造函数

    /**
     * 创建新的StandardWrapper实例，设置默认的Valve
     */
    public StandardWrapper() {
        super();
        swValve = new StandardWrapperValve(); // 创建标准Wrapper阀门
        pipeline.setBasic(swValve); // 将阀门设置为管道的基本阀门
        broadcaster = new NotificationBroadcasterSupport(); // 初始化通知广播器
    }

    // ----------------------------------------------------- 实例变量

    /**
     * Servlet可用时间戳（毫秒）
     * 0表示可用，Long.MAX_VALUE表示永久不可用
     */
    protected long available = 0L;

    /** 用于发送JMX通知的广播器 */
    protected final NotificationBroadcasterSupport broadcaster;

    /** 当前活跃的分配计数（原子整数保证线程安全） */
    protected final AtomicInteger countAllocated = new AtomicInteger(0);

    /** 与此Wrapper关联的门面类，提供简化接口 */
    protected final StandardWrapperFacade facade = new StandardWrapperFacade(this);

    /** Servlet实例（可能未初始化） */
    protected volatile Servlet instance = null;

    /** 实例是否已初始化的标志 */
    protected volatile boolean instanceInitialized = false;

    /** 启动时加载顺序（负值表示首次调用时加载） */
    protected int loadOnStartup = -1;

    /** Wrapper关联的映射路径列表 */
    protected final ArrayList<String> mappings = new ArrayList<>();

    /** Servlet初始化参数（键为参数名） */
    protected HashMap<String, String> parameters = new HashMap<>();

    /** 安全角色引用（Servlet内部角色到Web应用角色的映射） */
    protected HashMap<String, String> references = new HashMap<>();

    /** Servlet的运行身份（run-as角色） */
    protected String runAs = null;

    /** 通知序列号 */
    protected long sequenceNumber = 0;

    /** Servlet的完整类名 */
    protected String servletClass = null;

    /** 是否正在卸载Servlet实例 */
    protected volatile boolean unloading = false;

    /** Servlet卸载等待时间（毫秒） */
    protected long unloadDelay = 2000;

    /** 是否为JSP Servlet */
    protected boolean isJspServlet;

    /** JSP监控MBean的ObjectName */
    protected ObjectName jspMonitorON;

    /** 是否吞咽System.out输出 */
    protected boolean swallowOutput = false;

    // 用于JMX属性支持
    StandardWrapperValve swValve;
    protected long loadTime = 0;
    protected int classLoadTime = 0;

    /** 多部分配置 */
    protected MultipartConfigElement multipartConfigElement = null;

    /** 是否支持异步处理 */
    protected boolean asyncSupported = false;

    /** 是否启用Servlet */
    protected boolean enabled = true;

    /** 是否可被ServletContainerInitializer覆盖 */
    private boolean overridable = false;

    /** 安全管理器启用时调用Servlet.init使用的类数组 */
    protected static Class<?>[] classType = new Class[] { ServletConfig.class };

    /** 初始化参数的读写锁 */
    private final ReentrantReadWriteLock parametersLock = new ReentrantReadWriteLock();

    /** 映射路径的读写锁 */
    private final ReentrantReadWriteLock mappingsLock = new ReentrantReadWriteLock();

    /** 安全引用的读写锁 */
    private final ReentrantReadWriteLock referencesLock = new ReentrantReadWriteLock();

    // ------------------------------------------------------------- 属性方法

    @Override
    public boolean isOverridable() {
        return overridable;
    }

    @Override
    public void setOverridable(boolean overridable) {
        this.overridable = overridable;
    }

    @Override
    public long getAvailable() {
        return this.available;
    }

    @Override
    public void setAvailable(long available) {
        long oldAvailable = this.available;
        // 如果设置的可用时间在当前时间之后，则使用该时间，否则设为0（可用）
        if (available > System.currentTimeMillis()) {
            this.available = available;
        } else {
            this.available = 0L;
        }
        // 触发属性变更事件
        support.firePropertyChange("available", Long.valueOf(oldAvailable), Long.valueOf(this.available));
    }

    /**
     * 获取当前活跃的Servlet分配数
     *
     * @return 活跃分配数
     */
    public int getCountAllocated() {
        return this.countAllocated.get();
    }

    @Override
    public int getLoadOnStartup() {
        // JSP Servlet必须始终预加载
        if (isJspServlet && loadOnStartup == -1) {
            return Integer.MAX_VALUE;
        } else {
            return this.loadOnStartup;
        }
    }

    @Override
    public void setLoadOnStartup(int value) {
        int oldLoadOnStartup = this.loadOnStartup;
        this.loadOnStartup = value;
        // 触发属性变更事件
        support.firePropertyChange("loadOnStartup", Integer.valueOf(oldLoadOnStartup),
            Integer.valueOf(this.loadOnStartup));
    }

    /**
     * 从字符串设置加载顺序（处理非数字情况）
     *
     * @param value 加载顺序字符串
     */
    public void setLoadOnStartupString(String value) {
        try {
            setLoadOnStartup(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            setLoadOnStartup(0); // 非数字设为0
        }
    }

    /**
     * 获取加载顺序的字符串表示
     *
     * @return 加载顺序字符串
     */
    public String getLoadOnStartupString() {
        return Integer.toString(getLoadOnStartup());
    }

    /**
     * 设置父容器，必须为Context类型
     *
     * @param container 父容器
     */
    @Override
    public void setParent(Container container) {
        if ((container != null) && !(container instanceof Context)) {
            throw new IllegalArgumentException(sm.getString("standardWrapper.notContext"));
        }
        // 从父Context获取配置
        if (container instanceof StandardContext) {
            swallowOutput = ((StandardContext) container).getSwallowOutput();
            unloadDelay = ((StandardContext) container).getUnloadDelay();
        }
        super.setParent(container);
    }

    @Override
    public String getRunAs() {
        return this.runAs;
    }

    @Override
    public void setRunAs(String runAs) {
        String oldRunAs = this.runAs;
        this.runAs = runAs;
        // 触发属性变更事件
        support.firePropertyChange("runAs", oldRunAs, this.runAs);
    }

    @Override
    public String getServletClass() {
        return this.servletClass;
    }

    @Override
    public void setServletClass(String servletClass) {
        String oldServletClass = this.servletClass;
        this.servletClass = servletClass;
        // 触发属性变更事件
        support.firePropertyChange("servletClass", oldServletClass, this.servletClass);
        // 检测是否为JSP Servlet
        if (Constants.JSP_SERVLET_CLASS.equals(servletClass)) {
            isJspServlet = true;
        }
    }

    /**
     * 设置Servlet名称（别名，对应Container的setName方法）
     *
     * @param name Servlet名称
     */
    public void setServletName(String name) {
        setName(name);
    }

    @Override
    public boolean isUnavailable() {
        // 检查Servlet是否启用或可用时间
        if (!isEnabled()) {
            return true;
        } else if (available == 0L) {
            return false;
        } else if (available <= System.currentTimeMillis()) {
            available = 0L; // 可用时间已过，设为可用
            return false;
        } else {
            return true; // 可用时间未到，不可用
        }
    }

    @Override
    public String[] getServletMethods() throws ServletException {
        // 加载Servlet实例
        instance = loadServlet();
        Class<? extends Servlet> servletClazz = instance.getClass();
        // 非HttpServlet使用默认方法
        if (!jakarta.servlet.http.HttpServlet.class.isAssignableFrom(servletClazz)) {
            return DEFAULT_SERVLET_METHODS;
        }

        Set<String> allow = new HashSet<>();
        allow.add("OPTIONS"); // 始终支持OPTIONS方法

        if (isJspServlet) {
            // JSP Servlet支持GET、HEAD、POST
            allow.add("GET");
            allow.add("HEAD");
            allow.add("POST");
        } else {
            allow.add("TRACE"); // 支持TRACE方法

            // 检查Servlet类中定义的doXxx方法
            Method[] methods = getAllDeclaredMethods(servletClazz);
            if (methods != null) {
                for (Method m : methods) {
                    switch (m.getName()) {
                        case "doGet":
                            allow.add("GET");
                            allow.add("HEAD");
                            break;
                        case "doPost":
                            allow.add("POST");
                            break;
                        case "doPut":
                            allow.add("PUT");
                            break;
                        case "doDelete":
                            allow.add("DELETE");
                            break;
                    }
                }
            }
        }
        return allow.toArray(new String[0]);
    }

    @Override
    public Servlet getServlet() {
        return instance;
    }

    @Override
    public void setServlet(Servlet servlet) {
        instance = servlet;
    }

    // --------------------------------------------------------- 公共方法

    @Override
    public synchronized void backgroundProcess() {
        super.backgroundProcess();
        // 仅在组件可用时执行
        if (!getState().isAvailable()) {
            return;
        }
        // 处理周期性事件的Servlet
        if (getServlet() instanceof PeriodicEventListener) {
            ((PeriodicEventListener) getServlet()).periodicEvent();
        }
    }

    /**
     * 从ServletException中提取根本原因
     *
     * @param e ServletException
     * @return 根本原因Throwable
     */
    public static Throwable getRootCause(ServletException e) {
        Throwable rootCause = e;
        Throwable rootCauseCheck;
        int loops = 0;
        // 最多查找20层原因
        do {
            loops++;
            rootCauseCheck = rootCause.getCause();
            if (rootCauseCheck != null) {
                rootCause = rootCauseCheck;
            }
        } while (rootCauseCheck != null && (loops < 20));
        return rootCause;
    }

    /**
     * 拒绝添加子容器（Wrapper是容器层级的最底层）
     *
     * @param child 要添加的子容器
     */
    @Override
    public void addChild(Container child) {
        throw new IllegalStateException(sm.getString("standardWrapper.notChild"));
    }

    @Override
    public void addInitParameter(String name, String value) {
        // 写入锁保证线程安全
        parametersLock.writeLock().lock();
        try {
            parameters.put(name, value);
        } finally {
            parametersLock.writeLock().unlock();
        }
        // 触发容器事件
        fireContainerEvent("addInitParameter", name);
    }

    @Override
    public void addMapping(String mapping) {
        // 写入锁保证线程安全
        mappingsLock.writeLock().lock();
        try {
            mappings.add(mapping);
        } finally {
            mappingsLock.writeLock().unlock();
        }
        // 父容器已启动时触发添加映射事件
        if (parent.getState().equals(LifecycleState.STARTED)) {
            fireContainerEvent(ADD_MAPPING_EVENT, mapping);
        }
    }

    @Override
    public void addSecurityReference(String name, String link) {
        // 写入锁保证线程安全
        referencesLock.writeLock().lock();
        try {
            references.put(name, link);
        } finally {
            referencesLock.writeLock().unlock();
        }
        // 触发容器事件
        fireContainerEvent("addSecurityReference", name);
    }

    @Override
    public Servlet allocate() throws ServletException {
        // 卸载中时抛出异常
        if (unloading) {
            throw new ServletException(sm.getString("standardWrapper.unloading", getName()));
        }

        boolean newInstance = false;
        // 需要加载或初始化实例
        if (instance == null || !instanceInitialized) {
            synchronized (this) {
                if (instance == null) {
                    try {
                        if (log.isTraceEnabled()) {
                            log.trace("Allocating instance");
                        }
                        // 加载Servlet实例
                        instance = loadServlet();
                        newInstance = true;
                        // 增加分配计数（防止与unload竞争）
                        countAllocated.incrementAndGet();
                    } catch (ServletException e) {
                        throw e;
                    } catch (Throwable e) {
                        ExceptionUtils.handleThrowable(e);
                        throw new ServletException(sm.getString("standardWrapper.allocate"), e);
                    }
                }
                // 初始化实例（如果尚未初始化）
                if (!instanceInitialized) {
                    initServlet(instance);
                }
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("  Returning instance");
        }
        // 新实例已在创建时增加计数
        if (!newInstance) {
            countAllocated.incrementAndGet();
        }
        return instance;
    }

    @Override
    public void deallocate(Servlet servlet) throws ServletException {
        countAllocated.decrementAndGet(); // 减少分配计数
    }

    @Override
    public String findInitParameter(String name) {
        // 读取锁保证线程安全
        parametersLock.readLock().lock();
        try {
            return parameters.get(name);
        } finally {
            parametersLock.readLock().unlock();
        }
    }

    @Override
    public String[] findInitParameters() {
        // 读取锁保证线程安全
        parametersLock.readLock().lock();
        try {
            return parameters.keySet().toArray(new String[0]);
        } finally {
            parametersLock.readLock().unlock();
        }
    }

    @Override
    public String[] findMappings() {
        // 读取锁保证线程安全
        mappingsLock.readLock().lock();
        try {
            return mappings.toArray(new String[0]);
        } finally {
            mappingsLock.readLock().unlock();
        }
    }

    @Override
    public String findSecurityReference(String name) {
        String reference;
        // 读取锁保证线程安全
        referencesLock.readLock().lock();
        try {
            reference = references.get(name);
        } finally {
            referencesLock.readLock().unlock();
        }

        // Wrapper未定义时检查Context
        if (getParent() instanceof Context) {
            Context context = (Context) getParent();
            if (reference != null) {
                reference = context.findRoleMapping(reference);
            } else {
                reference = context.findRoleMapping(name);
            }
        }
        return reference;
    }

    @Override
    public String[] findSecurityReferences() {
        // 读取锁保证线程安全
        referencesLock.readLock().lock();
        try {
            return references.keySet().toArray(new String[0]);
        } finally {
            referencesLock.readLock().unlock();
        }
    }

    /**
     * 加载并初始化Servlet实例（如果尚未加载）
     * 用于启动时加载的Servlet
     *
     * @throws ServletException Servlet加载或初始化错误
     */
    @Override
    public synchronized void load() throws ServletException {
        instance = loadServlet();
        // 初始化实例（如果尚未初始化）
        if (!instanceInitialized) {
            initServlet(instance);
        }
        // 为JSP Servlet注册监控MBean
        if (isJspServlet) {
            StringBuilder oname = new StringBuilder(getDomain());
            oname.append(":type=JspMonitor");
            oname.append(getWebModuleKeyProperties());
            oname.append(",name=").append(getName());
            oname.append(getJ2EEKeyProperties());
            try {
                jspMonitorON = new ObjectName(oname.toString());
                Registry.getRegistry(null).registerComponent(instance, jspMonitorON, null);
            } catch (Exception ex) {
                log.warn(sm.getString("standardWrapper.jspMonitorError", instance));
            }
        }
    }

    /**
     * 加载Servlet实例
     *
     * @return 加载的Servlet实例
     * @throws ServletException 加载错误
     */
    public synchronized Servlet loadServlet() throws ServletException {
        // 已有实例则直接返回
        if (instance != null) {
            return instance;
        }

        PrintStream out = System.out;
        if (swallowOutput) {
            SystemLogHandler.startCapture(); // 开始捕获System.out输出
        }

        Servlet servlet;
        try {
            long t1 = System.currentTimeMillis();
            // 检查是否指定了Servlet类
            if (servletClass == null) {
                unavailable(null);
                throw new ServletException(sm.getString("standardWrapper.notClass", getName()));
            }

            // 从父Context获取实例管理器
            InstanceManager instanceManager = ((StandardContext) getParent()).getInstanceManager();
            try {
                // 实例化Servlet
                servlet = (Servlet) instanceManager.newInstance(servletClass);
            } catch (ClassCastException e) {
                unavailable(null);
                throw new ServletException(sm.getString("standardWrapper.notServlet", servletClass), e);
            } catch (Throwable e) {
                // 处理反射异常
                Throwable throwable = ExceptionUtils.unwrapInvocationTargetException(e);
                ExceptionUtils.handleThrowable(throwable);
                unavailable(null);
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("standardWrapper.instantiate", servletClass), throwable);
                }
                throw new ServletException(sm.getString("standardWrapper.instantiate", servletClass), throwable);
            }

            // 处理多部分配置注解
            if (multipartConfigElement == null) {
                MultipartConfig annotation = servlet.getClass().getAnnotation(MultipartConfig.class);
                if (annotation != null) {
                    multipartConfigElement = new MultipartConfigElement(annotation);
                }
            }

            // 处理ContainerServlet特殊情况
            if (servlet instanceof ContainerServlet) {
                ((ContainerServlet) servlet).setWrapper(this);
            }

            classLoadTime = (int) (System.currentTimeMillis() - t1); // 记录类加载时间

            initServlet(servlet); // 初始化Servlet

            fireContainerEvent("load", this); // 触发加载事件

            loadTime = System.currentTimeMillis() - t1; // 记录总加载时间
        } finally {
            if (swallowOutput) {
                // 处理捕获的System.out输出
                String log = SystemLogHandler.stopCapture();
                if (log != null && !log.isEmpty()) {
                    if (getServletContext() != null) {
                        getServletContext().log(log);
                    } else {
                        out.println(log);
                    }
                }
            }
        }
        return servlet;
    }

    /**
     * 初始化Servlet实例
     *
     * @param servlet 要初始化的Servlet实例
     * @throws ServletException 初始化错误
     */
    private synchronized void initServlet(Servlet servlet) throws ServletException {
        if (instanceInitialized) {
            return;
        }

        try {
            // 安全管理器环境下的特权操作
            if (Globals.IS_SECURITY_ENABLED) {
                boolean success = false;
                try {
                    Object[] args = new Object[] { facade };
                    SecurityUtil.doAsPrivilege("init", servlet, classType, args);
                    success = true;
                } finally {
                    if (!success) {
                        // 初始化失败时清除引用
                        SecurityUtil.remove(servlet);
                    }
                }
            } else {
                servlet.init(facade); // 普通环境下直接初始化
            }
            instanceInitialized = true; // 标记为已初始化
        } catch (UnavailableException f) {
            unavailable(f); // 处理不可用异常
            throw f;
        } catch (ServletException f) {
            throw f; // 直接抛出Servlet初始化异常
        } catch (Throwable f) {
            // 处理其他异常
            ExceptionUtils.handleThrowable(f);
            getServletContext().log(sm.getString("standardWrapper.initException", getName()), f);
            throw new ServletException(sm.getString("standardWrapper.initException", getName()), f);
        }
    }

    @Override
    public void removeInitParameter(String name) {
        // 写入锁保证线程安全
        parametersLock.writeLock().lock();
        try {
            parameters.remove(name);
        } finally {
            parametersLock.writeLock().unlock();
        }
        // 触发容器事件
        fireContainerEvent("removeInitParameter", name);
    }

    @Override
    public void removeMapping(String mapping) {
        // 写入锁保证线程安全
        mappingsLock.writeLock().lock();
        try {
            mappings.remove(mapping);
        } finally {
            mappingsLock.writeLock().unlock();
        }
        // 父容器已启动时触发移除映射事件
        if (parent.getState().equals(LifecycleState.STARTED)) {
            fireContainerEvent(REMOVE_MAPPING_EVENT, mapping);
        }
    }

    @Override
    public void removeSecurityReference(String name) {
        // 写入锁保证线程安全
        referencesLock.writeLock().lock();
        try {
            references.remove(name);
        } finally {
            referencesLock.writeLock().unlock();
        }
        // 触发容器事件
        fireContainerEvent("removeSecurityReference", name);
    }

    @Override
    public void unavailable(UnavailableException unavailable) {
        // 记录Servlet不可用日志
        getServletContext().log(sm.getString("standardWrapper.unavailable", getName()));
        if (unavailable == null) {
            setAvailable(Long.MAX_VALUE); // 永久不可用
        } else if (unavailable.isPermanent()) {
            setAvailable(Long.MAX_VALUE); // 永久不可用
        } else {
            // 设置临时不可用时间
            int unavailableSeconds = unavailable.getUnavailableSeconds();
            if (unavailableSeconds <= 0) {
                unavailableSeconds = 60; // 默认60秒
            }
            setAvailable(System.currentTimeMillis() + (unavailableSeconds * 1000L));
        }
    }

    @Override
    public synchronized void unload() throws ServletException {
        // 无实例则直接返回
        if (instance == null) {
            return;
        }
        unloading = true; // 标记为正在卸载

        // 等待所有分配的实例释放
        if (countAllocated.get() > 0) {
            int nRetries = 0;
            long delay = unloadDelay / 20;
            while ((nRetries < 21) && (countAllocated.get() > 0)) {
                if ((nRetries % 10) == 0) {
                    log.info(sm.getString("standardWrapper.waiting", countAllocated.toString(), getName()));
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    // 忽略中断
                }
                nRetries++;
            }
        }

        if (instanceInitialized) {
            PrintStream out = System.out;
            if (swallowOutput) {
                SystemLogHandler.startCapture(); // 开始捕获输出
            }

            try {
                // 调用Servlet的destroy方法
                if (Globals.IS_SECURITY_ENABLED) {
                    try {
                        SecurityUtil.doAsPrivilege("destroy", instance);
                    } finally {
                        SecurityUtil.remove(instance); // 移除安全引用
                    }
                } else {
                    instance.destroy();
                }
            } catch (Throwable t) {
                // 处理destroy异常
                Throwable throwable = ExceptionUtils.unwrapInvocationTargetException(t);
                ExceptionUtils.handleThrowable(throwable);
                fireContainerEvent("unload", this);
                unloading = false;
                throw new ServletException(sm.getString("standardWrapper.destroyException", getName()), throwable);
            } finally {
                // 处理注解
                if (!((Context) getParent()).getIgnoreAnnotations()) {
                    try {
                        ((Context) getParent()).getInstanceManager().destroyInstance(instance);
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        log.error(sm.getString("standardWrapper.destroyInstance", getName()), t);
                    }
                }
                // 处理捕获的输出
                if (swallowOutput) {
                    String log = SystemLogHandler.stopCapture();
                    if (log != null && !log.isEmpty()) {
                        if (getServletContext() != null) {
                            getServletContext().log(log);
                        } else {
                            out.println(log);
                        }
                    }
                }
                instance = null;
                instanceInitialized = false;
            }
        }

        // 注销实例
        instance = null;

        // 注销JSP监控MBean
        if (isJspServlet && jspMonitorON != null) {
            Registry.getRegistry(null).unregisterComponent(jspMonitorON);
        }

        unloading = false;
        fireContainerEvent("unload", this); // 触发卸载事件
    }

    // -------------------------------------------------- ServletConfig方法

    @Override
    public String getInitParameter(String name) {
        return findInitParameter(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        // 读取锁保证线程安全
        parametersLock.readLock().lock();
        try {
            return Collections.enumeration(parameters.keySet());
        } finally {
            parametersLock.readLock().unlock();
        }
    }

    @Override
    public ServletContext getServletContext() {
        if (parent == null) {
            return null;
        } else if (!(parent instanceof Context)) {
            return null;
        } else {
            return ((Context) parent).getServletContext();
        }
    }

    @Override
    public String getServletName() {
        return getName();
    }

    // 以下为监控相关方法

    public long getProcessingTime() {
        return swValve.getProcessingTime();
    }

    public long getMaxTime() {
        return swValve.getMaxTime();
    }

    public long getMinTime() {
        return swValve.getMinTime();
    }

    /**
     * 获取Wrapper处理的请求数（已过时，Tomcat 11将返回long）
     *
     * @deprecated 请使用long类型存储返回值
     */
    @Deprecated
    public int getRequestCount() {
        return swValve.getRequestCount();
    }

    /**
     * 获取Wrapper处理的错误请求数（已过时，Tomcat 11将返回long）
     *
     * @deprecated 请使用long类型存储返回值
     */
    @Deprecated
    public int getErrorCount() {
        return swValve.getErrorCount();
    }

    /**
     * 增加错误计数（用于监控）
     */
    @Override
    public void incrementErrorCount() {
        swValve.incrementErrorCount();
    }

    public long getLoadTime() {
        return loadTime;
    }

    public int getClassLoadTime() {
        return classLoadTime;
    }

    @Override
    public MultipartConfigElement getMultipartConfigElement() {
        return multipartConfigElement;
    }

    @Override
    public void setMultipartConfigElement(MultipartConfigElement multipartConfigElement) {
        this.multipartConfigElement = multipartConfigElement;
    }

    @Override
    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    @Override
    public void setAsyncSupported(boolean asyncSupported) {
        this.asyncSupported = asyncSupported;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // -------------------------------------------------------- 保护方法

    /**
     * 获取类及其父类的所有声明方法
     *
     * @param c 类对象
     * @return 方法数组
     */
    protected Method[] getAllDeclaredMethods(Class<?> c) {
        if (c.equals(jakarta.servlet.http.HttpServlet.class)) {
            return null;
        }

        Method[] parentMethods = getAllDeclaredMethods(c.getSuperclass());
        Method[] thisMethods = c.getDeclaredMethods();
        if (thisMethods.length == 0) {
            return parentMethods;
        }

        if ((parentMethods != null) && (parentMethods.length > 0)) {
            // 合并父类和当前类的方法
            Method[] allMethods = new Method[parentMethods.length + thisMethods.length];
            System.arraycopy(parentMethods, 0, allMethods, 0, parentMethods.length);
            System.arraycopy(thisMethods, 0, allMethods, parentMethods.length, thisMethods.length);
            thisMethods = allMethods;
        }
        return thisMethods;
    }

    // ------------------------------------------------------ 生命周期方法

    /**
     * 启动组件
     *
     * @throws LifecycleException 启动错误
     */
    @Override
    protected void startInternal() throws LifecycleException {
        // 发送j2ee.state.starting通知
        if (this.getObjectName() != null) {
            Notification notification = new Notification("j2ee.state.starting", this.getObjectName(), sequenceNumber++);
            broadcaster.sendNotification(notification);
        }

        // 启动组件
        super.startInternal();

        setAvailable(0L); // 设置为可用

        // 发送j2ee.state.running通知
        if (this.getObjectName() != null) {
            Notification notification = new Notification("j2ee.state.running", this.getObjectName(), sequenceNumber++);
            broadcaster.sendNotification(notification);
        }
    }

    /**
     * 停止组件
     *
     * @throws LifecycleException 停止错误
     */
    @Override
    protected void stopInternal() throws LifecycleException {
        setAvailable(Long.MAX_VALUE); // 设置为不可用

        // 发送j2ee.state.stopping通知
        if (this.getObjectName() != null) {
            Notification notification = new Notification("j2ee.state.stopping", this.getObjectName(), sequenceNumber++);
            broadcaster.sendNotification(notification);
        }

        // 卸载Servlet实例
        try {
            unload();
        } catch (ServletException e) {
            getServletContext().log(sm.getString("standardWrapper.unloadException", getName()), e);
        }

        // 停止组件
        super.stopInternal();

        // 发送j2ee.state.stopped通知
        if (this.getObjectName() != null) {
            Notification notification = new Notification("j2ee.state.stopped", this.getObjectName(), sequenceNumber++);
            broadcaster.sendNotification(notification);

            // 发送j2ee.object.deleted通知
            notification = new Notification("j2ee.object.deleted", this.getObjectName(), sequenceNumber++);
            broadcaster.sendNotification(notification);
        }
    }

    @Override
    protected String getObjectNameKeyProperties() {
        StringBuilder keyProperties = new StringBuilder("j2eeType=Servlet");
        keyProperties.append(getWebModuleKeyProperties());
        keyProperties.append(",name=");
        String name = getName();
        if (Util.objectNameValueNeedsQuote(name)) {
            name = ObjectName.quote(name);
        }
        keyProperties.append(name);
        keyProperties.append(getJ2EEKeyProperties());
        return keyProperties.toString();
    }

    private String getWebModuleKeyProperties() {
        StringBuilder keyProperties = new StringBuilder(",WebModule=//");
        String hostName = getParent().getParent().getName();
        keyProperties.append(Objects.requireNonNullElse(hostName, "DEFAULT"));
        String contextName = getParent().getName();
        if (!contextName.startsWith("/")) {
            keyProperties.append('/');
        }
        keyProperties.append(contextName);
        return keyProperties.toString();
    }

    private String getJ2EEKeyProperties() {
        StringBuilder keyProperties = new StringBuilder(",J2EEApplication=");
        StandardContext ctx = null;
        if (parent instanceof StandardContext) {
            ctx = (StandardContext) getParent();
        }
        if (ctx == null) {
            keyProperties.append("none");
        } else {
            keyProperties.append(ctx.getJ2EEApplication());
        }
        keyProperties.append(",J2EEServer=");
        if (ctx == null) {
            keyProperties.append("none");
        } else {
            keyProperties.append(ctx.getJ2EEServer());
        }
        return keyProperties.toString();
    }

    @Override
    public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object object)
        throws ListenerNotFoundException {
        broadcaster.removeNotificationListener(listener, filter, object);
    }

    protected MBeanNotificationInfo[] notificationInfo;

    @Override
    public MBeanNotificationInfo[] getNotificationInfo() {
        if (notificationInfo == null) {
            notificationInfo = new MBeanNotificationInfo[] {
                new MBeanNotificationInfo(new String[] { "j2ee.object.created" }, Notification.class.getName(),
                    "servlet is created"),
                new MBeanNotificationInfo(new String[] { "j2ee.state.starting" }, Notification.class.getName(),
                    "servlet is starting"),
                new MBeanNotificationInfo(new String[] { "j2ee.state.running" }, Notification.class.getName(),
                    "servlet is running"),
                new MBeanNotificationInfo(new String[] { "j2ee.state.stopped" }, Notification.class.getName(),
                    "servlet start to stopped"),
                new MBeanNotificationInfo(new String[] { "j2ee.object.stopped" }, Notification.class.getName(),
                    "servlet is stopped"),
                new MBeanNotificationInfo(new String[] { "j2ee.object.deleted" }, Notification.class.getName(),
                    "servlet is deleted") };
        }
        return notificationInfo;
    }

    @Override
    public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object object)
        throws IllegalArgumentException {
        broadcaster.addNotificationListener(listener, filter, object);
    }

    @Override
    public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
        broadcaster.removeNotificationListener(listener);
    }
}