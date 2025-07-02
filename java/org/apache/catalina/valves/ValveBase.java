/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina.valves;

import org.apache.catalina.Contained;
import org.apache.catalina.Container;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.catalina.util.ToStringUtil;
import org.apache.juli.logging.Log;
import org.apache.tomcat.util.res.StringManager;

/**
 * Valve接口的基础实现类，提供通用功能和生命周期管理
 *
 * 设计目标：
 * 1. 简化Valve实现，减少重复代码
 * 2. 提供生命周期管理支持（启动/停止/销毁）
 * 3. 集成JMX管理功能
 * 4. 维护Valve间的关联关系
 *
 * 实现要求：
 * - 子类必须实现invoke()方法
 * - 可选实现Lifecycle接口（已部分实现）
 */
public abstract class ValveBase extends LifecycleMBeanBase implements Contained, Valve {

    // 字符串资源管理器，用于加载国际化错误信息
    protected static final StringManager sm = StringManager.getManager(ValveBase.class);

    // ------------------------------------------------------ Constructor

    /**
     * 构造默认ValveBase实例（不支持异步处理）
     */
    public ValveBase() {
        this(false);
    }

    /**
     * 构造ValveBase实例并指定是否支持异步处理
     * @param asyncSupported 是否支持Servlet 3+异步请求
     */
    public ValveBase(boolean asyncSupported) {
        this.asyncSupported = asyncSupported;
    }

    // ------------------------------------------------------ Instance Variables

    /**
     * 异步处理支持标志
     * 默认为false，可通过构造函数或setter修改
     */
    protected boolean asyncSupported;

    /**
     * 关联的Container对象
     * Valve属于某个Container的Pipeline
     */
    protected Container container = null;

    /**
     * Container的日志记录器
     * 方便Valve使用容器的日志配置
     */
    protected Log containerLog = null;

    /**
     * 责任链中的下一个Valve
     * 形成Valve链表结构
     */
    protected Valve next = null;

    // -------------------------------------------------------------- Properties

    /**
     * 获取关联的Container对象
     */
    @Override
    public Container getContainer() {
        return container;
    }

    /**
     * 设置关联的Container对象
     * 实现Contained接口要求
     */
    @Override
    public void setContainer(Container container) {
        this.container = container;
    }

    /**
     * 检查是否支持异步处理
     * 实现Valve接口要求
     */
    @Override
    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    /**
     * 设置异步处理支持标志
     */
    public void setAsyncSupported(boolean asyncSupported) {
        this.asyncSupported = asyncSupported;
    }

    /**
     * 获取责任链中的下一个Valve
     * 实现Valve接口要求
     */
    @Override
    public Valve getNext() {
        return next;
    }

    /**
     * 设置责任链中的下一个Valve
     * 实现Valve接口要求
     */
    @Override
    public void setNext(Valve valve) {
        this.next = valve;
    }

    // ---------------------------------------------------------- Public Methods

    /**
     * 执行周期性后台任务（默认空实现）
     * 子类可覆盖此方法实现自定义后台逻辑
     */
    @Override
    public void backgroundProcess() {
        // 默认不执行任何操作
    }

    /**
     * 初始化内部资源
     * 实现Lifecycle接口要求
     */
    @Override
    protected void initInternal() throws LifecycleException {
        super.initInternal();
        // 获取容器的日志记录器
        containerLog = getContainer().getLogger();
    }

    /**
     * 启动Valve（默认设置状态为STARTING）
     * 子类可覆盖此方法添加启动逻辑
     */
    @Override
    protected void startInternal() throws LifecycleException {
        setState(LifecycleState.STARTING);
    }

    /**
     * 停止Valve（默认设置状态为STOPPING）
     * 子类可覆盖此方法添加停止逻辑
     */
    @Override
    protected void stopInternal() throws LifecycleException {
        setState(LifecycleState.STOPPING);
    }

    /**
     * 返回对象的字符串表示（用于调试）
     */
    @Override
    public String toString() {
        return ToStringUtil.toString(this);
    }

    // -------------------- JMX and Registration --------------------

    /**
     * 获取JMX ObjectName的键属性部分
     * 用于JMX注册和管理
     */
    @Override
    public String getObjectNameKeyProperties() {
        StringBuilder name = new StringBuilder("type=Valve");

        Container container = getContainer();
        name.append(container.getMBeanKeyProperties());

        int seq = 0;

        // 获取容器的Pipeline（单元测试中可能为null）
        Pipeline p = container.getPipeline();
        if (p != null) {
            for (Valve valve : p.getValves()) {
                // 跳过null Valve
                if (valve == null) {
                    continue;
                }
                // 找到当前Valve时停止循环
                if (valve == this) {
                    break;
                }
                // 统计同类型Valve的数量，用于生成唯一名称
                if (valve.getClass() == this.getClass()) {
                    seq++;
                }
            }
        }

        // 添加序号以区分同类型Valve
        if (seq > 0) {
            name.append(",seq=");
            name.append(seq);
        }

        // 添加类名作为名称
        String className = this.getClass().getName();
        int period = className.lastIndexOf('.');
        if (period >= 0) {
            className = className.substring(period + 1);
        }
        name.append(",name=");
        name.append(className);

        return name.toString();
    }

    /**
     * 获取JMX域（继承自容器的域）
     */
    @Override
    protected String getDomainInternal() {
        Container c = getContainer();
        return (c == null) ? null : c.getDomain();
    }
}