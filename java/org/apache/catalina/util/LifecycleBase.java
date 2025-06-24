/*
 * 版权归Apache软件基金会(ASF)所有，根据一个或多个贡献者许可协议。
 * 请参阅随附的NOTICE文件，了解有关版权所有权的额外信息。
 * ASF根据Apache许可证2.0版("许可证")向您许可本文件；
 * 除非符合许可证，否则您不得使用本文件。
 * 您可以在以下网址获取许可证副本：
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 除非适用法律要求或书面同意，否则根据许可证分发的软件
 * 按"原样"分发，不附带任何形式的明示或暗示保证。
 * 请参阅许可证，了解管理权限和限制的具体语言。
 */
package org.apache.catalina.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

/**
 * Lifecycle接口的基础实现，实现了Lifecycle#start()和Lifecycle#stop()的状态转换规则
 * 提供了组件生命周期管理的骨架实现，子类只需实现具体的生命周期方法
 */
public abstract class LifecycleBase implements Lifecycle {

    // 日志记录器
    private static final Log log = LogFactory.getLog(LifecycleBase.class);

    // 字符串管理器，用于国际化消息
    private static final StringManager sm = StringManager.getManager(LifecycleBase.class);

    /**
     * 注册的生命周期监听器列表，使用CopyOnWriteArrayList保证并发安全
     * 用于事件通知时遍历所有监听器
     */
    private final List<LifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();

    /**
     * 组件的当前状态，使用volatile保证可见性
     * 状态转换由synchronized方法控制
     */
    private volatile LifecycleState state = LifecycleState.NEW;

    /**
     * 子类方法抛出异常时是否重新抛出
     * true表示重新抛出，false表示记录日志
     */
    private boolean throwOnFailure = true;

    /**
     * 获取子类方法抛出异常时的处理策略
     * @return true表示重新抛出异常，false表示记录日志
     */
    public boolean getThrowOnFailure() {
        return throwOnFailure;
    }

    /**
     * 设置子类方法抛出异常时的处理策略
     * @param throwOnFailure true表示重新抛出异常，false表示记录日志
     */
    public void setThrowOnFailure(boolean throwOnFailure) {
        this.throwOnFailure = throwOnFailure;
    }

    /**
     * 添加生命周期监听器
     * @param listener 要添加的监听器
     */
    @Override
    public void addLifecycleListener(LifecycleListener listener) {
        lifecycleListeners.add(listener);
    }

    /**
     * 获取所有注册的生命周期监听器
     * @return 生命周期监听器数组
     */
    @Override
    public LifecycleListener[] findLifecycleListeners() {
        return lifecycleListeners.toArray(new LifecycleListener[0]);
    }

    /**
     * 移除生命周期监听器
     * @param listener 要移除的监听器
     */
    @Override
    public void removeLifecycleListener(LifecycleListener listener) {
        lifecycleListeners.remove(listener);
    }

    /**
     * 触发生命周期事件，通知所有监听器
     * @param type 事件类型
     * @param data 事件相关数据
     */
    protected void fireLifecycleEvent(String type, Object data) {
        LifecycleEvent event = new LifecycleEvent(this, type, data);
        for (LifecycleListener listener : lifecycleListeners) {
            listener.lifecycleEvent(event);
        }
    }

    /**
     * 初始化组件，线程安全的同步方法
     * @throws LifecycleException 初始化失败时抛出
     */
    @Override
    public final synchronized void init() throws LifecycleException {
        // 检查当前状态是否允许初始化（必须为NEW）
        if (!state.equals(LifecycleState.NEW)) {
            invalidTransition(BEFORE_INIT_EVENT);
        }

        try {
            // 转换状态为INITIALIZING并触发事件
            setStateInternal(LifecycleState.INITIALIZING, null, false);
            // 调用子类实现的初始化逻辑
            initInternal();
            // 转换状态为INITIALIZED并触发事件
            setStateInternal(LifecycleState.INITIALIZED, null, false);
        } catch (Throwable t) {
            // 处理子类抛出的异常
            handleSubClassException(t, "lifecycleBase.initFail", toString());
        }
    }

    /**
     * 子类必须实现的初始化逻辑
     * @throws LifecycleException 初始化失败时抛出
     */
    protected abstract void initInternal() throws LifecycleException;

    /**
     * 启动组件，线程安全的同步方法
     * @throws LifecycleException 启动失败时抛出
     */
    @Override
    public final synchronized void start() throws LifecycleException {
        // 检查是否已处于启动相关状态，避免重复启动
        if (LifecycleState.STARTING_PREP.equals(state) || LifecycleState.STARTING.equals(state) ||
            LifecycleState.STARTED.equals(state)) {
            // 记录日志并返回
            if (log.isDebugEnabled()) {
                Exception e = new LifecycleException();
                log.debug(sm.getString("lifecycleBase.alreadyStarted", toString()), e);
            } else if (log.isInfoEnabled()) {
                log.info(sm.getString("lifecycleBase.alreadyStarted", toString()));
            }
            return;
        }

        // 处理特殊状态转换
        if (state.equals(LifecycleState.NEW)) {
            init(); // 新状态时先初始化
        } else if (state.equals(LifecycleState.FAILED)) {
            stop(); // 失败状态时先停止
        } else if (!state.equals(LifecycleState.INITIALIZED) && !state.equals(LifecycleState.STOPPED)) {
            invalidTransition(BEFORE_START_EVENT); // 无效状态转换
        }

        try {
            // 转换状态为STARTING_PREP并触发事件
            setStateInternal(LifecycleState.STARTING_PREP, null, false);
            // 调用子类实现的启动逻辑
            startInternal();

            // 检查启动后的状态
            if (state.equals(LifecycleState.FAILED)) {
                // 启动失败时调用stop完成清理
                stop();
            } else if (!state.equals(LifecycleState.STARTING)) {
                // 状态异常时抛出错误
                invalidTransition(AFTER_START_EVENT);
            } else {
                // 转换状态为STARTED并触发事件
                setStateInternal(LifecycleState.STARTED, null, false);
            }
        } catch (Throwable t) {
            // 处理启动过程中的异常
            handleSubClassException(t, "lifecycleBase.startFail", toString());
        }
    }

    /**
     * 子类必须实现的启动逻辑
     * 必须将状态转换为STARTING并触发START_EVENT事件
     * @throws LifecycleException 启动失败时抛出
     */
    protected abstract void startInternal() throws LifecycleException;

    /**
     * 停止组件，线程安全的同步方法
     * @throws LifecycleException 停止失败时抛出
     */
    @Override
    public final synchronized void stop() throws LifecycleException {
        // 检查是否已处于停止相关状态，避免重复停止
        if (LifecycleState.STOPPING_PREP.equals(state) || LifecycleState.STOPPING.equals(state) ||
            LifecycleState.STOPPED.equals(state)) {
            // 记录日志并返回
            if (log.isDebugEnabled()) {
                Exception e = new LifecycleException();
                log.debug(sm.getString("lifecycleBase.alreadyStopped", toString()), e);
            } else if (log.isInfoEnabled()) {
                log.info(sm.getString("lifecycleBase.alreadyStopped", toString()));
            }
            return;
        }

        // 处理特殊状态转换
        if (state.equals(LifecycleState.INITIALIZED)) {
            return; // 已初始化但未启动时无需停止
        }

        if (state.equals(LifecycleState.NEW)) {
            state = LifecycleState.STOPPED; // 新状态时直接设为停止
            return;
        }

        if (!state.equals(LifecycleState.STARTED) && !state.equals(LifecycleState.FAILED)) {
            invalidTransition(BEFORE_STOP_EVENT); // 无效状态转换
        }

        try {
            // 处理失败状态的特殊转换
            if (state.equals(LifecycleState.FAILED)) {
                fireLifecycleEvent(BEFORE_STOP_EVENT, null); // 触发停止前事件
            } else {
                // 转换状态为STOPPING_PREP并触发事件
                setStateInternal(LifecycleState.STOPPING_PREP, null, false);
            }

            // 调用子类实现的停止逻辑
            stopInternal();

            // 检查停止后的状态
            if (!state.equals(LifecycleState.STOPPING) && !state.equals(LifecycleState.FAILED)) {
                invalidTransition(AFTER_STOP_EVENT); // 状态异常时抛出错误
            }

            // 转换状态为STOPPED并触发事件
            setStateInternal(LifecycleState.STOPPED, null, false);
        } catch (Throwable t) {
            // 处理停止过程中的异常
            handleSubClassException(t, "lifecycleBase.stopFail", toString());
        } finally {
            // 处理单次使用组件，停止后直接销毁
            if (this instanceof Lifecycle.SingleUse) {
                setStateInternal(LifecycleState.STOPPED, null, false);
                destroy();
            }
        }
    }

    /**
     * 子类必须实现的停止逻辑
     * 必须将状态转换为STOPPING并触发STOP_EVENT事件
     * @throws LifecycleException 停止失败时抛出
     */
    protected abstract void stopInternal() throws LifecycleException;

    /**
     * 销毁组件，线程安全的同步方法
     * @throws LifecycleException 销毁失败时抛出
     */
    @Override
    public final synchronized void destroy() throws LifecycleException {
        // 处理失败状态，先尝试停止
        if (LifecycleState.FAILED.equals(state)) {
            try {
                stop();
            } catch (LifecycleException e) {
                log.error(sm.getString("lifecycleBase.destroyStopFail", toString()), e);
            }
        }

        // 检查是否已处于销毁相关状态，避免重复销毁
        if (LifecycleState.DESTROYING.equals(state) || LifecycleState.DESTROYED.equals(state)) {
            if (log.isDebugEnabled()) {
                Exception e = new LifecycleException();
                log.debug(sm.getString("lifecycleBase.alreadyDestroyed", toString()), e);
            } else if (log.isInfoEnabled() && !(this instanceof Lifecycle.SingleUse)) {
                log.info(sm.getString("lifecycleBase.alreadyDestroyed", toString()));
            }
            return;
        }

        // 检查当前状态是否允许销毁
        if (!state.equals(LifecycleState.STOPPED) && !state.equals(LifecycleState.FAILED) &&
            !state.equals(LifecycleState.NEW) && !state.equals(LifecycleState.INITIALIZED)) {
            invalidTransition(BEFORE_DESTROY_EVENT);
        }

        try {
            // 转换状态为DESTROYING并触发事件
            setStateInternal(LifecycleState.DESTROYING, null, false);
            // 调用子类实现的销毁逻辑
            destroyInternal();
            // 转换状态为DESTROYED并触发事件
            setStateInternal(LifecycleState.DESTROYED, null, false);
        } catch (Throwable t) {
            // 处理销毁过程中的异常
            handleSubClassException(t, "lifecycleBase.destroyFail", toString());
        }
    }

    /**
     * 子类必须实现的销毁逻辑
     * @throws LifecycleException 销毁失败时抛出
     */
    protected abstract void destroyInternal() throws LifecycleException;

    /**
     * 获取组件当前状态
     * @return 生命周期状态
     */
    @Override
    public LifecycleState getState() {
        return state;
    }

    /**
     * 获取组件当前状态的字符串表示
     * @return 状态名称
     */
    @Override
    public String getStateName() {
        return getState().toString();
    }

    /**
     * 设置组件状态，触发相应的生命周期事件
     * @param state 新状态
     * @throws LifecycleException 无效状态转换时抛出
     */
    protected synchronized void setState(LifecycleState state) throws LifecycleException {
        setStateInternal(state, null, true);
    }

    /**
     * 设置组件状态并附带事件数据，触发相应的生命周期事件
     * @param state 新状态
     * @param data 事件数据
     * @throws LifecycleException 无效状态转换时抛出
     */
    protected synchronized void setState(LifecycleState state, Object data) throws LifecycleException {
        setStateInternal(state, data, true);
    }

    /**
     * 内部状态转换方法，包含状态验证和事件触发
     * @param state 新状态
     * @param data 事件数据
     * @param check 是否进行状态验证
     * @throws LifecycleException 无效状态转换时抛出
     */
    private synchronized void setStateInternal(LifecycleState state, Object data, boolean check)
        throws LifecycleException {

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("lifecycleBase.setState", this, state));
        }

        if (check) {
            // 状态不能为null
            if (state == null) {
                invalidTransition("null");
                return;
            }

            // 验证状态转换合法性
            if (!(state == LifecycleState.FAILED ||
                (this.state == LifecycleState.STARTING_PREP && state == LifecycleState.STARTING) ||
                (this.state == LifecycleState.STOPPING_PREP && state == LifecycleState.STOPPING) ||
                (this.state == LifecycleState.FAILED && state == LifecycleState.STOPPING))) {
                invalidTransition(state.name());
            }
        }

        // 更新状态并触发事件
        this.state = state;
        String lifecycleEvent = state.getLifecycleEvent();
        if (lifecycleEvent != null) {
            fireLifecycleEvent(lifecycleEvent, data);
        }
    }

    /**
     * 抛出无效状态转换异常
     * @param type 目标状态类型
     * @throws LifecycleException 包含错误信息的异常
     */
    private void invalidTransition(String type) throws LifecycleException {
        String msg = sm.getString("lifecycleBase.invalidTransition", type, toString(), state);
        throw new LifecycleException(msg);
    }

    /**
     * 处理子类方法抛出的异常
     * @param t 原始异常
     * @param key 错误信息键
     * @param args 错误信息参数
     * @throws LifecycleException 包装后的异常
     */
    private void handleSubClassException(Throwable t, String key, Object... args) throws LifecycleException {
        // 设置状态为FAILED
        setStateInternal(LifecycleState.FAILED, null, false);
        ExceptionUtils.handleThrowable(t);
        String msg = sm.getString(key, args);

        // 根据throwOnFailure决定是否重新抛出异常
        if (getThrowOnFailure()) {
            if (!(t instanceof LifecycleException)) {
                t = new LifecycleException(msg, t);
            }
            throw (LifecycleException) t;
        } else {
            log.error(msg, t);
        }
    }
}