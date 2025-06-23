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
package org.apache.catalina.startup; // 包声明，属于Catalina启动模块

import java.io.File; // 文件操作类
import java.io.FileInputStream; // 文件输入流
import java.io.IOException; // IO异常
import java.io.InputStream; // 输入流接口
import java.net.URI; // URI处理类
import java.util.Enumeration; // 枚举接口
import java.util.Properties; // 属性配置类

import org.apache.juli.logging.Log; // 日志接口
import org.apache.juli.logging.LogFactory; // 日志工厂

/**
 * 读取Catalina启动配置的工具类。
 * 负责加载和管理Catalina的配置属性，支持从多种来源加载配置。
 *
 * @author Remy Maucherat
 */
public class CatalinaProperties {

    private static final Log log = LogFactory.getLog(CatalinaProperties.class); // 类级别的日志实例

    private static Properties properties = null; // 静态属性对象，存储加载的配置


    static { // 静态初始化块，类加载时执行
        loadProperties(); // 加载配置属性
    }


    /**
     * 根据属性名获取配置值
     *
     * @param name 属性名称
     * @return 属性值，如果不存在则返回null
     */
    public static String getProperty(String name) {
        return properties.getProperty(name); // 从静态属性对象获取值
    }


    /**
     * 加载Catalina配置属性
     * 按以下顺序尝试加载配置：
     * 1. 通过catalina.config系统属性指定的URL
     * 2. $CATALINA_BASE/conf/catalina.properties文件
     * 3. 类路径下的默认配置文件
     */
    private static void loadProperties() {

        InputStream is = null; // 输入流，用于读取配置文件
        String fileName = "catalina.properties"; // 默认配置文件名

        try {
            // 首先尝试从catalina.config系统属性获取配置URL
            String configUrl = System.getProperty("catalina.config");
            if (configUrl != null) {
                if (configUrl.indexOf('/') == -1) {
                    // 没有斜杠，说明是文件名而不是URL
                    fileName = configUrl; // 使用指定的文件名
                } else {
                    // 解析为URL并打开输入流
                    is = new URI(configUrl).toURL().openStream();
                }
            }
        } catch (Throwable t) {
            handleThrowable(t); // 处理严重异常
        }

        if (is == null) { // 如果未通过URL加载成功
            try {
                // 尝试从$CATALINA_BASE/conf目录加载
                File home = new File(Bootstrap.getCatalinaBase()); // 获取Catalina基础目录
                File conf = new File(home, "conf"); // 获取conf子目录
                File propsFile = new File(conf, fileName); // 构建配置文件路径
                is = new FileInputStream(propsFile); // 打开文件输入流
            } catch (Throwable t) {
                handleThrowable(t); // 处理严重异常
            }
        }

        if (is == null) { // 如果仍未加载成功
            try {
                // 尝试从类路径加载默认配置
                is = CatalinaProperties.class.getResourceAsStream("/org/apache/catalina/startup/catalina.properties");
            } catch (Throwable t) {
                handleThrowable(t); // 处理严重异常
            }
        }

        if (is != null) { // 如果成功获取输入流
            try {
                properties = new Properties(); // 初始化属性对象
                properties.load(is); // 从输入流加载属性
            } catch (Throwable t) {
                handleThrowable(t); // 处理严重异常
                log.warn(t); // 记录警告日志
            } finally {
                try {
                    is.close(); // 关闭输入流
                } catch (IOException ioe) {
                    log.warn("Could not close catalina properties file", ioe); // 记录关闭失败日志
                }
            }
        }

        if ((is == null)) { // 如果所有方法都未能加载配置
            // 记录警告但继续执行，使用默认配置
            log.warn("Failed to load catalina properties file");
            // 这没关系 - 我们有合理的默认值
            properties = new Properties(); // 初始化空的属性对象
        }

        // 将所有加载的属性注册为系统属性
        Enumeration<?> enumeration = properties.propertyNames();
        while (enumeration.hasMoreElements()) {
            String name = (String) enumeration.nextElement();
            String value = properties.getProperty(name);
            if (value != null) {
                System.setProperty(name, value); // 设置系统属性
            }
        }
    }


    // 从ExceptionUtils复制而来，因为该类在启动时不可见
    private static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) { // 如果是线程死亡异常
            throw (ThreadDeath) t; // 重新抛出
        }
        if (t instanceof VirtualMachineError) { // 如果是虚拟机错误
            throw (VirtualMachineError) t; // 重新抛出
        }
        // 其他所有Throwable实例将被静默吞咽
    }
}