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

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.catalina.startup.Catalina;

/**
 * <code>Server</code>接口表示整个Catalina Servlet容器。
 * 其属性代表Servlet容器的整体特征。
 * 一个<code>Server</code>可包含一个或多个<code>Services</code>和顶级命名资源集。
 * <p>
 * 通常，此接口的实现还将实现<code>Lifecycle</code>，
 * 以便在调用<code>start()</code>和<code>stop()</code>方法时，
 * 所有已定义的<code>Services</code>也会被启动或停止。
 * <p>
 * 在此期间，实现必须在<code>port</code>属性指定的端口上打开服务器套接字。
 * 接受连接后，读取第一行并与指定的关闭命令进行比较。
 * 如果命令匹配，则启动服务器关闭。
 *
 * @author Craig R. McClanahan
 */
public interface Server extends Lifecycle {

    // ------------------------------------------------------------- 属性访问方法

    /**
     * 获取全局命名资源
     *
     * @return 全局命名资源实例
     */
    NamingResourcesImpl getGlobalNamingResources();

    /**
     * 设置全局命名资源
     *
     * @param globalNamingResources 新的全局命名资源
     */
    void setGlobalNamingResources(NamingResourcesImpl globalNamingResources);

    /**
     * 获取全局命名上下文
     *
     * @return 全局命名上下文
     */
    javax.naming.Context getGlobalNamingContext();

    /**
     * 获取用于监听关闭命令的端口号
     *
     * @return 端口号
     * @see #getPortOffset() 获取端口偏移量
     * @see #getPortWithOffset() 获取带偏移量的端口号
     */
    int getPort();

    /**
     * 设置用于监听关闭命令的端口号
     *
     * @param port 新的端口号
     * @see #setPortOffset(int) 设置端口偏移量
     */
    void setPort(int port);

    /**
     * 获取关闭命令端口的偏移量
     * 例如，如果port为8005，portOffset为1000，则服务器在9005端口监听
     *
     * @return 端口偏移量
     */
    int getPortOffset();

    /**
     * 设置关闭命令端口的偏移量
     * 例如，如果port为8005，设置portOffset为1000，则连接器在9005端口监听
     *
     * @param portOffset 端口偏移量
     */
    void setPortOffset(int portOffset);

    /**
     * 获取服务器监听关闭命令的实际端口
     * 如果未设置端口偏移量，返回port；如果设置了端口偏移量，返回port + portOffset
     *
     * @return 带偏移量的端口号
     */
    int getPortWithOffset();

    /**
     * 获取监听关闭命令的地址
     *
     * @return 监听地址
     */
    String getAddress();

    /**
     * 设置监听关闭命令的地址
     *
     * @param address 新的监听地址
     */
    void setAddress(String address);

    /**
     * 获取等待的关闭命令字符串
     *
     * @return 关闭命令
     */
    String getShutdown();

    /**
     * 设置等待的关闭命令
     *
     * @param shutdown 新的关闭命令
     */
    void setShutdown(String shutdown);

    /**
     * 获取此组件的父类加载器
     * 如果未设置，返回{@link #getCatalina()}的{@link Catalina#getParentClassLoader()}
     * 如果catalina未设置，返回系统类加载器
     *
     * @return 父类加载器
     */
    ClassLoader getParentClassLoader();

    /**
     * 设置此服务器的父类加载器
     *
     * @param parent 新的父类加载器
     */
    void setParentClassLoader(ClassLoader parent);

    /**
     * 获取外部Catalina启动/关闭组件（如果存在）
     *
     * @return Catalina实例
     */
    Catalina getCatalina();

    /**
     * 设置外部Catalina启动/关闭组件（如果存在）
     *
     * @param catalina 外部Catalina组件
     */
    void setCatalina(Catalina catalina);

    /**
     * 获取配置的基础（实例）目录
     * 注意home和base可能相同（默认情况下相同）
     * 如果未设置，将使用{@link #getCatalinaHome()}返回的值
     *
     * @return Catalina基础目录
     */
    File getCatalinaBase();

    /**
     * 设置配置的基础（实例）目录
     * 注意home和base可能相同（默认情况下相同）
     *
     * @param catalinaBase 配置的基础目录
     */
    void setCatalinaBase(File catalinaBase);

    /**
     * 获取配置的主（二进制）目录
     * 注意home和base可能相同（默认情况下相同）
     *
     * @return Catalina主目录
     */
    File getCatalinaHome();

    /**
     * 设置配置的主（二进制）目录
     * 注意home和base可能相同（默认情况下相同）
     *
     * @param catalinaHome 配置的主目录
     */
    void setCatalinaHome(File catalinaHome);

    /**
     * 获取实用线程数
     *
     * @return 线程数
     */
    int getUtilityThreads();

    /**
     * 设置实用线程数
     *
     * @param utilityThreads 新的线程数
     */
    void setUtilityThreads(int utilityThreads);

    // --------------------------------------------------------- 公共方法

    /**
     * 向已定义的Services集合添加新的Service
     *
     * @param service 要添加的Service
     */
    void addService(Service service);

    /**
     * 等待直到收到正确的关闭命令，然后返回
     */
    void await();

    /**
     * 查找指定的Service
     *
     * @param name 要返回的Service的名称
     * @return 指定的Service，若不存在则返回<code>null</code>
     */
    Service findService(String name);

    /**
     * 获取此Server中定义的Service数组
     *
     * @return Service数组
     */
    Service[] findServices();

    /**
     * 从此Server关联的集合中移除指定的Service
     *
     * @param service 要移除的Service
     */
    void removeService(Service service);

    /**
     * 获取与关联JNDI命名上下文操作所需的令牌
     *
     * @return 命名令牌
     */
    Object getNamingToken();

    /**
     * 获取由Service管理的实用执行器
     *
     * @return 调度执行器服务
     */
    ScheduledExecutorService getUtilityExecutor();
}