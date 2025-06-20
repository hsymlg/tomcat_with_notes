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
     * 守护进程引用，指向Catalina实例
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
                @SuppressWarnings("unused")
                URL url = uri.toURL(); // 将URI转换为URL
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

        Thread.currentThread().setContextClassLoader(catalinaLoader); // 设置当前线程的上下文类加载器为catalinaLoader

        SecurityClassLoad.securityClassLoad(catalinaLoader); // 执行安全类加载检查

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

    // 供单元测试保护
    static String[] getPaths(String value) {

        List<String> result = new ArrayList<>(); // 创建结果列表
        Matcher matcher = PATH_PATTERN.matcher(value); // 创建正则表达式匹配器

        while (matcher.find()) { // 查找所有匹配项
            String path = value.substring(matcher.start(), matcher.end()); // 获取匹配的路径

            path = path.trim(); // 去除路径两端的空白
            if (path.isEmpty()) { // 如果路径为空
                continue; // 继续下一个匹配
            }

            char first = path.charAt(0); // 获取路径首字符
            char last = path.charAt(path.length() - 1); // 获取路径尾字符

            if (first == '"' && last == '"' && path.length() > 1) { // 如果是用双引号包裹的路径
                path = path.substring(1, path.length() - 1); // 去除双引号
                path = path.trim(); // 去除两端空白
                if (path.isEmpty()) { // 如果处理后路径为空
                    continue; // 继续下一个匹配
                }
            } else if (path.contains("\"")) { // 如果路径中包含未匹配的双引号
                // 引号不平衡
                // 太早使用标准i18n支持。类路径尚未配置。
                throw new IllegalArgumentException(
                    "双引号[\"]字符只能用于引用路径。它不能出现在路径中。此加载器路径无效: [" + value + "]");
            } else { // 非引号包裹的路径
                // 不做操作
            }

            result.add(path); // 将路径添加到结果列表
        }

        return result.toArray(new String[0]); // 转换为字符串数组并返回
    }
}