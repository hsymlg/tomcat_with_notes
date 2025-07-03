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
 * 每个容器（如 Host）有独立的Pipeline-Valve链
 * 请求会按容器层级依次经过各层责任链
 * 浏览器请求 → Engine Pipeline → Host Pipeline → Context Pipeline → Wrapper Pipeline → Servlet   （容器层级传递，非设计模式，属于容器架构层级关系）
 * 最终在StandardWrapperValve的过滤器链（ApplicationFilterChain） 间接调用的Servlet的service方法
 *
 * 责任链的典型应用场景
 * 请求预处理：如AccessLogValve记录访问日志，SecurityValve进行权限校验
 * 会话管理：如SessionIdGeneratorValve生成会话 ID，SessionHandlerValve管理会话状态
 * 容器特性实现：
 * - StandardEngineValve：处理 Engine 容器的请求分发
 * - StandardHostValve：处理虚拟主机（Host）的请求路由
 * - StandardContextValve：处理 Web 应用（Context）的请求映射
 * - StandardWrapperValve：最终调用 Servlet 的service()方法
 *
 * 当请求到达时，责任链按以下顺序处理：
 * 1.请求首先进入first阀门（链头）
 * 2.每个Valve处理完逻辑后，通过getNext().invoke(request, response)传递给下一个节点
 * 3.直到到达basic阀门（链尾），完成最终处理
 * 4.响应按相反顺序回传，每个阀门可补充响应处理逻辑
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
        //调用的是父类的无参构造函数。具体来说，StandardPipeline继承自LifecycleBase类，因此这行代码调用的是LifecycleBase类的构造函数。
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
     * 设置管道的基础Valve（Pipeline的最后一个执行Valve）
     *
     * 实现逻辑说明：
     * 1. 状态检查与旧Valve处理：若存在旧基础Valve，先停止其生命周期并解除与容器的关联
     * 2. 新Valve初始化：将新Valve关联到容器并启动（若管道已启动）
     * 3. 链表结构更新：修改Valve链表中指向旧Valve的引用，指向新Valve
     * 4. 最终状态设置：将新Valve设为基础Valve
     *
     * @param valve 要设置的新基础Valve，null表示移除基础Valve
     */
    @Override
    public void setBasic(Valve valve) {
        // 获取当前基础Valve
        Valve oldBasic = this.basic;
        // 若新旧Valve相同，直接返回（避免无效操作）
        if (oldBasic == valve) {
            return;
        }

        // ------------------------- 处理旧基础Valve -------------------------
        if (oldBasic != null) {
            // 若管道状态可用且旧Valve支持生命周期管理，停止其生命周期
            if (getState().isAvailable() && (oldBasic instanceof Lifecycle)) {
                try {
                    ((Lifecycle) oldBasic).stop();
                } catch (LifecycleException e) {
                    // 记录停止旧Valve时的异常
                    log.error(sm.getString("standardPipeline.basic.stop"), e);
                }
            }
            // 若旧Valve实现了Contained接口，解除其与容器的关联
            if (oldBasic instanceof Contained) {
                ((Contained) oldBasic).setContainer(null);
            }
        }

        // ------------------------- 处理新基础Valve -------------------------
        // 若新Valve为null，直接返回（不进行后续操作）
        if (valve == null) {
            return;
        }
        // 若新Valve实现了Contained接口，将其关联到当前容器
        if (valve instanceof Contained) {
            ((Contained) valve).setContainer(this.container);
        }
        // 若管道状态可用且新Valve支持生命周期管理，启动其生命周期
        if (getState().isAvailable() && valve instanceof Lifecycle) {
            try {
                ((Lifecycle) valve).start();
            } catch (LifecycleException e) {
                // 记录启动新Valve时的异常并终止方法
                log.error(sm.getString("standardPipeline.basic.start"), e);
                return;
            }
        }

        // ------------------------- 更新Valve链表结构 -------------------------
        // 从第一个Valve开始遍历链表
        Valve current = first;
        while (current != null) {
            // 找到链表中指向旧基础Valve的节点
            if (current.getNext() == oldBasic) {
                // 修改该节点的next引用，指向新基础Valve
                current.setNext(valve);
                break;
            }
            // 继续遍历下一个Valve
            current = current.getNext();
        }

        // 将新Valve设置为基础Valve
        this.basic = valve;
    }

    /**
     * 向管道添加Valve（添加到所有已有普通Valve之后，基础Valve之前）
     *
     * 实现逻辑说明：
     * 1. 容器关联：将新Valve与当前容器建立关联
     * 2. 生命周期启动：若管道已启动，启动新Valve的生命周期
     * 3. 链表插入：根据链表当前状态，将新Valve插入到合适位置
     * 4. 事件通知：触发容器的ADD_VALVE_EVENT事件
     *
     * @param valve 要添加的Valve实例
     * @throws IllegalArgumentException 若Valve拒绝与容器关联
     * @throws IllegalStateException 若Valve已关联到其他容器
     */
    @Override
    public void addValve(Valve valve) {
        // ------------------------- 关联Valve到容器 -------------------------
        // 若Valve实现了Contained接口，设置其关联的容器为当前容器
        if (valve instanceof Contained) {
            ((Contained) valve).setContainer(this.container);
        }

        // ------------------------- 启动Valve生命周期 -------------------------
        // 若管道状态可用（已启动或正在启动），启动Valve的生命周期
        if (getState().isAvailable()) {
            if (valve instanceof Lifecycle) {
                try {
                    ((Lifecycle) valve).start();
                } catch (LifecycleException e) {
                    // 记录启动Valve时的异常
                    log.error(sm.getString("standardPipeline.valve.start"), e);
                }
            }
        }

        // ------------------------- 插入Valve到链表 -------------------------
        // 情况1：管道中尚无普通Valve（first为null）
        if (first == null) {
            // 新Valve成为第一个普通Valve
            first = valve;
            // 新Valve的next指向基础Valve
            valve.setNext(basic);
        }
        // 情况2：管道中已有普通Valve，需找到链表尾部
        else {
            Valve current = first;
            // 遍历链表，找到最后一个指向基础Valve的节点
            while (current != null) {
                if (current.getNext() == basic) {
                    // 在该节点后插入新Valve
                    current.setNext(valve);
                    // 新Valve的next指向基础Valve
                    valve.setNext(basic);
                    break;
                }
                current = current.getNext();
            }
        }

        // ------------------------- 触发容器事件 -------------------------
        // 通知容器已添加Valve（用于事件监听和JMX通知）
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