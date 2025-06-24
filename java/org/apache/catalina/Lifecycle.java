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
package org.apache.catalina;

/**
 * 组件生命周期方法的通用接口。Catalina组件可实现此接口（以及所支持功能的相应接口），
 * 以提供启动和停止组件的一致机制。支持{@link Lifecycle}的组件的有效状态转换如下：
 * （状态转换图见接口注释原文）
 *
 * 任何状态都可以转换为FAILED。
 *
 * 在组件处于STARTING_PREP、STARTING或STARTED状态时调用start()无效果。
 *
 * 在组件处于NEW状态时调用start()将导致在进入start()方法后立即调用init()。
 *
 * 在组件处于STOPPING_PREP、STOPPING或STOPPED状态时调用stop()无效果。
 *
 * 在组件处于NEW状态时调用stop()会将组件转换为STOPPED状态。这通常发生在组件启动失败且未启动所有子组件的情况下。
 * 当组件停止时，它将尝试停止所有子组件 - 即使是未启动的子组件。
 *
 * 尝试任何其他转换将抛出{@link LifecycleException}。
 *
 * 状态更改期间触发的{@link LifecycleEvent}在触发更改的方法中定义。
 * 如果尝试的转换无效，则不会触发任何{@link LifecycleEvent}。
 *
 * @author Craig R. McClanahan
 */
public interface Lifecycle {

    // ----------------------------------------------------- 状态事件常量

    /** 组件初始化前事件的类型 */
    String BEFORE_INIT_EVENT = "before_init";

    /** 组件初始化后事件的类型 */
    String AFTER_INIT_EVENT = "after_init";

    /** 组件启动事件的类型 */
    String START_EVENT = "start";

    /** 组件启动前事件的类型 */
    String BEFORE_START_EVENT = "before_start";

    /** 组件启动后事件的类型 */
    String AFTER_START_EVENT = "after_start";

    /** 组件停止事件的类型 */
    String STOP_EVENT = "stop";

    /** 组件停止前事件的类型 */
    String BEFORE_STOP_EVENT = "before_stop";

    /** 组件停止后事件的类型 */
    String AFTER_STOP_EVENT = "after_stop";

    /** 组件销毁后事件的类型 */
    String AFTER_DESTROY_EVENT = "after_destroy";

    /** 组件销毁前事件的类型 */
    String BEFORE_DESTROY_EVENT = "before_destroy";

    /** 周期性事件的类型 */
    String PERIODIC_EVENT = "periodic";

    /** 配置开始事件的类型 */
    String CONFIGURE_START_EVENT = "configure_start";

    /** 配置停止事件的类型 */
    String CONFIGURE_STOP_EVENT = "configure_stop";

    // --------------------------------------------------------- 公共方法

    /**
     * 向此组件添加生命周期事件监听器
     *
     * @param listener 要添加的监听器
     */
    void addLifecycleListener(LifecycleListener listener);

    /**
     * 获取与此生命周期关联的生命周期监听器
     *
     * @return 包含与此生命周期关联的生命周期监听器的数组。
     *         如果此组件未注册任何监听器，则返回长度为零的数组。
     */
    LifecycleListener[] findLifecycleListeners();

    /**
     * 从此组件移除生命周期事件监听器
     *
     * @param listener 要移除的监听器
     */
    void removeLifecycleListener(LifecycleListener listener);

    /**
     * 准备启动组件。此方法应执行对象创建后所需的任何初始化。
     * 将按以下顺序触发以下{@link LifecycleEvent}：
     * <ol>
     * <li>INIT_EVENT：组件初始化成功完成时。</li>
     * </ol>
     *
     * @exception LifecycleException 如果此组件检测到阻止其使用的致命错误
     */
    void init() throws LifecycleException;

    /**
     * 准备开始使用此组件的公共方法（属性getter/setter和生命周期方法除外）。
     * 应在使用此组件的任何公共方法（属性getter/setter和生命周期方法除外）之前调用此方法。
     * 将按以下顺序触发以下{@link LifecycleEvent}：
     * <ol>
     * <li>BEFORE_START_EVENT：方法开始时。此时状态转换为{@link LifecycleState#STARTING_PREP}。</li>
     * <li>START_EVENT：方法期间，一旦可以安全地为任何子组件调用start()时。
     * 此时状态转换为{@link LifecycleState#STARTING}，并且可以使用公共方法（属性getter/setter和生命周期方法除外）。</li>
     * <li>AFTER_START_EVENT：方法结束时，在返回之前。此时状态转换为{@link LifecycleState#STARTED}。</li>
     * </ol>
     *
     * @exception LifecycleException 如果此组件检测到阻止其使用的致命错误
     */
    void start() throws LifecycleException;

    /**
     * 优雅终止此组件的公共方法（属性getter/setter和生命周期方法除外）的使用。
     * 一旦触发STOP_EVENT，就不应再使用公共方法（属性getter/setter和生命周期方法除外）。
     * 将按以下顺序触发以下{@link LifecycleEvent}：
     * <ol>
     * <li>BEFORE_STOP_EVENT：方法开始时。此时状态转换为{@link LifecycleState#STOPPING_PREP}。</li>
     * <li>STOP_EVENT：方法期间，一旦可以安全地为任何子组件调用stop()时。
     * 此时状态转换为{@link LifecycleState#STOPPING}，并且公共方法（属性getter/setter和生命周期方法除外）不再使用。</li>
     * <li>AFTER_STOP_EVENT：方法结束时，在返回之前。此时状态转换为{@link LifecycleState#STOPPED}。</li>
     * </ol>
     * 注意：如果从{@link LifecycleState#FAILED}转换，则会触发上述三个事件，
     * 但组件将直接从{@link LifecycleState#FAILED}转换为{@link LifecycleState#STOPPING}，
     * 绕过{@link LifecycleState#STOPPING_PREP}
     *
     * @exception LifecycleException 如果此组件检测到需要报告的致命错误
     */
    void stop() throws LifecycleException;

    /**
     * 准备丢弃对象。将按以下顺序触发以下{@link LifecycleEvent}：
     * <ol>
     * <li>DESTROY_EVENT：组件销毁成功完成时。</li>
     * </ol>
     *
     * @exception LifecycleException 如果此组件检测到阻止其使用的致命错误
     */
    void destroy() throws LifecycleException;

    /**
     * 获取源组件的当前状态
     *
     * @return 源组件的当前状态
     */
    LifecycleState getState();

    /**
     * 获取组件当前状态的文本表示。对JMX有用。
     * 此字符串的格式可能在不同版本之间有所不同，不应依赖它来确定组件状态。
     * 要确定组件状态，请使用{@link #getState()}。
     *
     * @return 组件当前状态的名称
     */
    String getStateName();

    /**
     * 标记接口，用于指示实例应仅使用一次。
     * 在支持此接口的实例上调用{@link #stop()}将在{@link #stop()}完成后自动调用{@link #destroy()}。
     */
    interface SingleUse {
    }
}