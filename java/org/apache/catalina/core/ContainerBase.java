/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.catalina.core;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.management.ObjectName;

import org.apache.catalina.*;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.util.ContextName;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.MultiThrowable;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.InlineExecutorService;

/**
 * Container接口的抽象实现，提供几乎所有实现所需的公共功能
 * 扩展此类的类必须实现invoke()方法的替代实现
 * <p>
 * 此类的所有子类都将包含对Pipeline对象的支持，该对象定义了由invoke()方法接收的每个请求的处理流程，
 * 利用"责任链"设计模式。子类应将自己的处理功能封装为Valve，并通过调用setBasic()将此Valve配置到管道中
 * <p>
 * 此实现根据JavaBeans设计模式触发属性变更事件。此外，它还会向通过addContainerListener()注册的监听器触发以下ContainerEvent事件：
 * （事件类型说明见类注释原文表格）
 * <p>
 * 实现类触发的其他事件应在实现类的类注释中记录
 * @author Craig R. McClanahan
 */
public abstract class ContainerBase extends LifecycleMBeanBase implements Container {

    private static final Log log = LogFactory.getLog(ContainerBase.class);

    /**
     * 使用此类权限执行addChild操作
     * addChild可通过XML解析器调用，这允许XML解析器具有比Tomcat更少的权限
     */
    protected class PrivilegedAddChild implements PrivilegedAction<Void> {
        private final Container child;

        PrivilegedAddChild(Container child) {
            this.child = child;
        }

        @Override
        public Void run() {
            addChildInternal(child);
            return null;
        }
    }

    // ----------------------------------------------------- 实例变量

    /**
     * 属于此容器的子容器，按键名(name)存储
     */
    protected final HashMap<String, Container> children = new HashMap<>();
    private final ReadWriteLock childrenLock = new ReentrantReadWriteLock();

    /**
     * 此组件的处理器延迟时间
     */
    protected int backgroundProcessorDelay = -1;

    /**
     * 用于控制后台处理器的未来任务
     */
    protected ScheduledFuture<?> backgroundProcessorFuture;
    protected ScheduledFuture<?> monitorFuture;

    /**
     * 此容器的容器事件监听器
     * 实现为CopyOnWriteArrayList，因为监听器可能会调用添加/删除自身或其他监听器的方法
     */
    protected final List<ContainerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 与此容器关联的日志记录器实现
     */
    protected Log logger = null;

    /**
     * 关联的日志记录器名称
     */
    protected String logName = null;

    /**
     * 与此容器关联的集群
     */
    protected Cluster cluster = null;
    private final ReadWriteLock clusterLock = new ReentrantReadWriteLock();

    /**
     * 此容器的人类可读名称
     */
    protected String name = null;

    /**
     * 此容器所属的父容器
     */
    protected Container parent = null;

    /**
     * 安装Loader时要配置的父类加载器
     */
    protected ClassLoader parentClassLoader = null;

    /**
     * 与此容器关联的Pipeline对象
     */
    protected final Pipeline pipeline = new StandardPipeline(this);

    /**
     * 与此容器关联的Realm
     */
    private volatile Realm realm = null;

    /**
     * 用于控制对Realm访问的锁
     */
    private final ReadWriteLock realmLock = new ReentrantReadWriteLock();

    /**
     * 此包的字符串管理器
     */
    protected static final StringManager sm = StringManager.getManager(ContainerBase.class);

    /**
     * 添加子容器时是否自动启动
     */
    protected boolean startChildren = true;

    /**
     * 此组件的属性变更支持
     */
    protected final PropertyChangeSupport support = new PropertyChangeSupport(this);

    /**
     * 用于此容器正常处理的访问日志，该日志已在处理链的早期处理过
     */
    protected volatile AccessLog accessLog = null;
    private volatile boolean accessLogScanComplete = false;

    /**
     * 可用于处理与此容器关联的任何子容器的启动和停止事件的线程数
     */
    private int startStopThreads = 1;
    protected ExecutorService startStopExecutor;

    // ------------------------------------------------------------- 属性访问方法

    @Override
    public int getStartStopThreads() {
        return startStopThreads;
    }

    @Override
    public void setStartStopThreads(int startStopThreads) {
        int oldStartStopThreads = this.startStopThreads;
        this.startStopThreads = startStopThreads;
        // 使用本地副本确保线程安全
        if (oldStartStopThreads != startStopThreads && startStopExecutor != null) {
            reconfigureStartStopExecutor(getStartStopThreads());
        }
    }

    @Override
    public int getBackgroundProcessorDelay() {
        return backgroundProcessorDelay;
    }

    @Override
    public void setBackgroundProcessorDelay(int delay) {
        backgroundProcessorDelay = delay;
    }

    @Override
    public Log getLogger() {
        if (logger != null) {
            return logger;
        }
        logger = LogFactory.getLog(getLogName());
        return logger;
    }

    @Override
    public String getLogName() {
        if (logName != null) {
            return logName;
        }
        String loggerName = null;
        Container current = this;
        // 构建日志名称，从当前容器向上遍历父容器
        while (current != null) {
            String name = current.getName();
            if ((name == null) || (name.isEmpty())) {
                name = "/";
            } else if (name.startsWith("##")) {
                name = "/" + name;
            }
            loggerName = "[" + name + "]" + ((loggerName != null) ? ("." + loggerName) : "");
            current = current.getParent();
        }
        logName = ContainerBase.class.getName() + "." + loggerName;
        return logName;
    }

    @Override
    public Cluster getCluster() {
        Lock readLock = clusterLock.readLock();
        readLock.lock();
        try {
            if (cluster != null) {
                return cluster;
            }
            if (parent != null) {
                return parent.getCluster();
            }
            return null;
        } finally {
            readLock.unlock();
        }
    }

    /*
     * 仅提供对附加到此容器的集群组件的访问
     */
    protected Cluster getClusterInternal() {
        Lock readLock = clusterLock.readLock();
        readLock.lock();
        try {
            return cluster;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void setCluster(Cluster cluster) {
        Cluster oldCluster;
        Lock writeLock = clusterLock.writeLock();
        writeLock.lock();
        try {
            oldCluster = this.cluster;
            if (oldCluster == cluster) {
                return;
            }
            this.cluster = cluster;
            // 必要时启动新组件
            if (cluster != null) {
                cluster.setContainer(this);
            }
        } finally {
            writeLock.unlock();
        }
        // 必要时停止旧组件
        if (getState().isAvailable() && (oldCluster instanceof Lifecycle)) {
            try {
                ((Lifecycle) oldCluster).stop();
            } catch (LifecycleException e) {
                log.error(sm.getString("containerBase.cluster.stop"), e);
            }
        }
        if (getState().isAvailable() && (cluster instanceof Lifecycle)) {
            try {
                ((Lifecycle) cluster).start();
            } catch (LifecycleException e) {
                log.error(sm.getString("containerBase.cluster.start"), e);
            }
        }
        // 向感兴趣的监听器报告此属性变更
        support.firePropertyChange("cluster", oldCluster, cluster);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        if (name == null) {
            throw new IllegalArgumentException(sm.getString("containerBase.nullName"));
        }
        String oldName = this.name;
        this.name = name;
        support.firePropertyChange("name", oldName, this.name);
    }

    /**
     * 返回此容器的子容器添加时是否自动启动
     * @return 如果子容器将自动启动则为true
     */
    public boolean getStartChildren() {
        return startChildren;
    }

    /**
     * 设置此容器的子容器添加时是否自动启动
     * @param startChildren startChildren标志的新值
     */
    public void setStartChildren(boolean startChildren) {
        boolean oldStartChildren = this.startChildren;
        this.startChildren = startChildren;
        support.firePropertyChange("startChildren", oldStartChildren, this.startChildren);
    }

    @Override
    public Container getParent() {
        return parent;
    }

    @Override
    public void setParent(Container container) {
        Container oldParent = this.parent;
        this.parent = container;
        support.firePropertyChange("parent", oldParent, this.parent);
    }

    @Override
    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null) {
            return parentClassLoader;
        }
        if (parent != null) {
            return parent.getParentClassLoader();
        }
        return ClassLoader.getSystemClassLoader();
    }

    @Override
    public void setParentClassLoader(ClassLoader parent) {
        ClassLoader oldParentClassLoader = this.parentClassLoader;
        this.parentClassLoader = parent;
        support.firePropertyChange("parentClassLoader", oldParentClassLoader, this.parentClassLoader);
    }

    @Override
    public Pipeline getPipeline() {
        return this.pipeline;
    }

    @Override
    public Realm getRealm() {
        Lock l = realmLock.readLock();
        l.lock();
        try {
            if (realm != null) {
                return realm;
            }
            if (parent != null) {
                return parent.getRealm();
            }
            return null;
        } finally {
            l.unlock();
        }
    }

    protected Realm getRealmInternal() {
        Lock l = realmLock.readLock();
        l.lock();
        try {
            return realm;
        } finally {
            l.unlock();
        }
    }

    @Override
    public void setRealm(Realm realm) {
        Realm oldRealm;
        Lock l = realmLock.writeLock();
        l.lock();
        try {
            oldRealm = this.realm;
            if (oldRealm == realm) {
                return;
            }
            this.realm = realm;
            // 必要时启动新组件
            if (realm != null) {
                realm.setContainer(this);
            }
        } finally {
            l.unlock();
        }
        // 必要时停止旧组件
        if (getState().isAvailable() && oldRealm instanceof Lifecycle) {
            try {
                ((Lifecycle) oldRealm).stop();
            } catch (LifecycleException e) {
                log.error(sm.getString("containerBase.realm.stop"), e);
            }
        }
        if (getState().isAvailable() && realm instanceof Lifecycle) {
            try {
                ((Lifecycle) realm).start();
            } catch (LifecycleException e) {
                log.error(sm.getString("containerBase.realm.start"), e);
            }
        }
        // 向感兴趣的监听器报告此属性变更
        support.firePropertyChange("realm", oldRealm, this.realm);
    }

    // ------------------------------------------------------ Container方法

    @Override
    public void addChild(Container child) {
        if (Globals.IS_SECURITY_ENABLED) {
            PrivilegedAction<Void> dp = new PrivilegedAddChild(child);
            AccessController.doPrivileged(dp);
        } else {
            addChildInternal(child);
        }
    }

    private void addChildInternal(Container child) {
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("containerBase.child.add", child, this));
        }
        childrenLock.writeLock().lock();
        try {
            if (children.get(child.getName()) != null) {
                throw new IllegalArgumentException(sm.getString("containerBase.child.notUnique", child.getName()));
            }
            child.setParent(this); // 可能抛出IAE
            children.put(child.getName(), child);
        } finally {
            childrenLock.writeLock().unlock();
        }
        fireContainerEvent(ADD_CHILD_EVENT, child);
        // 启动子容器
        // 不要在同步块内执行 - start可能是一个缓慢的过程
        try {
            if ((getState().isAvailable() || LifecycleState.STARTING_PREP.equals(getState())) && startChildren) {
                child.start();
            }
        } catch (LifecycleException e) {
            throw new IllegalStateException(sm.getString("containerBase.child.start"), e);
        }
    }

    @Override
    public void addContainerListener(ContainerListener listener) {
        listeners.add(listener);
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        support.addPropertyChangeListener(listener);
    }

    @Override
    public Container findChild(String name) {
        if (name == null) {
            return null;
        }
        childrenLock.readLock().lock();
        try {
            return children.get(name);
        } finally {
            childrenLock.readLock().unlock();
        }
    }

    @Override
    public Container[] findChildren() {
        childrenLock.readLock().lock();
        try {
            return children.values().toArray(new Container[0]);
        } finally {
            childrenLock.readLock().unlock();
        }
    }

    @Override
    public ContainerListener[] findContainerListeners() {
        return listeners.toArray(new ContainerListener[0]);
    }

    @Override
    public void removeChild(Container child) {
        if (child == null) {
            return;
        }
        try {
            if (child.getState().isAvailable()) {
                child.stop();
            }
        } catch (LifecycleException e) {
            log.error(sm.getString("containerBase.child.stop"), e);
        }
        boolean destroy = false;
        try {
            // 如果child.destroy()已经被调用，这会触发此调用。如果是这种情况，无需再次销毁子容器
            if (!LifecycleState.DESTROYING.equals(child.getState())) {
                child.destroy();
                destroy = true;
            }
        } catch (LifecycleException e) {
            log.error(sm.getString("containerBase.child.destroy"), e);
        }
        if (!destroy) {
            fireContainerEvent(REMOVE_CHILD_EVENT, child);
        }
        childrenLock.writeLock().lock();
        try {
            children.remove(child.getName());
        } finally {
            childrenLock.writeLock().unlock();
        }
    }

    @Override
    public void removeContainerListener(ContainerListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        support.removePropertyChangeListener(listener);
    }

    private void reconfigureStartStopExecutor(int threads) {
        if (threads == 1) {
            // 使用伪执行器
            if (!(startStopExecutor instanceof InlineExecutorService)) {
                startStopExecutor = new InlineExecutorService();
            }
        } else {
            // 将实用程序执行委托给Service
            Server server = Container.getService(this).getServer();
            server.setUtilityThreads(threads);
            startStopExecutor = server.getUtilityExecutor();
        }
    }

    /**
     * 启动此组件并实现LifecycleBase.startInternal()的要求
     * @exception LifecycleException 如果此组件检测到致命错误，导致无法使用此组件
     */
    @Override
    protected void startInternal() throws LifecycleException {
        reconfigureStartStopExecutor(getStartStopThreads());
        // 启动我们的下属组件（如果有）
        logger = null;
        getLogger();
        Cluster cluster = getClusterInternal();
        if (cluster instanceof Lifecycle) {
            ((Lifecycle) cluster).start();
        }
        Realm realm = getRealmInternal();
        if (realm instanceof Lifecycle) {
            ((Lifecycle) realm).start();
        }
        // 启动我们的子容器（如果有）
        Container[] children = findChildren();
        List<Future<Void>> results = new ArrayList<>(children.length);
        for (Container child : children) {
            results.add(startStopExecutor.submit(new StartChild(child)));
        }
        MultiThrowable multiThrowable = null;
        for (Future<Void> result : results) {
            try {
                result.get();
            } catch (Throwable e) {
                log.error(sm.getString("containerBase.threadedStartFailed"), e);
                if (multiThrowable == null) {
                    multiThrowable = new MultiThrowable();
                }
                multiThrowable.add(e);
            }
        }
        if (multiThrowable != null) {
            throw new LifecycleException(sm.getString("containerBase.threadedStartFailed"),
                multiThrowable.getThrowable());
        }
        // 启动我们管道中的Valves（包括基本Valve，如果有的话）
        if (pipeline instanceof Lifecycle) {
            ((Lifecycle) pipeline).start();
        }
        setState(LifecycleState.STARTING);
        // 启动我们的线程
        if (backgroundProcessorDelay > 0) {
            monitorFuture = Container.getService(ContainerBase.this).getServer().getUtilityExecutor()
                .scheduleWithFixedDelay(new ContainerBackgroundProcessorMonitor(), 0, 60, TimeUnit.SECONDS);
        }
    }

    /**
     * 停止此组件并实现LifecycleBase.stopInternal()的要求
     * @exception LifecycleException 如果此组件检测到致命错误，导致无法使用此组件
     */
    @Override
    protected void stopInternal() throws LifecycleException {
        // 停止我们的线程
        if (monitorFuture != null) {
            monitorFuture.cancel(true);
            monitorFuture = null;
        }
        threadStop();
        setState(LifecycleState.STOPPING);
        // 停止我们管道中的Valves（包括基本Valve，如果有的话）
        if (pipeline instanceof Lifecycle && ((Lifecycle) pipeline).getState().isAvailable()) {
            ((Lifecycle) pipeline).stop();
        }
        // 停止我们的子容器（如果有）
        Container[] children = findChildren();
        List<Future<Void>> results = new ArrayList<>(children.length);
        for (Container child : children) {
            results.add(startStopExecutor.submit(new StopChild(child)));
        }
        boolean fail = false;
        for (Future<Void> result : results) {
            try {
                result.get();
            } catch (Exception e) {
                log.error(sm.getString("containerBase.threadedStopFailed"), e);
                fail = true;
            }
        }
        if (fail) {
            throw new LifecycleException(sm.getString("containerBase.threadedStopFailed"));
        }
        // 停止我们的下属组件（如果有）
        Realm realm = getRealmInternal();
        if (realm instanceof Lifecycle) {
            ((Lifecycle) realm).stop();
        }
        Cluster cluster = getClusterInternal();
        if (cluster instanceof Lifecycle) {
            ((Lifecycle) cluster).stop();
        }
        // 如果init失败，这可能为null
        if (startStopExecutor != null) {
            startStopExecutor.shutdownNow();
            startStopExecutor = null;
        }
    }

    @Override
    protected void destroyInternal() throws LifecycleException {
        Realm realm = getRealmInternal();
        if (realm instanceof Lifecycle) {
            ((Lifecycle) realm).destroy();
        }
        Cluster cluster = getClusterInternal();
        if (cluster instanceof Lifecycle) {
            ((Lifecycle) cluster).destroy();
        }
        // 停止我们管道中的Valves（包括基本Valve，如果有的话）
        if (pipeline instanceof Lifecycle) {
            ((Lifecycle) pipeline).destroy();
        }
        // 移除子容器，现在这个容器正在被销毁
        for (Container child : findChildren()) {
            removeChild(child);
        }
        // 如果子容器被直接销毁，这是必需的
        if (parent != null) {
            parent.removeChild(this);
        }
        super.destroyInternal();
    }

    @Override
    public void logAccess(Request request, Response response, long time, boolean useDefault) {
        boolean logged = false;
        if (getAccessLog() != null) {
            getAccessLog().log(request, response, time);
            logged = true;
        }
        if (getParent() != null) {
            // 一旦请求/响应被记录一次，就不需要使用默认日志记录器
            getParent().logAccess(request, response, time, (useDefault && !logged));
        }
    }

    @Override
    public AccessLog getAccessLog() {
        if (accessLogScanComplete) {
            return accessLog;
        }
        AccessLogAdapter adapter = null;
        Valve[] valves = getPipeline().getValves();
        for (Valve valve : valves) {
            if (valve instanceof AccessLog) {
                if (adapter == null) {
                    adapter = new AccessLogAdapter((AccessLog) valve);
                } else {
                    adapter.add((AccessLog) valve);
                }
            }
        }
        if (adapter != null) {
            accessLog = adapter;
        }
        accessLogScanComplete = true;
        return accessLog;
    }

    // ------------------------------------------------------- Pipeline方法

    /**
     * 便利方法，供Digester使用，简化向容器添加Valves的过程
     * 有关完整详细信息，请参阅Pipeline.addValve(Valve)
     * 除Digester之外的组件应使用getPipeline().addValve(Valve)
     * @param valve 要添加的Valve
     * @exception IllegalArgumentException 如果此容器拒绝接受指定的Valve
     * @exception IllegalArgumentException 如果指定的Valve拒绝与此容器关联
     * @exception IllegalStateException 如果指定的Valve已与其他容器关联
     */
    public synchronized void addValve(Valve valve) {
        pipeline.addValve(valve);
    }

    @Override
    public synchronized void backgroundProcess() {
        if (!getState().isAvailable()) {
            return;
        }
        Cluster cluster = getClusterInternal();
        if (cluster != null) {
            try {
                cluster.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("containerBase.backgroundProcess.cluster", cluster), e);
            }
        }
        Realm realm = getRealmInternal();
        if (realm != null) {
            try {
                realm.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("containerBase.backgroundProcess.realm", realm), e);
            }
        }
        Valve current = pipeline.getFirst();
        while (current != null) {
            try {
                current.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("containerBase.backgroundProcess.valve", current), e);
            }
            current = current.getNext();
        }
        fireLifecycleEvent(PERIODIC_EVENT, null);
    }

    @Override
    public File getCatalinaBase() {
        if (parent == null) {
            return null;
        }
        return parent.getCatalinaBase();
    }

    @Override
    public File getCatalinaHome() {
        if (parent == null) {
            return null;
        }
        return parent.getCatalinaHome();
    }

    // ------------------------------------------------------ 受保护的方法

    @Override
    public void fireContainerEvent(String type, Object data) {
        if (listeners.isEmpty()) {
            return;
        }
        ContainerEvent event = new ContainerEvent(this, type, data);
        // 注意：每个都使用内部迭代器，因此这是安全的
        for (ContainerListener listener : listeners) {
            listener.containerEvent(event);
        }
    }

    // -------------------- JMX和注册 --------------------

    @Override
    protected String getDomainInternal() {
        Container p = this.getParent();
        if (p == null) {
            return null;
        } else {
            return p.getDomain();
        }
    }

    @Override
    public String getMBeanKeyProperties() {
        Container c = this;
        StringBuilder keyProperties = new StringBuilder();
        int containerCount = 0;
        // 向上遍历容器层次结构，为每个容器向名称添加一个组件
        while (!(c instanceof Engine)) {
            if (c instanceof Wrapper) {
                keyProperties.insert(0, ",servlet=");
                keyProperties.insert(9, c.getName());
            } else if (c instanceof Context) {
                keyProperties.insert(0, ",context=");
                ContextName cn = new ContextName(c.getName(), false);
                keyProperties.insert(9, cn.getDisplayName());
            } else if (c instanceof Host) {
                keyProperties.insert(0, ",host=");
                keyProperties.insert(6, c.getName());
            } else if (c == null) {
                // 可能在单元测试和/或某些嵌入场景中发生
                keyProperties.append(",container");
                keyProperties.append(containerCount);
                keyProperties.append("=null");
                break;
            } else {
                // 应该永远不会发生...
                keyProperties.append(",container");
                keyProperties.append(containerCount++);
                keyProperties.append('=');
                keyProperties.append(c.getName());
            }
            c = c.getParent();
        }
        return keyProperties.toString();
    }

    public ObjectName[] getChildren() {
        List<ObjectName> names;
        childrenLock.readLock().lock();
        try {
            names = new ArrayList<>(children.size());
            for (Container next : children.values()) {
                if (next instanceof ContainerBase) {
                    names.add(next.getObjectName());
                }
            }
        } finally {
            childrenLock.readLock().unlock();
        }
        return names.toArray(new ObjectName[0]);
    }

    // -------------------- 后台线程 --------------------

    /**
     * 启动将定期检查会话超时的后台线程
     */
    protected void threadStart() {
        if (backgroundProcessorDelay > 0 &&
            (getState().isAvailable() || LifecycleState.STARTING_PREP.equals(getState())) &&
            (backgroundProcessorFuture == null || backgroundProcessorFuture.isDone())) {
            if (backgroundProcessorFuture != null && backgroundProcessorFuture.isDone()) {
                // 执行计划任务时出错，获取并记录它
                try {
                    backgroundProcessorFuture.get();
                } catch (InterruptedException | ExecutionException e) {
                    log.error(sm.getString("containerBase.backgroundProcess.error"), e);
                }
            }
            backgroundProcessorFuture = Container.getService(this).getServer().getUtilityExecutor()
                .scheduleWithFixedDelay(new ContainerBackgroundProcessor(), backgroundProcessorDelay,
                    backgroundProcessorDelay, TimeUnit.SECONDS);
        }
    }

    /**
     * 停止定期检查会话超时的后台线程
     */
    protected void threadStop() {
        if (backgroundProcessorFuture != null) {
            backgroundProcessorFuture.cancel(true);
            backgroundProcessorFuture = null;
        }
    }

    @Override
    public final String toString() {
        StringBuilder sb = new StringBuilder();
        Container parent = getParent();
        if (parent != null) {
            sb.append(parent);
            sb.append('.');
        }
        sb.append(this.getClass().getSimpleName());
        sb.append('[');
        sb.append(getName());
        sb.append(']');
        return sb.toString();
    }

    // ------------------------------- 容器后台处理器监控内部类

    protected class ContainerBackgroundProcessorMonitor implements Runnable {
        @Override
        public void run() {
            if (getState().isAvailable()) {
                threadStart();
            }
        }
    }

    /**
     * 私有可运行类，用于在固定延迟后调用此容器及其子容器的backgroundProcess方法
     */
    protected class ContainerBackgroundProcessor implements Runnable {
        @Override
        public void run() {
            processChildren(ContainerBase.this);
        }

        protected void processChildren(Container container) {
            ClassLoader originalClassLoader = null;
            try {
                if (container instanceof Context) {
                    Loader loader = ((Context) container).getLoader();
                    // 对于FailedContext实例，Loader将为null
                    if (loader == null) {
                        return;
                    }
                    // 确保在Web应用程序的类加载器下执行Context和Wrapper的后台处理
                    originalClassLoader = ((Context) container).bind(false, null);
                }
                container.backgroundProcess();
                Container[] children = container.findChildren();
                for (Container child : children) {
                    if (child.getBackgroundProcessorDelay() <= 0) {
                        processChildren(child);
                    }
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("containerBase.backgroundProcess.error"), t);
            } finally {
                if (container instanceof Context) {
                    ((Context) container).unbind(false, originalClassLoader);
                }
            }
        }
    }

    // ---------------------------- 与启动/停止Executor一起使用的内部类

    private static class StartChild implements Callable<Void> {
        private final Container child;

        StartChild(Container child) {
            this.child = child;
        }

        @Override
        public Void call() throws LifecycleException {
            child.start();
            return null;
        }
    }

    private static class StopChild implements Callable<Void> {
        private final Container child;

        StopChild(Container child) {
            this.child = child;
        }

        @Override
        public Void call() throws LifecycleException {
            if (child.getState().isAvailable()) {
                child.stop();
            }
            return null;
        }
    }
}