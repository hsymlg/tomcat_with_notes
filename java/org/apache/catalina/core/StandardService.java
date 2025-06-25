/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.catalina.core;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.management.ObjectName;

import org.apache.catalina.Container;
import org.apache.catalina.Engine;
import org.apache.catalina.Executor;
import org.apache.catalina.JmxEnabled;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.mapper.Mapper;
import org.apache.catalina.mapper.MapperListener;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;


/**
 * Service接口的标准实现类
 * 关联的Container通常是Engine实例，但这不是强制要求
 * @author Craig R. McClanahan
 */
public class StandardService extends LifecycleMBeanBase implements Service {

    // 日志记录器，用于输出服务相关日志
    private static final Log log = LogFactory.getLog(StandardService.class);
    // 字符串资源管理器，用于获取国际化提示信息
    private static final StringManager sm = StringManager.getManager(StandardService.class);


    // ----------------------------------------------------- 实例变量

    /** 服务的名称 */
    private String name = null;

    /** 拥有此服务的Server实例 */
    private Server server = null;

    /** 属性变更支持类，用于通知监听器属性变化 */
    protected final PropertyChangeSupport support = new PropertyChangeSupport(this);

    /** 与此服务关联的Connector数组 */
    protected Connector[] connectors = new Connector[0];
    // 连接器数组的读写锁，保证多线程安全访问
    private final ReadWriteLock connectorsLock = new ReentrantReadWriteLock();

    /** 服务持有的执行器列表 */
    protected final ArrayList<Executor> executors = new ArrayList<>();
    // 执行器列表的读写锁，保证多线程安全访问
    private final ReadWriteLock executorsLock = new ReentrantReadWriteLock();

    /** 服务关联的Engine实例（核心容器） */
    private Engine engine = null;

    /** 父类加载器 */
    private ClassLoader parentClassLoader = null;

    /** 请求映射器，用于将请求映射到对应的容器 */
    protected final Mapper mapper = new Mapper();

    /** 映射器监听器，监听容器变化并更新映射关系 */
    protected final MapperListener mapperListener = new MapperListener(this);

    /** 优雅停止时的等待毫秒数 */
    private long gracefulStopAwaitMillis = 0;


    // ------------------------------------------------------------- 属性访问方法

    /** 获取优雅停止等待时间 */
    public long getGracefulStopAwaitMillis() {
        return gracefulStopAwaitMillis;
    }

    /** 设置优雅停止等待时间 */
    public void setGracefulStopAwaitMillis(long gracefulStopAwaitMillis) {
        this.gracefulStopAwaitMillis = gracefulStopAwaitMillis;
    }

    /** 获取请求映射器 */
    @Override
    public Mapper getMapper() {
        return mapper;
    }

    /** 获取服务关联的容器（通常是Engine） */
    @Override
    public Engine getContainer() {
        return engine;
    }

    /** 设置服务关联的容器（通常是Engine） */
    @Override
    public void setContainer(Engine engine) {
        // 保存旧的Engine实例
        Engine oldEngine = this.engine;
        if (oldEngine != null) {
            oldEngine.setService(null); // 解除旧Engine与服务的关联
        }
        this.engine = engine; // 设置新的Engine实例
        if (this.engine != null) {
            this.engine.setService(this); // 建立新Engine与服务的关联
        }
        // 如果服务已处于可用状态，处理引擎切换
        if (getState().isAvailable()) {
            if (this.engine != null) {
                try {
                    this.engine.start(); // 启动新Engine
                } catch (LifecycleException e) {
                    log.error(sm.getString("standardService.engine.startFailed"), e);
                }
            }
            // 重启映射监听器以使用新引擎
            try {
                mapperListener.stop();
            } catch (LifecycleException e) {
                log.error(sm.getString("standardService.mapperListener.stopFailed"), e);
            }
            try {
                mapperListener.start();
            } catch (LifecycleException e) {
                log.error(sm.getString("standardService.mapperListener.startFailed"), e);
            }
            if (oldEngine != null) {
                try {
                    oldEngine.stop(); // 停止旧Engine
                } catch (LifecycleException e) {
                    log.error(sm.getString("standardService.engine.stopFailed"), e);
                }
            }
        }
        // 通知监听器容器已变更
        support.firePropertyChange("container", oldEngine, this.engine);
    }

    /** 获取服务名称 */
    @Override
    public String getName() {
        return name;
    }

    /** 设置服务名称 */
    @Override
    public void setName(String name) {
        this.name = name;
    }

    /** 获取服务所属的Server实例 */
    @Override
    public Server getServer() {
        return this.server;
    }

    /** 设置服务所属的Server实例 */
    @Override
    public void setServer(Server server) {
        this.server = server;
    }


    // --------------------------------------------------------- 公共方法

    /** 添加Connector到服务 */
    @Override
    public void addConnector(Connector connector) {
        // 获取写锁，保证线程安全
        Lock writeLock = connectorsLock.writeLock();
        writeLock.lock();
        try {
            connector.setService(this); // 设置Connector所属的服务
            // 扩容Connector数组并添加新Connector
            Connector[] results = new Connector[connectors.length + 1];
            System.arraycopy(connectors, 0, results, 0, connectors.length);
            results[connectors.length] = connector;
            connectors = results;
        } finally {
            writeLock.unlock(); // 释放写锁
        }
        try {
            if (getState().isAvailable()) {
                connector.start(); // 启动Connector
            }
        } catch (LifecycleException e) {
            throw new IllegalArgumentException(sm.getString("standardService.connector.startFailed", connector), e);
        }
        // 通知监听器Connector已添加
        support.firePropertyChange("connector", null, connector);
    }

    /** 获取所有Connector的JMX对象名称 */
    public ObjectName[] getConnectorNames() {
        // 获取读锁，保证线程安全
        Lock readLock = connectorsLock.readLock();
        readLock.lock();
        try {
            ObjectName[] results = new ObjectName[connectors.length];
            for (int i = 0; i < results.length; i++) {
                results[i] = connectors[i].getObjectName(); // 获取每个Connector的JMX名称
            }
            return results;
        } finally {
            readLock.unlock(); // 释放读锁
        }
    }

    /** 添加属性变更监听器 */
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        support.addPropertyChangeListener(listener);
    }

    /** 查找所有Connector */
    @Override
    public Connector[] findConnectors() {
        // 获取读锁，保证线程安全
        Lock readLock = connectorsLock.readLock();
        readLock.lock();
        try {
            // 返回Connector数组的浅拷贝
            return connectors.clone();
        } finally {
            readLock.unlock(); // 释放读锁
        }
    }

    /** 从服务中移除Connector */
    @Override
    public void removeConnector(Connector connector) {
        // 获取写锁，保证线程安全
        Lock writeLock = connectorsLock.writeLock();
        writeLock.lock();
        try {
            // 找到Connector在数组中的位置并移除
            int j = -1;
            for (int i = 0; i < connectors.length; i++) {
                if (connector == connectors[i]) {
                    j = i;
                    break;
                }
            }
            if (j < 0) {
                return;
            }
            int k = 0;
            Connector[] results = new Connector[connectors.length - 1];
            for (int i = 0; i < connectors.length; i++) {
                if (i != j) {
                    results[k++] = connectors[i];
                }
            }
            connectors = results;
        } finally {
            writeLock.unlock(); // 释放写锁
        }
        // 如果Connector处于可用状态，停止它
        if (connector.getState().isAvailable()) {
            try {
                connector.stop();
            } catch (LifecycleException e) {
                log.error(sm.getString("standardService.connector.stopFailed", connector), e);
            }
        }
        connector.setService(null); // 解除Connector与服务的关联
        // 通知监听器Connector已移除
        support.firePropertyChange("connector", connector, null);
    }

    /** 移除属性变更监听器 */
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        support.removePropertyChangeListener(listener);
    }

    /** 返回服务的字符串表示 */
    @Override
    public String toString() {
        return "StandardService[" + getName() + "]";
    }

    /** 添加Executor到服务 */
    @Override
    public void addExecutor(Executor ex) {
        boolean added = false;
        // 获取写锁，保证线程安全
        executorsLock.writeLock().lock();
        try {
            if (!executors.contains(ex)) {
                added = true;
                executors.add(ex); // 添加Executor到列表
            }
        } finally {
            executorsLock.writeLock().unlock(); // 释放写锁
        }
        // 如果服务已启动且Executor是新添加的，启动它
        if (added && getState().isAvailable()) {
            try {
                ex.start();
            } catch (LifecycleException x) {
                log.error(sm.getString("standardService.executor.start"), x);
            }
        }
    }

    /** 查找所有Executor */
    @Override
    public Executor[] findExecutors() {
        // 获取读锁，保证线程安全
        executorsLock.readLock().lock();
        try {
            return executors.toArray(new Executor[0]); // 返回Executor数组
        } finally {
            executorsLock.readLock().unlock(); // 释放读锁
        }
    }

    /** 根据名称查找Executor */
    @Override
    public Executor getExecutor(String executorName) {
        // 获取读锁，保证线程安全
        executorsLock.readLock().lock();
        try {
            for (Executor executor : executors) {
                if (executorName.equals(executor.getName())) {
                    return executor; // 找到匹配名称的Executor
                }
            }
        } finally {
            executorsLock.readLock().unlock(); // 释放读锁
        }
        return null; // 未找到匹配的Executor
    }

    /** 从服务中移除Executor */
    @Override
    public void removeExecutor(Executor ex) {
        boolean removed;
        // 获取写锁，保证线程安全
        executorsLock.writeLock().lock();
        try {
            removed = executors.remove(ex); // 从列表中移除Executor
        } finally {
            executorsLock.writeLock().unlock(); // 释放写锁
        }
        // 如果Executor被移除且服务已启动，停止它
        if (removed && getState().isAvailable()) {
            try {
                ex.stop();
            } catch (LifecycleException e) {
                log.error(sm.getString("standardService.executor.stop"), e);
            }
        }
    }

    /** 启动服务的内部组件（容器、执行器、连接器等） */
    @Override
    protected void startInternal() throws LifecycleException {
        if (log.isInfoEnabled()) {
            log.info(sm.getString("standardService.start.name", this.name));
        }
        setState(LifecycleState.STARTING); // 设置服务状态为启动中

        // 首先启动关联的容器（Engine）
        if (engine != null) {
            engine.start();
        }

        // 启动所有执行器
        for (Executor executor : findExecutors()) {
            executor.start();
        }

        // 启动映射监听器
        mapperListener.start();

        // 最后启动所有连接器
        for (Connector connector : findConnectors()) {
            // 如果连接器未启动失败，启动它
            if (connector.getState() != LifecycleState.FAILED) {
                connector.start();
            }
        }
    }

    /** 停止服务的内部组件（容器、执行器、连接器等） */
    @Override
    protected void stopInternal() throws LifecycleException {
        // 获取所有连接器
        Connector[] connectors = findConnectors();
        // 对每个连接器发起优雅关闭
        for (Connector connector : connectors) {
            connector.getProtocolHandler().closeServerSocketGraceful();
        }

        // 等待优雅关闭完成
        long waitMillis = gracefulStopAwaitMillis;
        if (waitMillis > 0) {
            for (Connector connector : connectors) {
                waitMillis = connector.getProtocolHandler().awaitConnectionsClose(waitMillis);
            }
        }

        // 暂停所有连接器
        for (Connector connector : connectors) {
            connector.pause();
        }

        if (log.isInfoEnabled()) {
            log.info(sm.getString("standardService.stop.name", this.name));
        }
        setState(LifecycleState.STOPPING); // 设置服务状态为停止中

        // 一旦连接器全部暂停，停止关联的容器（Engine）
        if (engine != null) {
            engine.stop();
        }

        // 现在停止所有连接器
        for (Connector connector : connectors) {
            if (!LifecycleState.STARTED.equals(connector.getState())) {
                continue; // 只停止已启动的连接器
            }
            connector.stop();
        }

        // 如果映射监听器未初始化，停止它
        if (mapperListener.getState() != LifecycleState.INITIALIZED) {
            mapperListener.stop();
        }

        // 停止所有执行器
        for (Executor executor : findExecutors()) {
            executor.stop();
        }
    }

    /** 初始化服务的内部组件 */
    @Override
    protected void initInternal() throws LifecycleException {
        super.initInternal(); // 调用父类初始化方法

        if (engine != null) {
            engine.init(); // 初始化关联的容器（Engine）
        }

        // 初始化所有执行器
        for (Executor executor : findExecutors()) {
            if (executor instanceof JmxEnabled) {
                ((JmxEnabled) executor).setDomain(getDomain());
            }
            executor.init();
        }

        // 初始化映射监听器
        mapperListener.init();

        // 初始化所有连接器
        for (Connector connector : findConnectors()) {
            connector.init();
        }
    }

    /** 销毁服务的内部组件 */
    @Override
    protected void destroyInternal() throws LifecycleException {
        mapperListener.destroy(); // 销毁映射监听器

        // 销毁所有连接器
        for (Connector connector : findConnectors()) {
            connector.destroy();
        }

        // 销毁所有执行器
        for (Executor executor : findExecutors()) {
            executor.destroy();
        }

        if (engine != null) {
            engine.destroy(); // 销毁关联的容器（Engine）
        }

        super.destroyInternal(); // 调用父类销毁方法
    }

    /** 获取父类加载器 */
    @Override
    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null) {
            return parentClassLoader;
        }
        if (server != null) {
            return server.getParentClassLoader(); // 使用所属Server的父类加载器
        }
        return ClassLoader.getSystemClassLoader(); // 使用系统类加载器
    }

    /** 设置父类加载器 */
    @Override
    public void setParentClassLoader(ClassLoader parent) {
        ClassLoader oldParentClassLoader = this.parentClassLoader;
        this.parentClassLoader = parent;
        // 通知监听器父类加载器已变更
        support.firePropertyChange("parentClassLoader", oldParentClassLoader, this.parentClassLoader);
    }

    /** 获取MBean域名 */
    @Override
    protected String getDomainInternal() {
        String domain = null;
        Container engine = getContainer();

        // 优先使用引擎名称
        if (engine != null) {
            domain = engine.getName();
        }

        // 没有引擎或引擎名称，使用服务名称
        if (domain == null) {
            domain = getName();
        }

        // 没有服务名称，返回null（使用默认域名）
        return domain;
    }

    /** 获取JMX对象名称的关键属性 */
    @Override
    public final String getObjectNameKeyProperties() {
        return "type=Service";
    }
}