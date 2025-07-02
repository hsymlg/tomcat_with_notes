/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.management.ObjectName;

import org.apache.catalina.Contained;
import org.apache.catalina.Container;
import org.apache.catalina.JmxEnabled;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.util.LifecycleBase;
import org.apache.catalina.util.ToStringUtil;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

/**
 * Pipeline接口的标准实现类，负责管理Valve的执行顺序
 *
 * 实现特点：
 * 1. 基于责任链模式实现Valve的顺序调用
 * 2. 支持生命周期管理（Lifecycle接口）
 * 3. 维护Valve链表结构，支持动态添加/删除
 * 4. 与Container紧密关联，每个Container对应一个Pipeline
 *
 * 线程安全说明：
 * - 假设运行时不会在请求处理中修改Valve列表
 * - 若需要并发修改，需自行保证线程安全
 */
public class StandardPipeline extends LifecycleBase implements Pipeline {

    // 日志记录器和字符串资源管理器
    private static final Log log = LogFactory.getLog(StandardPipeline.class);
    private static final StringManager sm = StringManager.getManager(StandardPipeline.class);

    // ----------------------------------------------------------- Constructors

    /**
     * 构造无关联Container的StandardPipeline实例
     */
    public StandardPipeline() {
        this(null);
    }

    /**
     * 构造关联指定Container的StandardPipeline实例
     * @param container 关联的容器对象
     */
    public StandardPipeline(Container container) {
        super();
        setContainer(container);
    }

    // ----------------------------------------------------- Instance Variables

    /**
     * 基础Valve（管道中最后执行的Valve）
     * 通常由容器实现提供核心处理逻辑
     */
    protected Valve basic = null;

    /**
     * 关联的Container对象
     */
    protected Container container = null;

    /**
     * 管道中第一个执行的Valve
     * 形成Valve链表的头节点
     */
    protected Valve first = null;

    // --------------------------------------------------------- Public Methods

    /**
     * 检查管道是否支持异步处理
     * 遍历所有Valve，只要有一个不支持异步则返回false
     */
    @Override
    public boolean isAsyncSupported() {
        Valve valve = (first != null) ? first : basic;
        boolean supported = true;
        while (supported && valve != null) {
            supported = valve.isAsyncSupported();
            valve = valve.getNext();
        }
        return supported;
    }

    /**
     * 查找不支持异步处理的Valve
     * 将不支持异步的Valve类名添加到结果集合中
     */
    @Override
    public void findNonAsyncValves(Set<String> result) {
        Valve valve = (first != null) ? first : basic;
        while (valve != null) {
            if (!valve.isAsyncSupported()) {
                result.add(valve.getClass().getName());
            }
            valve = valve.getNext();
        }
    }

    // ------------------------------------------------------ Contained Methods

    /**
     * 获取关联的Container对象
     */
    @Override
    public Container getContainer() {
        return this.container;
    }

    /**
     * 设置关联的Container对象
     */
    @Override
    public void setContainer(Container container) {
        this.container = container;
    }

    /**
     * 初始化内部资源（空实现）
     */
    @Override
    protected void initInternal() {
        // NOOP
    }

    /**
     * 启动管道及所有Valve
     * 按顺序启动每个Valve的生命周期
     */
    @Override
    protected void startInternal() throws LifecycleException {
        Valve current = first;
        if (current == null) {
            current = basic;
        }
        while (current != null) {
            if (current instanceof Lifecycle) {
                ((Lifecycle) current).start();
            }
            current = current.getNext();
        }
        setState(LifecycleState.STARTING);
    }

    /**
     * 停止管道及所有Valve
     * 按顺序停止每个Valve的生命周期
     */
    @Override
    protected void stopInternal() throws LifecycleException {
        setState(LifecycleState.STOPPING);
        Valve current = first;
        if (current == null) {
            current = basic;
        }
        while (current != null) {
            if (current instanceof Lifecycle) {
                ((Lifecycle) current).stop();
            }
            current = current.getNext();
        }
    }

    /**
     * 销毁管道资源
     * 移除所有Valve并释放资源
     */
    @Override
    protected void destroyInternal() {
        Valve[] valves = getValves();
        for (Valve valve : valves) {
            removeValve(valve);
        }
    }

    /**
     * 返回对象的字符串表示（用于调试）
     */
    @Override
    public String toString() {
        return ToStringUtil.toString(this);
    }

    // ------------------------------------------------------- Pipeline Methods

    /**
     * 获取基础Valve
     */
    @Override
    public Valve getBasic() {
        return this.basic;
    }

    /**
     * 设置基础Valve
     * 实现逻辑：
     * 1. 停止旧Valve（如果有）
     * 2. 关联新Valve到Container
     * 3. 更新Valve链表关系
     * 4. 启动新Valve（如果已启动）
     */
    @Override
    public void setBasic(Valve valve) {
        Valve oldBasic = this.basic;
        if (oldBasic == valve) {
            return;
        }

        // 停止旧Valve
        if (oldBasic != null) {
            if (getState().isAvailable() && (oldBasic instanceof Lifecycle)) {
                try {
                    ((Lifecycle) oldBasic).stop();
                } catch (LifecycleException e) {
                    log.error(sm.getString("standardPipeline.basic.stop"), e);
                }
            }
            if (oldBasic instanceof Contained) {
                ((Contained) oldBasic).setContainer(null);
            }
        }

        // 启动新Valve
        if (valve == null) {
            return;
        }
        if (valve instanceof Contained) {
            ((Contained) valve).setContainer(this.container);
        }
        if (getState().isAvailable() && valve instanceof Lifecycle) {
            try {
                ((Lifecycle) valve).start();
            } catch (LifecycleException e) {
                log.error(sm.getString("standardPipeline.basic.start"), e);
                return;
            }
        }

        // 更新链表关系
        Valve current = first;
        while (current != null) {
            if (current.getNext() == oldBasic) {
                current.setNext(valve);
                break;
            }
            current = current.getNext();
        }

        this.basic = valve;
    }

    /**
     * 向管道添加Valve
     * 实现逻辑：
     * 1. 关联Valve到Container
     * 2. 启动Valve（如果已启动）
     * 3. 添加到Valve链表末尾（basic Valve之前）
     * 4. 触发容器事件
     */
    @Override
    public void addValve(Valve valve) {
        // 关联Container
        if (valve instanceof Contained) {
            ((Contained) valve).setContainer(this.container);
        }

        // 启动Valve
        if (getState().isAvailable()) {
            if (valve instanceof Lifecycle) {
                try {
                    ((Lifecycle) valve).start();
                } catch (LifecycleException e) {
                    log.error(sm.getString("standardPipeline.valve.start"), e);
                }
            }
        }

        // 添加到链表
        if (first == null) {
            first = valve;
            valve.setNext(basic);
        } else {
            Valve current = first;
            while (current != null) {
                if (current.getNext() == basic) {
                    current.setNext(valve);
                    valve.setNext(basic);
                    break;
                }
                current = current.getNext();
            }
        }

        // 触发容器事件
        container.fireContainerEvent(Container.ADD_VALVE_EVENT, valve);
    }

    /**
     * 获取管道中所有Valve
     * 按执行顺序返回Valve数组
     */
    @Override
    public Valve[] getValves() {
        List<Valve> valveList = new ArrayList<>();
        Valve current = first;
        if (current == null) {
            current = basic;
        }
        while (current != null) {
            valveList.add(current);
            current = current.getNext();
        }
        return valveList.toArray(new Valve[0]);
    }

    /**
     * 获取管道中所有Valve的JMX对象名
     * 用于JMX管理和监控
     */
    public ObjectName[] getValveObjectNames() {
        List<ObjectName> valveList = new ArrayList<>();
        Valve current = first;
        if (current == null) {
            current = basic;
        }
        while (current != null) {
            if (current instanceof JmxEnabled) {
                valveList.add(((JmxEnabled) current).getObjectName());
            }
            current = current.getNext();
        }
        return valveList.toArray(new ObjectName[0]);
    }

    /**
     * 从管道移除Valve
     * 实现逻辑：
     * 1. 更新链表关系
     * 2. 解除Valve与Container的关联
     * 3. 停止并销毁Valve
     * 4. 触发容器事件
     */
    @Override
    public void removeValve(Valve valve) {
        // 更新链表
        Valve current;
        if (first == valve) {
            first = first.getNext();
            current = null;
        } else {
            current = first;
        }
        while (current != null) {
            if (current.getNext() == valve) {
                current.setNext(valve.getNext());
                break;
            }
            current = current.getNext();
        }

        if (first == basic) {
            first = null;
        }

        // 解除关联
        if (valve instanceof Contained) {
            ((Contained) valve).setContainer(null);
        }

        // 停止并销毁Valve
        if (valve instanceof Lifecycle) {
            if (getState().isAvailable()) {
                try {
                    ((Lifecycle) valve).stop();
                } catch (LifecycleException e) {
                    log.error(sm.getString("standardPipeline.valve.stop"), e);
                }
            }
            try {
                ((Lifecycle) valve).destroy();
            } catch (LifecycleException e) {
                log.error(sm.getString("standardPipeline.valve.destroy"), e);
            }
        }

        // 触发容器事件
        container.fireContainerEvent(Container.REMOVE_VALVE_EVENT, valve);
    }

    /**
     * 获取管道中第一个执行的Valve
     * 若无普通Valve，返回基础Valve
     */
    @Override
    public Valve getFirst() {
        if (first != null) {
            return first;
        }
        return basic;
    }
}