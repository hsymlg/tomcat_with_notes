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
package org.apache.catalina.startup; // 包声明，指定该类属于Catalina的启动包

import java.io.File; // 导入文件操作类
import java.io.IOException; // 导入IO异常类
import java.lang.reflect.InvocationTargetException; // 导入反射调用目标异常类
import java.lang.reflect.Method; // 导入反射方法类
import java.net.MalformedURLException; // 导入格式错误的URL异常类
import java.net.URI; // 导入URI类
import java.net.URISyntaxException; // 导入URI语法异常类
import java.net.URL; // 导入URL类
import java.util.ArrayList; // 导入ArrayList类
import java.util.List; // 导入List接口
import java.util.regex.Matcher; // 导入正则表达式匹配器类
import java.util.regex.Pattern; // 导入正则表达式模式类

import org.apache.catalina.security.SecurityClassLoad; // 导入Catalina安全类加载器
import org.apache.catalina.startup.ClassLoaderFactory.Repository; // 导入类加载器工厂的存储库类
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType; // 导入类加载器工厂的存储库类型
import org.apache.juli.logging.Log; // 导入日志接口
import org.apache.juli.logging.LogFactory; // 导入日志工厂类
/**
 * 阅读的流程
 * 1. 启动阶段（服务器初始化）
 * org.apache.catalina.startup.Bootstrap          // 启动入口类
 * └─ org.apache.catalina.startup.Catalina        // 初始化 Catalina 引擎
 *    └─ org.apache.catalina.core.StandardServer  // 管理 Service 组件
 *       └─ org.apache.catalina.core.StandardService  // 连接 Connector 和 Container
 *          ├─ org.apache.coyote.http11.Http11NioProtocol  // HTTP/1.1 协议处理器（NIO）
 *          └─ org.apache.catalina.core.StandardEngine    // 顶级容器
 *             └─ org.apache.catalina.core.StandardHost   // 虚拟主机
 *                └─ org.apache.catalina.core.StandardContext  // Web 应用上下文
 *
 * 2. Connector 接收请求
 * org.apache.coyote.http11.Http11NioProtocol$Http11ConnectionHandler  // 连接处理器
 * └─ org.apache.tomcat.util.net.NioEndpoint$SocketProcessor  // NIO 套接字处理器
 *    └─ org.apache.coyote.AbstractProcessorLight  // 抽象请求处理器
 *       └─ org.apache.coyote.http11.Http11Processor  // HTTP/1.1 请求解析器
 *          ├─ 解析 HTTP 请求行和头部
 *          └─ 创建 Request 和 Response 对象
 *
 * 3. 请求进入容器（Container）
 * org.apache.catalina.connector.CoyoteAdapter  // 连接 Coyote 和 Catalina
 * └─ org.apache.catalina.connector.Request  // Catalina 请求对象
 *    └─ org.apache.catalina.core.StandardEngineValve  // 引擎阀门
 *       └─ org.apache.catalina.core.StandardHostValve  // 主机阀门
 *          └─ org.apache.catalina.core.StandardContextValve  // 上下文阀门
 *             ├─ 检查安全约束（SecurityConstraints）
 *             └─ 创建 ServletRequest 和 ServletResponse
 *
 * 4. 映射请求到 Servlet
 * org.apache.catalina.core.StandardWrapperValve  // Wrapper 阀门
 * └─ org.apache.catalina.core.ApplicationFilterChain  // 过滤器链
 *    ├─ 执行过滤器（Filter）链
 *    └─ 找到匹配的 Servlet
 *       └─ org.apache.catalina.core.StandardWrapper  // Servlet 包装器
 *          └─ 加载并初始化 Servlet（如果未初始化）
 *
 * 5. 执行 Servlet
 * javax.servlet.http.HttpServlet  // 用户自定义 Servlet 基类
 * └─ 用户实现的 HttpServlet 子类
 *    ├─ doGet()/doPost() 方法处理请求
 *    └─ 生成响应内容到 ServletResponse
 *
 * 6. 响应处理
 * org.apache.catalina.connector.Response  // Catalina 响应对象
 * └─ org.apache.catalina.connector.CoyoteAdapter  // 转换为 Coyote 响应
 *    └─ org.apache.coyote.http11.Http11OutputBuffer  // HTTP 输出缓冲区
 *       └─ org.apache.tomcat.util.net.SocketChannelIO  // 通过 NIO 写回客户端
 *
 * 7. 关键辅助类
 * 线程池管理
 * org.apache.tomcat.util.threads.ThreadPoolExecutor  // 工作线程池
 * org.apache.tomcat.util.threads.TaskQueue          // 任务队列
 *
 * 类加载
 * org.apache.catalina.loader.WebappClassLoaderBase  // Web 应用类加载器
 *
 * 会话管理
 * org.apache.catalina.session.StandardManager  // 会话管理器
 * org.apache.catalina.session.StandardSession  // 会话实现
 */
/**
 * 在 Tomcat 中，JDBC 驱动无法通过上下文类加载器找到的问题，本质上是由类加载器隔离机制和Java SPI（服务提供者接口）加载逻辑共同导致的。
 * Tomcat 为每个 Web 应用创建独立的类加载器（WebappClassLoader），形成严格的隔离体系：
 * Bootstrap ClassLoader（根加载器）
 *     ↓
 * Extension ClassLoader（扩展加载器）
 *     ↓
 * System ClassLoader（系统加载器，即Application ClassLoader）
 *     ↓
 * Tomcat CatalinaLoader（Tomcat系统加载器）
 *     ↓
 * Tomcat SharedLoader（共享加载器）
 *     ↓
 * WebappClassLoader（每个Web应用独立加载器），WebappClassLoader 的特性：优先加载自身路径（WEB-INF/classes和WEB-INF/lib）的类，父类加载器无法反向访问其子加载器中的类。
 *
 * JDBC 驱动通过java.sql.DriverManager注册，其核心逻辑如下：
 * // DriverManager初始化时会扫描并加载驱动
 * static {
 *     loadInitialDrivers();
 *     println("JDBC DriverManager initialized");
 * }
 * private static void loadInitialDrivers() {
 *     // 通过SPI机制查找所有java.sql.Driver实现
 *     // 关键问题：ServiceLoader.load()默认使用调用者的类加载器（即DriverManager的类加载器，通常是系统类加载器），而不是当前线程的上下文类加载器。
 *     ServiceLoader<Driver> loadedDrivers = ServiceLoader.load(Driver.class);
 *     // 遍历加载驱动类
 *     Iterator<Driver> driversIterator = loadedDrivers.iterator();
 *     try {
 *         while (driversIterator.hasNext()) {
 *             driversIterator.next();
 *         }
 *     } catch (Throwable t) {
 *         // 忽略错误
 *     }
 * }
 *
 * 假设将 JDBC 驱动（如mysql-connector-java.jar）放在 Web 应用的WEB-INF/lib下，此时：
 * 驱动类（如com.mysql.cj.jdbc.Driver）由WebappClassLoader加载。
 * DriverManager使用系统类加载器（Application ClassLoader）查找驱动类，但系统类加载器无法访问WebappClassLoader中的类，导致加载失败。
 *
 * 根本原因：
 * 类加载器层级冲突：系统类加载器是WebappClassLoader的祖先，但祖先无法访问子孙加载器中的类（双亲委派机制的限制）。
 * SPI 加载逻辑缺陷：ServiceLoader未主动使用上下文类加载器，而是固定使用调用者的类加载器，导致无法找到 Web 应用内的驱动类。
 *
 * Tomcat 的解决方案：显式设置上下文类加载器，将当前线程（即 Tomcat 启动线程）的上下文类加载器设置为catalinaLoader（Tomcat 的系统加载器），
 * 而WebappClassLoader的父类加载器通常是sharedLoader（继承自catalinaLoader）。
 * Thread.currentThread().setContextClassLoader(catalinaLoader);
 *
 * 当DriverManager通过 SPI 加载驱动时，若直接使用系统类加载器失败，部分框架（如 Tomcat）会通过以下方式间接使用上下文类加载器
 * 通过Thread.currentThread().getContextClassLoader()获取当前线程的上下文类加载器，从而让DriverManager能访问 Web 应用内的驱动类。
 * // 手动通过上下文类加载器加载驱动（伪代码）
 * ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
 * if (contextClassLoader != null) {
 *     try {
 *         Class<?> driverClass = contextClassLoader.loadClass(driverClassName);
 *         Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
 *         DriverManager.registerDriver(driver);
 *     } catch (Exception e) {
 *         // 处理异常
 *     }
 * }
 */

/**
 * Catalina的引导加载器。
 * 该应用程序构造一个类加载器，用于加载Catalina内部类
 * (通过累积在"catalina.home"下的"server"目录中找到的所有JAR文件)，
 * 并开始容器的常规执行。
 * 这种迂回方法的目的是将Catalina内部类
 * (以及它们依赖的任何其他类，如XML解析器)与系统类路径隔离，
 * 从而对应用程序级类不可见。
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public final class Bootstrap { // 定义Bootstrap类，final表示不可被继承

    private static final Log log = LogFactory.getLog(Bootstrap.class); // 获取日志实例，用于记录引导过程中的事件

    /**
     * 主方法使用的守护进程对象。
     * 用于同步访问Bootstrap实例
     *
     * main 方法是静态方法，无法直接锁定 this 对象，因此需要一个静态的锁对象。daemonLock 作为 private static final 成员，属于类级别的共享资源，适合作为静态方法的同步锁。
     * final 修饰确保 daemonLock 在初始化后无法被修改，避免锁对象被意外替换导致同步失效
     * static 修饰确保全类共享同一个锁对象，所有线程操作都基于同一把锁
     *
     */
    private static final Object daemonLock = new Object(); // 同步锁对象，用于保护daemon实例的访问
    /**
     * 类级静态变量（全类共享）,在main方法中通过new Bootstrap()创建,Tomcat 启动的 "引导器"，负责初始化和流程控制,daemon创建并持有catalinaDaemon
     *
     * 如果daemon没有被声明为 volatile，可能会出现以下问题：
     *
     * 指令重排序问题：
     *  JVM 可能对new Bootstrap()操作进行指令重排：
     *      1.分配内存空间
     *      2.将引用指向内存空间（此时对象尚未初始化）
     *      3.执行构造函数初始化对象
     *  若另一个线程在步骤 2 之后、步骤 3 之前检查daemon，会得到一个非空但未完全初始化的对象
     * 可见性问题：
     *  没有 volatile 修饰时，一个线程对daemon的修改可能不会立即刷新到主内存
     *  其他线程可能继续使用本地缓存中的旧值，导致重复创建实例
     */
    private static volatile Bootstrap daemon = null; // 单例Bootstrap实例，volatile确保线程可见性

    private static final File catalinaBaseFile; // Catalina基础目录文件对象
    private static final File catalinaHomeFile; // Catalina主目录文件对象

    private static final Pattern PATH_PATTERN = Pattern.compile("(\"[^\"]*\")|(([^,])*)"); // 用于解析路径的正则表达式模式

    static { // 静态代码块，在类加载时执行，用于初始化Catalina的home和base目录
        // 始终为非空
        String userDir = System.getProperty("user.dir"); // 获取当前工作目录

        // 首先处理home目录
        String home = System.getProperty(Constants.CATALINA_HOME_PROP); // 从系统属性获取CATALINA_HOME
        File homeFile = null; // 初始化home文件对象

        if (home != null) { // 如果系统属性中设置了CATALINA_HOME
            File f = new File(home); // 创建文件对象
            try {
                homeFile = f.getCanonicalFile(); // 获取规范文件路径，解析符号链接
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile(); // 无法获取规范路径时使用绝对路径
            }
        }

        if (homeFile == null) { // 如果homeFile仍为null，尝试第一个回退策略
            // 第一个回退：检查当前目录是否是正常Tomcat安装中的bin目录
            File bootstrapJar = new File(userDir, "bootstrap.jar"); // 检查bootstrap.jar是否存在

            if (bootstrapJar.exists()) { // 如果存在，说明当前目录可能是bin目录
                File f = new File(userDir, ".."); // 向上一级目录，可能是Tomcat主目录
                try {
                    homeFile = f.getCanonicalFile(); // 获取规范路径
                } catch (IOException ioe) {
                    homeFile = f.getAbsoluteFile(); // 获取绝对路径
                }
            }
        }

        if (homeFile == null) { // 如果仍为null，尝试第二个回退策略
            // 第二个回退：使用当前目录
            File f = new File(userDir); // 创建当前目录的文件对象
            try {
                homeFile = f.getCanonicalFile(); // 获取规范路径
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile(); // 获取绝对路径
            }
        }

        catalinaHomeFile = homeFile; // 赋值给静态变量
        System.setProperty(Constants.CATALINA_HOME_PROP, catalinaHomeFile.getPath()); // 设置系统属性CATALINA_HOME

        // 然后处理base目录
        String base = System.getProperty(Constants.CATALINA_BASE_PROP); // 从系统属性获取CATALINA_BASE
        if (base == null) { // 如果未设置CATALINA_BASE
            catalinaBaseFile = catalinaHomeFile; // base目录默认为home目录
        } else { // 如果设置了CATALINA_BASE
            File baseFile = new File(base); // 创建base目录文件对象
            try {
                baseFile = baseFile.getCanonicalFile(); // 获取规范路径
            } catch (IOException ioe) {
                baseFile = baseFile.getAbsoluteFile(); // 获取绝对路径
            }
            catalinaBaseFile = baseFile; // 赋值给静态变量
        }
        System.setProperty(Constants.CATALINA_BASE_PROP, catalinaBaseFile.getPath()); // 设置系统属性CATALINA_BASE
    }

    // -------------------------------------------------------------- 变量定义

    /**
     * 实例级变量（每个Bootstrap实例独有）,在init()方法中通过反射创建Catalina实例,Tomcat 容器的 "核心引擎"，负责业务逻辑实现,catalinaDaemon依赖daemon的类加载器配置
     *
     * 守护进程引用，catalinaDaemon是 Tomcat 核心启动类Catalina的实例，负责管理容器的生命周期
     * 虽然catalinaDaemon不是操作系统进程，但 Tomcat 确实可以作为系统服务运行：通过systemd、init.d脚本将 Tomcat 注册为系统服务
     * 这些实现方式将 Tomcat 进程注册为系统服务，使其具备守护进程的特性，但这是通过外部脚本或工具实现的，而非catalinaDaemon变量直接控制。
     */
    private Object catalinaDaemon = null; // Catalina守护进程实例

    ClassLoader commonLoader = null; // 公共类加载器，用于加载公共库
    ClassLoader catalinaLoader = null; // Catalina类加载器，用于加载Catalina内部类
    ClassLoader sharedLoader = null; // 共享类加载器，用于加载共享库

    // -------------------------------------------------------- 私有方法

    /**
     * 初始化类加载器
     */
    private void initClassLoaders() {
        try {
            commonLoader = createClassLoader("common", null); // 创建公共类加载器
            if (commonLoader == null) { // 如果创建失败
                // 没有配置文件，默认为此加载器 - 可能处于"单例"环境中
                commonLoader = this.getClass().getClassLoader(); // 使用当前类的类加载器
            }
            catalinaLoader = createClassLoader("server", commonLoader); // 创建Catalina类加载器，父加载器为commonLoader
            sharedLoader = createClassLoader("shared", commonLoader); // 创建共享类加载器，父加载器为commonLoader
        } catch (Throwable t) { // 捕获所有可能的异常
            handleThrowable(t); // 处理Throwable
            log.error("Class loader creation threw exception", t); // 记录错误日志
            System.exit(1); // 退出程序
        }
    }

    /**
     * 创建类加载器
     *
     * @param name 类加载器名称，用于查找配置属性
     * @param parent 父类加载器
     * @return 创建的类加载器实例
     * @throws Exception 加载器创建过程中可能抛出的异常
     */
    private ClassLoader createClassLoader(String name, ClassLoader parent) throws Exception {

        //对于common来说，一般是${catalina.base}/lib,${catalina.home}/lib
        String value = CatalinaProperties.getProperty(name + ".loader"); // 获取类加载器配置属性
        if ((value == null) || (value.isEmpty())) { // 如果属性为空
            return parent; // 返回父类加载器
        }

        value = replace(value); // 替换属性中的系统变量

        List<Repository> repositories = new ArrayList<>(); // 创建存储库列表

        String[] repositoryPaths = getPaths(value); // 解析配置的路径

        for (String repository : repositoryPaths) { // 遍历每个路径
            // 检查是否为JAR URL存储库
            try {
                URI uri = new URI(repository); // 将路径转换为URI
                /**
                 * URL 是 URI 的一个子集，专门用于标识网络资源
                 * uri.toURL()方法将 URI 转换为具体的 URL 对象
                 * URL 包含了访问资源所需的协议、主机、路径等完整信息
                 */
                @SuppressWarnings("unused")
                URL url = uri.toURL();
                repositories.add(new Repository(repository, RepositoryType.URL)); // 添加URL类型的存储库
                continue; // 继续下一个路径
            } catch (IllegalArgumentException | MalformedURLException | URISyntaxException e) {
                // 转换失败，忽略异常
            }

            // 本地存储库处理
            if (repository.endsWith("*.jar")) { // 处理通配符JAR
                repository = repository.substring(0, repository.length() - "*.jar".length()); // 移除通配符
                repositories.add(new Repository(repository, RepositoryType.GLOB)); // 添加通配符类型存储库
            } else if (repository.endsWith(".jar")) { // 处理单个JAR文件
                repositories.add(new Repository(repository, RepositoryType.JAR)); // 添加JAR类型存储库
            } else { // 处理目录
                repositories.add(new Repository(repository, RepositoryType.DIR)); // 添加目录类型存储库
            }
        }

        return ClassLoaderFactory.createClassLoader(repositories, parent); // 使用ClassLoaderFactory创建类加载器
    }

    /**
     * 替换给定字符串中的系统属性
     * 格式：${property.name}
     * 示例：${java.version} 会被替换为 JVM 版本号
     *
     * 未闭合的占位符（如 ${test）会被保留
     * 空属性名（如 ${}）会被保留
     *
     * 特殊的：${catalina.home}：替换为 Tomcat 主目录路径
     * ${catalina.base}：替换为 Tomcat 基础目录路径
     *
     * @param str 原始字符串
     * @return 替换后的字符串
     */
    private String replace(String str) {
        // 实现从ClassLoaderLogManager.replace()复制而来，
        // 但添加了对catalina.home和catalina.base的特殊处理
        String result = str; // 初始化结果字符串
        int pos_start = str.indexOf("${"); // 查找系统属性占位符开始位置
        if (pos_start >= 0) { // 如果存在占位符
            StringBuilder builder = new StringBuilder(); // 创建字符串构建器
            int pos_end = -1; // 初始化占位符结束位置
            while (pos_start >= 0) { // 循环处理所有占位符
                builder.append(str, pos_end + 1, pos_start); // 添加占位符前的内容
                pos_end = str.indexOf('}', pos_start + 2); // 查找占位符结束位置
                if (pos_end < 0) { // 如果没有找到结束符
                    pos_end = pos_start - 1; // 设置结束位置为开始位置前一位
                    break; // 跳出循环
                }
                String propName = str.substring(pos_start + 2, pos_end); // 获取属性名称
                String replacement; // 替换值

                if (propName.isEmpty()) { // 如果属性名称为空
                    replacement = null; // 替换值为null
                } else if (Constants.CATALINA_HOME_PROP.equals(propName)) { // 如果是CATALINA_HOME属性
                    replacement = getCatalinaHome(); // 获取Catalina home路径
                } else if (Constants.CATALINA_BASE_PROP.equals(propName)) { // 如果是CATALINA_BASE属性
                    replacement = getCatalinaBase(); // 获取Catalina base路径
                } else { // 其他系统属性
                    replacement = System.getProperty(propName); // 从系统属性获取值
                }

                if (replacement != null) { // 如果替换值不为null
                    builder.append(replacement); // 添加替换值
                } else { // 替换值为null
                    builder.append(str, pos_start, pos_end + 1); // 添加原始占位符
                }

                pos_start = str.indexOf("${", pos_end + 1); // 查找下一个占位符
            }
            builder.append(str, pos_end + 1, str.length()); // 添加最后部分内容
            result = builder.toString(); // 转换为字符串
        }
        return result; // 返回替换后的字符串
    }

    /**
     * 初始化守护进程
     *
     * @throws Exception 初始化过程中可能抛出的异常
     */
    public void init() throws Exception {

        initClassLoaders(); // 初始化类加载器

        /**
         * 在 Java 中，每个线程都有一个上下文类加载器(Context ClassLoader)，setContextClassLoader方法将当前线程（启动线程）的上下文类加载器设为 Tomcat 顶层加载器
         * catalinaLoader → CommonClassLoader → Application ClassLoader
         * 这行代码的核心目的是让 Tomcat 核心线程使用自定义类加载器加载类
         * Tomcat 需要加载自身的核心类（如Catalina类），这些类由catalinaLoader管理，若使用默认的系统类加载器，可能无法找到 Tomcat 的自定义类
         */
        Thread.currentThread().setContextClassLoader(catalinaLoader); // 设置当前线程的上下文类加载器为catalinaLoader

        //当 Java 安全管理器（SecurityManager）启用时，类加载操作会受到更严格的安全检查，预加载这些类可以确保它们由系统信任的类加载器加载，避免后续访问时的权限问题
        //若Catalina类先加载，其依赖的内部类可能在后续加载时因权限不足抛出异常
        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // 加载启动类并调用其process()方法
        if (log.isTraceEnabled()) { // 如果日志级别为TRACE
            log.trace("Loading startup class"); // 记录跟踪日志
        }
        Class<?> startupClass = catalinaLoader.loadClass("org.apache.catalina.startup.Catalina"); // 加载Catalina类
        Object startupInstance = startupClass.getConstructor().newInstance(); // 创建Catalina实例

        // 设置共享扩展类加载器
        if (log.isTraceEnabled()) { // 如果日志级别为TRACE
            log.trace("Setting startup class properties"); // 记录跟踪日志
        }
        String methodName = "setParentClassLoader"; // 方法名称
        Class<?>[] paramTypes = new Class[1]; // 参数类型数组
        paramTypes[0] = Class.forName("java.lang.ClassLoader"); // 参数类型为ClassLoader
        Object[] paramValues = new Object[1]; // 参数值数组
        paramValues[0] = sharedLoader; // 参数值为sharedLoader
        Method method = startupInstance.getClass().getMethod(methodName, paramTypes); // 获取方法
        method.invoke(startupInstance, paramValues); // 调用方法设置父类加载器

        catalinaDaemon = startupInstance; // 保存Catalina实例
    }

    /**
     * 加载守护进程
     *
     * @param arguments 命令行参数
     * @throws Exception 加载过程中可能抛出的异常
     */
    private void load(String[] arguments) throws Exception {

        // 调用load()方法
        String methodName = "load"; // 方法名称
        Object[] param; // 参数值数组
        Class<?>[] paramTypes; // 参数类型数组

        if (arguments == null || arguments.length == 0) { // 如果没有参数
            paramTypes = null; // 参数类型为null
            param = null; // 参数值为null
        } else { // 如果有参数
            paramTypes = new Class[1]; // 参数类型数组长度为1
            paramTypes[0] = arguments.getClass(); // 参数类型为String[]
            param = new Object[1]; // 参数值数组长度为1
            param[0] = arguments; // 参数值为arguments
        }

        Method method = catalinaDaemon.getClass().getMethod(methodName, paramTypes); // 获取方法
        if (log.isTraceEnabled()) { // 如果日志级别为TRACE
            log.trace("Calling startup class " + method); // 记录跟踪日志
        }
        method.invoke(catalinaDaemon, param); // 调用Catalina的load方法
    }

    /**
     * 用于configtest命令获取Server实例
     *
     * @return Server实例
     * @throws Exception 调用过程中可能抛出的异常
     */
    private Object getServer() throws Exception {

        String methodName = "getServer"; // 方法名称
        Method method = catalinaDaemon.getClass().getMethod(methodName); // 获取方法
        return method.invoke(catalinaDaemon); // 调用方法获取Server实例
    }

    // ----------------------------------------------------------- 主程序

    /**
     * 加载Catalina守护进程
     *
     * @param arguments 初始化参数
     * @throws Exception 初始化过程中可能抛出的异常
     */
    public void init(String[] arguments) throws Exception {

        init(); // 初始化
        load(arguments); // 加载
    }

    /**
     * 启动Catalina守护进程
     *
     * @throws Exception 启动过程中可能抛出的异常
     */
    public void start() throws Exception {
        if (catalinaDaemon == null) { // 如果Catalina实例未初始化
            init(); // 执行初始化
        }

        Method method = catalinaDaemon.getClass().getMethod("start", (Class<?>[]) null); // 获取start方法
        method.invoke(catalinaDaemon, (Object[]) null); // 调用start方法启动Catalina
    }

    /**
     * 停止Catalina守护进程
     *
     * @throws Exception 停止过程中可能抛出的异常
     */
    public void stop() throws Exception {
        Method method = catalinaDaemon.getClass().getMethod("stop", (Class<?>[]) null); // 获取stop方法
        method.invoke(catalinaDaemon, (Object[]) null); // 调用stop方法停止Catalina
    }

    /**
     * 停止独立服务器
     *
     * @throws Exception 停止过程中可能抛出的异常
     */
    public void stopServer() throws Exception {

        Method method = catalinaDaemon.getClass().getMethod("stopServer", (Class<?>[]) null); // 获取stopServer方法
        method.invoke(catalinaDaemon, (Object[]) null); // 调用stopServer方法
    }

    /**
     * 停止独立服务器
     *
     * @param arguments 命令行参数
     * @throws Exception 停止过程中可能抛出的异常
     */
    public void stopServer(String[] arguments) throws Exception {

        Object[] param; // 参数值数组
        Class<?>[] paramTypes; // 参数类型数组

        if (arguments == null || arguments.length == 0) { // 如果没有参数
            paramTypes = null; // 参数类型为null
            param = null; // 参数值为null
        } else { // 如果有参数
            paramTypes = new Class[1]; // 参数类型数组长度为1
            paramTypes[0] = arguments.getClass(); // 参数类型为String[]
            param = new Object[1]; // 参数值数组长度为1
            param[0] = arguments; // 参数值为arguments
        }

        Method method = catalinaDaemon.getClass().getMethod("stopServer", paramTypes); // 获取stopServer方法
        method.invoke(catalinaDaemon, param); // 调用stopServer方法
    }

    /**
     * 设置等待标志
     *
     * @param await 如果守护进程应该阻塞则为true
     * @throws Exception 反射调用过程中可能抛出的异常
     */
    public void setAwait(boolean await) throws Exception {

        Class<?>[] paramTypes = new Class[1]; // 参数类型数组长度为1
        paramTypes[0] = Boolean.TYPE; // 参数类型为boolean
        Object[] paramValues = new Object[1]; // 参数值数组长度为1
        paramValues[0] = Boolean.valueOf(await); // 参数值为包装后的boolean

        Method method = catalinaDaemon.getClass().getMethod("setAwait", paramTypes); // 获取setAwait方法
        method.invoke(catalinaDaemon, paramValues); // 调用setAwait方法
    }

    /**
     * 获取等待标志
     *
     * @return 等待标志值
     * @throws Exception 反射调用过程中可能抛出的异常
     */
    public boolean getAwait() throws Exception {
        Class<?>[] paramTypes = new Class[0]; // 无参数
        Object[] paramValues = new Object[0]; // 无参数值

        Method method = catalinaDaemon.getClass().getMethod("getAwait", paramTypes); // 获取getAwait方法
        Boolean b = (Boolean) method.invoke(catalinaDaemon, paramValues); // 调用getAwait方法
        return b.booleanValue(); // 返回boolean值
    }

    /**
     * 销毁Catalina守护进程
     */
    public void destroy() {
        // 空实现，子类可重写
    }

    /**
     * 主方法，通过提供的脚本启动Tomcat时的入口点
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {

        //当main方法执行到synchronized (daemonLock)时，Bootstrap类已经完成初始化
        //若尝试实例化Bootstrap后，使用new Object()作为锁对象，会导致每个实例都有不同的锁，无法实现全局同步
        synchronized (daemonLock) {
            if (daemon == null) { // 如果daemon实例未创建
                // 在init()完成之前不要设置daemon
                Bootstrap bootstrap = new Bootstrap(); // 创建Bootstrap实例
                try {
                    bootstrap.init(); // 初始化Bootstrap
                } catch (Throwable t) { // 捕获所有可能的异常
                    handleThrowable(t); // 处理Throwable
                    log.error("Init exception", t); // 记录错误日志
                    return; // 返回
                }
                daemon = bootstrap; // 设置daemon实例
            } else { // 如果daemon已存在
                // 作为服务运行时，对stop的调用将在新线程上进行，
                // 因此确保使用正确的类加载器，以防止各种类未找到异常。
                Thread.currentThread().setContextClassLoader(daemon.catalinaLoader); // 设置上下文类加载器
            }
        }

        try {
            String command = "start"; // 默认为start命令
            if (args.length > 0) { // 如果有命令行参数
                command = args[args.length - 1]; // 获取最后一个参数作为命令
            }

            switch (command) { // 根据命令执行不同操作
                case "startd": // 后台启动
                    args[args.length - 1] = "start"; // 修改参数为start
                    daemon.load(args); // 加载
                    daemon.start(); // 启动
                    break;
                case "stopd": // 后台停止
                    args[args.length - 1] = "stop"; // 修改参数为stop
                    daemon.stop(); // 停止
                    break;
                case "start": // 前台启动
                    daemon.setAwait(true); // 设置等待标志
                    daemon.load(args); // 加载
                    daemon.start(); // 启动
                    if (null == daemon.getServer()) { // 如果Server实例为null
                        System.exit(1); // 退出并返回错误码
                    }
                    break;
                case "stop": // 前台停止
                    daemon.stopServer(args); // 停止服务器
                    break;
                case "configtest": // 配置测试
                    daemon.load(args); // 加载
                    if (null == daemon.getServer()) { // 如果Server实例为null
                        System.exit(1); // 退出并返回错误码
                    }
                    System.exit(0); // 退出并返回成功码
                    break;
                default: // 未知命令
                    log.warn("Bootstrap: command \"" + command + "\" does not exist."); // 记录警告日志
                    break;
            }
        } catch (Throwable t) { // 捕获所有可能的异常
            // 解包异常以获得更清晰的错误报告
            Throwable throwable = t;
            if (throwable instanceof InvocationTargetException && throwable.getCause() != null) {
                throwable = throwable.getCause(); // 获取原始异常
            }
            handleThrowable(throwable); // 处理Throwable
            log.error("Error running command", throwable); // 记录错误日志
            System.exit(1); // 退出并返回错误码
        }
    }

    /**
     * 获取配置的home(二进制)目录名称。
     * 注意home和base可能相同(默认情况下是相同的)。
     *
     * @return catalina home路径
     */
    public static String getCatalinaHome() {
        return catalinaHomeFile.getPath(); // 返回catalinaHomeFile的路径
    }

    /**
     * 获取配置的base(实例)目录名称。
     * 注意home和base可能相同(默认情况下是相同的)。
     * 如果未设置，将使用{@link #getCatalinaHome()}返回的值。
     *
     * @return catalina base路径
     */
    public static String getCatalinaBase() {
        return catalinaBaseFile.getPath(); // 返回catalinaBaseFile的路径
    }

    /**
     * 获取配置的home(二进制)目录。
     * 注意home和base可能相同(默认情况下是相同的)。
     *
     * @return catalina home的File对象
     */
    public static File getCatalinaHomeFile() {
        return catalinaHomeFile; // 返回catalinaHomeFile对象
    }

    /**
     * 获取配置的base(实例)目录。
     * 注意home和base可能相同(默认情况下是相同的)。
     * 如果未设置，将使用{@link #getCatalinaHomeFile()}返回的值。
     *
     * @return catalina base的File对象
     */
    public static File getCatalinaBaseFile() {
        return catalinaBaseFile; // 返回catalinaBaseFile对象
    }

    // 从ExceptionUtils复制而来，因为该类在启动时不可见
    static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) { // 如果是线程死亡异常
            throw (ThreadDeath) t; // 抛出异常
        }
        if (t instanceof StackOverflowError) { // 如果是栈溢出错误
            // 静默吞咽 - 应该是可恢复的
            return;
        }
        if (t instanceof VirtualMachineError) { // 如果是虚拟机错误
            throw (VirtualMachineError) t; // 抛出错误
        }
        // 其他所有Throwable实例将被静默吞咽
    }

    // 从ExceptionUtils复制而来，以便不依赖utils
    static Throwable unwrapInvocationTargetException(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) { // 如果是调用目标异常且有原因
            return t.getCause(); // 返回原因异常
        }
        return t; // 返回原始异常
    }

    /**
     * 解析路径字符串并返回路径数组，支持处理带双引号的路径
     *
     * @param value 包含路径的字符串，路径之间通常用逗号分隔
     * @return 解析后的路径数组
     */
    static String[] getPaths(String value) {

        // 创建用于存储结果路径的ArrayList
        List<String> result = new ArrayList<>();

        // 使用预定义的正则表达式模式匹配路径，PATH_PATTERN用于匹配带引号或不带引号的路径
        Matcher matcher = PATH_PATTERN.matcher(value);

        // 循环查找所有匹配的路径
        while (matcher.find()) {
            // 获取当前匹配的路径子字符串
            String path = value.substring(matcher.start(), matcher.end());

            // 去除路径两端的空白字符
            path = path.trim();

            // 如果处理后路径为空，跳过当前循环
            if (path.isEmpty()) {
                continue;
            }

            // 获取路径的首字符和尾字符，用于判断是否为引号包裹的路径
            char first = path.charAt(0);
            char last = path.charAt(path.length() - 1);

            // 处理双引号包裹的路径（如"path/to/lib.jar"）
            if (first == '"' && last == '"' && path.length() > 1) {
                // 去除首尾的双引号
                path = path.substring(1, path.length() - 1);
                // 再次去除两端的空白字符（处理引号内的空白）
                path = path.trim();
                // 如果去除引号和空白后路径为空，跳过当前循环
                if (path.isEmpty()) {
                    continue;
                }
            }
            // 检查路径中是否包含未匹配的双引号（如path"to/lib.jar）
            else if (path.contains("\"")) {
                // 抛出异常，提示双引号使用错误
                throw new IllegalArgumentException(
                    "双引号[\"]字符只能用于引用路径。它不能出现在路径中。此加载器路径无效: [" + value + "]");
            }
            // 非引号包裹的路径无需特殊处理
            else {
                // 留空，不做操作
            }

            // 将处理后的有效路径添加到结果列表
            result.add(path);
        }

        // 将结果列表转换为字符串数组并返回
        return result.toArray(new String[0]);
    }
}