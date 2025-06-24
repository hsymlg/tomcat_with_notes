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

import java.util.EventObject;

/**
 * 生命周期事件类，用于通知实现了Lifecycle接口的组件的监听器发生了重要变化
 * 当Lifecycle组件的状态发生改变时，会创建此事件并通知相关监听器
 *
 * @author Craig R. McClanahan
 */
public final class LifecycleEvent extends EventObject {

    // 序列化版本号，确保不同版本间的序列化兼容性
    private static final long serialVersionUID = 1L;

    /**
     * 构造一个新的LifecycleEvent实例
     *
     * @param lifecycle 发生此事件的Lifecycle组件
     * @param type      事件类型（如Lifecycle接口定义的BEFORE_INIT_EVENT等）
     * @param data      事件相关数据（可选，可传递null）
     */
    public LifecycleEvent(Lifecycle lifecycle, String type, Object data) {
        super(lifecycle);  // 调用父类EventObject构造方法，传入事件源
        this.type = type;  // 初始化事件类型
        this.data = data;  // 初始化事件数据
    }

    /**
     * 事件相关的数据对象，在构造时指定
     * 可用于传递事件相关的额外信息
     */
    private final Object data;

    /**
     * 事件的类型，对应Lifecycle接口中定义的事件常量
     * 如BEFORE_INIT_EVENT、START_EVENT等
     */
    private final String type;

    /**
     * 获取事件相关的数据对象
     *
     * @return 事件数据，构造时传入的data参数
     */
    public Object getData() {
        return data;
    }

    /**
     * 获取触发此事件的Lifecycle组件
     *
     * @return 发生事件的Lifecycle实例
     */
    public Lifecycle getLifecycle() {
        return (Lifecycle) getSource();  // 从父类获取事件源并转换为Lifecycle类型
    }

    /**
     * 获取事件的类型
     *
     * @return 事件类型字符串，如"start"、"stop"等
     */
    public String getType() {
        return this.type;
    }
}