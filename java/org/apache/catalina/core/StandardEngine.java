/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.catalina.core;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.catalina.AccessLog;
import org.apache.catalina.Container;
import org.apache.catalina.ContainerEvent;
import org.apache.catalina.ContainerListener;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Realm;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.realm.NullRealm;
import org.apache.catalina.util.ServerInfo;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Lifecycle：定义组件生命周期规范
 * Container：定义容器组件核心功能
 * JmxEnabled：定义 JMX 管理能力
 * LifecycleBase：实现基本生命周期管理
 * ContainerBase：实现基本容器功能
 * LifecycleMBeanBase：结合生命周期和 JMX 管理
 * Engine：定义引擎特有的功能
 * StandardEngine：实现完整的引擎功能，整合上述所有能力
 *
 * ┌──────────────┐     ┌───────────────┐     ┌───────────────┐
 * │   Lifecycle  │     │    Container  │     │   JmxEnabled  │
 * │  (接口)       │     │   (接口)      │     │   (接口)      │
 * └──────┬───────┘     └──────┬───────┘     └──────┬───────┘
 *        ▼                ▼                ▼
 * ┌───────────────┐     ┌───────────────┐   ┌───────────────┐
 * │  LifecycleBase│    │  ContainerBase│  │  MBeanRegistration│
 * │  (抽象类)      │     │  (抽象类)     │    │    (接口)       │
 * └──────┬───────┘      └──────┬───────┘     └──────┬───────┘
 *        ▼                ▼                ▼
 * ┌─────────────────────┐     ┌───────────────┐
 * │  LifecycleMBeanBase │     │    Engine     │
 * │  (抽象类)           │     │   (接口)      │
 * └──────────────┬──────┘     └──────┬───────┘
 *                ▼                ▼
 *            ┌──────────────────┐
 *            │  StandardEngine  │
 *            │  (具体类)         │
 *            └──────────────────┘
 *
 * Engine接口的标准实现类
 * 每个子容器必须是Host实现，用于处理特定虚拟主机的完全限定主机名
 * @author Craig R. McClanahan
 */
public class StandardEngine extends ContainerBase implements Engine {

    private static final Log log = LogFactory.getLog(StandardEngine.class);


    // ----------------------------------------------------------- 构造函数

    /**
     * 创建带有默认基础Valve的StandardEngine组件
     */
    public StandardEngine() {
        pipeline.setBasic(new StandardEngineValve()); // 设置基础Valve为StandardEngineValve
        // 默认情况下，引擎将持有重新加载线程
        backgroundProcessorDelay = 10;
    }


    // ----------------------------------------------------- 实例变量

    /**
     * 当请求中未指定服务器主机或指定未知主机时使用的主机名
     */
    private String defaultHost = null;

    /**
     * 拥有此Engine的Service实例（如果有）
     */
    private Service service = null;

    /**
     * 此Tomcat实例的JVM路由ID，集群中所有路由ID必须唯一
     */
    private String jvmRouteId;

    /**
     * 当无法确定目标主机和上下文时，用于请求/响应的默认访问日志
     */
    private final AtomicReference<AccessLog> defaultAccessLog = new AtomicReference<>();

    // ------------------------------------------------------------- 属性访问方法

    /**
     * 获取Realm实例
     * 如果未设置Realm，则默认使用NullRealm
     * @return Realm实例
     */
    @Override
    public Realm getRealm() {
        Realm configured = super.getRealm();
        // 如果尚未调用setRealm - 默认使用NullRealm
        // 可在引擎、上下文和主机级别覆盖
        if (configured == null) {
            configured = new NullRealm();
            this.setRealm(configured);
        }
        return configured;
    }

    /**
     * 获取默认主机名
     * @return 默认主机名
     */
    @Override
    public String getDefaultHost() {
        return defaultHost;
    }

    /**
     * 设置默认主机名
     * @param host 默认主机名
     */
    @Override
    public void setDefaultHost(String host) {
        String oldDefaultHost = this.defaultHost;
        if (host == null) {
            this.defaultHost = null;
        } else {
            this.defaultHost = host.toLowerCase(Locale.ENGLISH); // 转换为小写
        }
        if (getState().isAvailable()) {
            service.getMapper().setDefaultHostName(host); // 更新映射器的默认主机名
        }
        // 通知属性变更监听器
        support.firePropertyChange("defaultHost", oldDefaultHost, this.defaultHost);
    }

    /**
     * 设置JVM路由ID
     * @param routeId JVM路由ID
     */
    @Override
    public void setJvmRoute(String routeId) {
        jvmRouteId = routeId;
    }

    /**
     * 获取JVM路由ID
     * @return JVM路由ID
     */
    @Override
    public String getJvmRoute() {
        return jvmRouteId;
    }

    /**
     * 获取拥有此Engine的Service实例
     * @return Service实例
     */
    @Override
    public Service getService() {
        return this.service;
    }

    /**
     * 设置拥有此Engine的Service实例
     * @param service Service实例
     */
    @Override
    public void setService(Service service) {
        this.service = service;
    }

    // --------------------------------------------------------- 公共方法

    /**
     * 添加子容器
     * 子容器必须是Host实现
     * @param child 要添加的子容器
     */
    @Override
    public void addChild(Container child) {
        if (!(child instanceof Host)) {
            throw new IllegalArgumentException(sm.getString("standardEngine.notHost"));
        }
        super.addChild(child);
    }

    /**
     * 禁止为此容器设置父容器
     * 因为Engine应该位于容器层次结构的顶部
     * @param container 提议的父容器
     */
    @Override
    public void setParent(Container container) {
        throw new IllegalArgumentException(sm.getString("standardEngine.notParent"));
    }

    /**
     * 初始化内部组件
     * 确保在尝试启动前存在Realm
     * @throws LifecycleException 生命周期异常
     */
    @Override
    protected void initInternal() throws LifecycleException {
        // 确保在启动前存在Realm，必要时创建默认的NullRealm
        getRealm();
        super.initInternal();
    }

    /**
     * 启动内部组件
     * 记录服务器标识信息并启动容器
     * @throws LifecycleException 生命周期异常
     */
    @Override
    protected void startInternal() throws LifecycleException {
        // 记录服务器标识信息
        if (log.isInfoEnabled()) {
            log.info(sm.getString("standardEngine.start", ServerInfo.getServerInfo()));
        }
        // 标准容器启动
        super.startInternal();
    }

    /**
     * 记录访问日志
     * 优先使用引擎的访问日志，否则查找默认主机或其ROOT上下文的访问日志
     * @param request 请求对象
     * @param response 响应对象
     * @param time 处理时间
     * @param useDefault 是否使用默认日志
     */
    @Override
    public void logAccess(Request request, Response response, long time, boolean useDefault) {
        boolean logged = false;
        // 首先尝试使用引擎自身的访问日志
        if (getAccessLog() != null) {
            accessLog.log(request, response, time);
            logged = true;
        }
        // 如果未记录且允许使用默认日志，则查找默认访问日志
        if (!logged && useDefault) {
            AccessLog newDefaultAccessLog = defaultAccessLog.get();
            if (newDefaultAccessLog == null) {
                // 如果引擎没有访问日志，查找默认主机
                Host host = (Host) findChild(getDefaultHost());
                Context context = null;
                if (host != null && host.getState().isAvailable()) {
                    newDefaultAccessLog = host.getAccessLog();
                    if (newDefaultAccessLog != null) {
                        // 找到主机的访问日志，更新默认访问日志并安装监听器
                        if (defaultAccessLog.compareAndSet(null, newDefaultAccessLog)) {
                            AccessLogListener l = new AccessLogListener(this, host, null);
                            l.install();
                        }
                    } else {
                        // 尝试默认主机的ROOT上下文
                        context = (Context) host.findChild("");
                        if (context != null && context.getState().isAvailable()) {
                            newDefaultAccessLog = context.getAccessLog();
                            if (newDefaultAccessLog != null) {
                                if (defaultAccessLog.compareAndSet(null, newDefaultAccessLog)) {
                                    AccessLogListener l = new AccessLogListener(this, null, context);
                                    l.install();
                                }
                            }
                        }
                    }
                }
                // 如果仍然没有找到，使用NoopAccessLog（无操作日志）
                if (newDefaultAccessLog == null) {
                    newDefaultAccessLog = new NoopAccessLog();
                    if (defaultAccessLog.compareAndSet(null, newDefaultAccessLog)) {
                        AccessLogListener l = new AccessLogListener(this, host, context);
                        l.install();
                    }
                }
            }
            // 使用找到的默认访问日志记录访问
            newDefaultAccessLog.log(request, response, time);
        }
    }

    /**
     * 获取父类加载器
     * 优先使用服务的父类加载器，否则使用系统类加载器
     * @return 父类加载器
     */
    @Override
    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null) {
            return parentClassLoader;
        }
        if (service != null) {
            return service.getParentClassLoader();
        }
        return ClassLoader.getSystemClassLoader();
    }

    /**
     * 获取Catalina基础目录
     * 优先使用服务所属服务器的Catalina基础目录
     * @return Catalina基础目录
     */
    @Override
    public File getCatalinaBase() {
        if (service != null) {
            Server s = service.getServer();
            if (s != null) {
                File base = s.getCatalinaBase();
                if (base != null) {
                    return base;
                }
            }
        }
        // 回退到父类实现
        return super.getCatalinaBase();
    }

    /**
     * 获取Catalina主目录
     * 优先使用服务所属服务器的Catalina主目录
     * @return Catalina主目录
     */
    @Override
    public File getCatalinaHome() {
        if (service != null) {
            Server s = service.getServer();
            if (s != null) {
                File base = s.getCatalinaHome();
                if (base != null) {
                    return base;
                }
            }
        }
        // 回退到父类实现
        return super.getCatalinaHome();
    }

    // -------------------- JMX注册相关方法 --------------------

    /**
     * 获取JMX对象名称的关键属性
     * @return JMX对象名称的关键属性
     */
    @Override
    protected String getObjectNameKeyProperties() {
        return "type=Engine";
    }

    /**
     * 获取JMX域名
     * @return 域名
     */
    @Override
    protected String getDomainInternal() {
        return getName();
    }

    // ----------------------------------------------------------- 内部类

    /**
     * 无操作访问日志实现
     * 用于没有配置访问日志的场景
     */
    protected static final class NoopAccessLog implements AccessLog {
        @Override
        public void log(Request request, Response response, long time) {
            // 无操作
        }

        @Override
        public void setRequestAttributesEnabled(boolean requestAttributesEnabled) {
            // 无操作
        }

        @Override
        public boolean getRequestAttributesEnabled() {
            // 无操作，返回false
            return false;
        }
    }

    /**
     * 访问日志监听器
     * 监听属性变更、生命周期事件和容器事件
     */
    protected static final class AccessLogListener
        implements PropertyChangeListener, LifecycleListener, ContainerListener {
        private final StandardEngine engine;
        private final Host host;
        private final Context context;
        private volatile boolean disabled = false;

        public AccessLogListener(StandardEngine engine, Host host, Context context) {
            this.engine = engine;
            this.host = host;
            this.context = context;
        }

        /**
         * 安装监听器
         * 注册属性变更、生命周期和容器事件监听器
         */
        public void install() {
            engine.addPropertyChangeListener(this);
            if (host != null) {
                host.addContainerListener(this);
                host.addLifecycleListener(this);
            }
            if (context != null) {
                context.addLifecycleListener(this);
            }
        }

        /**
         * 卸载监听器
         * 移除属性变更、生命周期和容器事件监听器
         */
        private void uninstall() {
            disabled = true;
            if (context != null) {
                context.removeLifecycleListener(this);
            }
            if (host != null) {
                host.removeLifecycleListener(this);
                host.removeContainerListener(this);
            }
            engine.removePropertyChangeListener(this);
        }

        /**
         * 处理生命周期事件
         * 当容器启动/停止/销毁时，强制重新计算默认访问日志
         */
        @Override
        public void lifecycleEvent(LifecycleEvent event) {
            if (disabled) {
                return;
            }
            String type = event.getType();
            if (AFTER_START_EVENT.equals(type) || BEFORE_STOP_EVENT.equals(type) || BEFORE_DESTROY_EVENT.equals(type)) {
                // 容器正在启动/停止/移除，强制重新计算并禁用监听器
                engine.defaultAccessLog.set(null);
                uninstall();
            }
        }

        /**
         * 处理属性变更事件
         * 当默认主机变更时，强制重新计算默认访问日志
         */
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (disabled) {
                return;
            }
            if ("defaultHost".equals(evt.getPropertyName())) {
                // 默认主机变更，强制重新计算并禁用监听器
                engine.defaultAccessLog.set(null);
                uninstall();
            }
        }

        /**
         * 处理容器事件
         * 当添加ROOT上下文时，强制重新计算默认访问日志
         */
        @Override
        public void containerEvent(ContainerEvent event) {
            // 仅对主机有用
            if (disabled) {
                return;
            }
            if (ADD_CHILD_EVENT.equals(event.getType())) {
                Context context = (Context) event.getData();
                if (context.getPath().isEmpty()) {
                    // 添加了ROOT上下文，强制重新计算并禁用监听器
                    engine.defaultAccessLog.set(null);
                    uninstall();
                }
            }
        }
    }
}