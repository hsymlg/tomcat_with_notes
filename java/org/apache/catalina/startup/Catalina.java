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
package org.apache.catalina.startup;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;

import org.apache.catalina.Container;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Server;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.security.SecurityConfig;
import org.apache.juli.ClassLoaderLogManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.digester.Rule;
import org.apache.tomcat.util.digester.RuleSet;
import org.apache.tomcat.util.file.ConfigFileLoader;
import org.apache.tomcat.util.file.ConfigurationSource;
import org.apache.tomcat.util.log.SystemLogHandler;
import org.apache.tomcat.util.res.StringManager;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;

/**
 * Catalina的启动/关闭控制类，负责解析配置文件、启动服务器实例
 * 支持的命令行选项包括配置文件指定、启动、停止、配置测试等功能
 *
 * Tomcat 的启动流程默认是单线程执行的。Catalina 类的 load() 方法通常由主线程调用，在整个启动过程中不会有多个线程同时尝试调用该方法。
 * 因此，从设计意图上来说，不存在多线程并发调用 load() 方法的场景，自然也就不需要使用 volatile 来保证可见性。
 * Catalina 对象通常由 Bootstrap 类单例创建，这种单例模式可能通过同步机制确保对象的安全发布，从而使 loaded 变量对其他线程可见。
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public class Catalina {

    /** 字符串管理器，用于国际化消息处理 */
    protected static final StringManager sm = StringManager.getManager(Constants.Package);

    /** 默认服务器配置文件路径 */
    public static final String SERVER_XML = "conf/server.xml";

    // ------------------------------- 实例变量定义 -------------------------------

    /** 是否在启动后阻塞等待（如监听关闭信号） */
    protected boolean await = false;

    /** 服务器配置文件路径，默认为conf/server.xml */
    protected String configFile = SERVER_XML;

    /** 服务器共享扩展类加载器，用于加载公共组件 */
    protected ClassLoader parentClassLoader = Catalina.class.getClassLoader();

    /** 服务器组件实例，负责管理整个容器生命周期 */
    protected Server server = null;

    /** 是否注册JVM关闭钩子（shutdown hook） */
    protected boolean useShutdownHook = true;

    /** 关闭钩子线程，用于优雅关闭Tomcat */
    protected Thread shutdownHook = null;

    /** 是否启用JNDI命名服务 */
    protected boolean useNaming = true;

    /** 防止重复加载标志，确保配置只解析一次 */
    protected boolean loaded = false;

    /** 初始化失败时是否抛出异常（控制启动流程） */
    protected boolean throwOnInitFailure = Boolean.getBoolean("org.apache.catalina.startup.EXIT_ON_INIT_FAILURE");

    /** 是否生成嵌入式代码（用于动态生成配置解析类） */
    protected boolean generateCode = false;

    /** 生成代码的存储位置 */
    protected File generatedCodeLocation = null;

    /** 命令行指定的生成代码位置参数 */
    protected String generatedCodeLocationParameter = null;

    /** 生成代码的包名前缀 */
    protected String generatedCodePackage = "catalinaembedded";

    /** 是否使用生成的代码替代配置文件解析 */
    protected boolean useGeneratedCode = false;

    // ------------------------------- 构造函数 -------------------------------

    /**
     * 初始化Catalina实例
     * 执行安全配置和异常工具类预加载
     */
    public Catalina() {
        setSecurityProtection();          // 初始化安全策略
        ExceptionUtils.preload();         // 预加载异常工具类，提升性能
    }

    // ------------------------------- 属性访问方法 -------------------------------

    /** 设置服务器配置文件路径 */
    public void setConfigFile(String file) {
        configFile = file;
    }

    /** 获取服务器配置文件路径 */
    public String getConfigFile() {
        return configFile;
    }

    /** 设置是否使用JVM关闭钩子 */
    public void setUseShutdownHook(boolean useShutdownHook) {
        this.useShutdownHook = useShutdownHook;
    }

    /** 获取是否使用JVM关闭钩子 */
    public boolean getUseShutdownHook() {
        return useShutdownHook;
    }

    /** 获取是否生成嵌入式代码标志 */
    public boolean getGenerateCode() {
        return this.generateCode;
    }

    /** 设置是否生成嵌入式代码 */
    public void setGenerateCode(boolean generateCode) {
        this.generateCode = generateCode;
    }

    /** 获取是否使用生成代码标志 */
    public boolean getUseGeneratedCode() {
        return this.useGeneratedCode;
    }

    /** 设置是否使用生成代码 */
    public void setUseGeneratedCode(boolean useGeneratedCode) {
        this.useGeneratedCode = useGeneratedCode;
    }

    /** 获取生成代码存储位置 */
    public File getGeneratedCodeLocation() {
        return this.generatedCodeLocation;
    }

    /** 设置生成代码存储位置 */
    public void setGeneratedCodeLocation(File generatedCodeLocation) {
        this.generatedCodeLocation = generatedCodeLocation;
    }

    /** 获取生成代码包名 */
    public String getGeneratedCodePackage() {
        return this.generatedCodePackage;
    }

    /** 设置生成代码包名 */
    public void setGeneratedCodePackage(String generatedCodePackage) {
        this.generatedCodePackage = generatedCodePackage;
    }

    /**
     * 获取初始化失败时是否抛出异常的标志
     * @return 初始化失败时是否终止启动流程
     */
    public boolean getThrowOnInitFailure() {
        return throwOnInitFailure;
    }

    /**
     * 设置初始化失败时的处理策略
     * @param throwOnInitFailure 为true时初始化失败将抛出错误
     */
    public void setThrowOnInitFailure(boolean throwOnInitFailure) {
        this.throwOnInitFailure = throwOnInitFailure;
    }

    /**
     * 设置共享扩展类加载器
     * @param parentClassLoader 父类加载器实例
     */
    public void setParentClassLoader(ClassLoader parentClassLoader) {
        this.parentClassLoader = parentClassLoader;
    }

    /**
     * 获取共享扩展类加载器
     * @return 父类加载器实例（若无则返回系统类加载器）
     */
    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null) {
            return parentClassLoader;
        }
        return ClassLoader.getSystemClassLoader();
    }

    /** 设置服务器实例 */
    public void setServer(Server server) {
        this.server = server;
    }

    /** 获取服务器实例 */
    public Server getServer() {
        return server;
    }

    /**
     * 获取命名服务是否启用
     * @return true表示启用JNDI命名服务
     */
    public boolean isUseNaming() {
        return this.useNaming;
    }

    /**
     * 启用或禁用命名服务
     * @param useNaming 新的命名服务状态
     */
    public void setUseNaming(boolean useNaming) {
        this.useNaming = useNaming;
    }

    /** 设置等待模式 */
    public void setAwait(boolean b) {
        await = b;
    }

    /** 获取等待模式状态 */
    public boolean isAwait() {
        return await;
    }

    // ------------------------------- 核心功能方法 -------------------------------

    /**
     * 处理命令行参数
     * @param args 命令行参数数组
     * @return true表示参数合法可继续处理，false表示需要退出
     */
    protected boolean arguments(String[] args) {
        boolean isConfig = false;       // 配置文件路径标志
        boolean isGenerateCode = false; // 生成代码标志

        // 参数长度为0时显示用法并退出
        if (args.length < 1) {
            usage();
            return false;
        }

        // 遍历所有命令行参数
        for (String arg : args) {
            if (isConfig) {
                // 处理-config参数后的配置文件路径
                configFile = arg;
                isConfig = false;
            } else if (arg.equals("-config")) {
                // 标记下一个参数为配置文件路径
                isConfig = true;
            } else if (arg.equals("-generateCode")) {
                // 启用代码生成功能
                setGenerateCode(true);
                isGenerateCode = true;
            } else if (arg.equals("-useGeneratedCode")) {
                // 启用生成代码加载功能
                setUseGeneratedCode(true);
                isGenerateCode = false;
            } else if (arg.equals("-nonaming")) {
                // 禁用命名服务
                setUseNaming(false);
                isGenerateCode = false;
            } else if (arg.equals("-help")) {
                // 显示用法信息并退出
                usage();
                return false;
            } else if (isGenerateCode) {
                // 处理生成代码位置参数
                generatedCodeLocationParameter = arg;
                isGenerateCode = false;
            } else if (arg.equals("start") || arg.equals("configtest") || arg.equals("stop")) {
                // 命令参数，无需特殊处理
                isGenerateCode = false;
            } else {
                // 未知参数时显示用法并退出
                usage();
                return false;
            }
        }
        return true;
    }

    /**
     * 获取配置文件的File对象
     * 处理相对路径（基于catalina.base）
     * @return 配置文件的File实例
     */
    protected File configFile() {
        File file = new File(configFile);
        if (!file.isAbsolute()) {
            // 相对路径转换为基于catalina.base的绝对路径
            file = new File(Bootstrap.getCatalinaBase(), configFile);
        }
        return file;
    }

    /**
     * 创建用于启动流程的Digester实例
     * 配置解析server.xml的规则集
     * @return 初始化好的Digester实例
     */
    protected Digester createStartDigester() {
        // 初始化Digester解析器
        Digester digester = new Digester();
        digester.setValidating(false);                 // 禁用XML验证
        digester.setRulesValidation(true);            // 启用规则验证
        Map<Class<?>, List<String>> fakeAttributes = new HashMap<>();

        // 配置需要忽略的属性（如IDE生成的元数据）
        List<String> objectAttrs = new ArrayList<>();
        objectAttrs.add("className");
        fakeAttributes.put(Object.class, objectAttrs);

        List<String> contextAttrs = new ArrayList<>();
        contextAttrs.add("source");
        fakeAttributes.put(StandardContext.class, contextAttrs);

        List<String> connectorAttrs = new ArrayList<>();
        connectorAttrs.add("portOffset");
        fakeAttributes.put(Connector.class, connectorAttrs);

        digester.setFakeAttributes(fakeAttributes);   // 设置忽略属性
        digester.setUseContextClassLoader(true);      // 使用上下文类加载器

        // --------------------- 配置Server元素解析规则 ---------------------
        digester.addObjectCreate("Server", "org.apache.catalina.core.StandardServer", "className");
        digester.addSetProperties("Server");
        digester.addSetNext("Server", "setServer", "org.apache.catalina.Server");

        // --------------------- 配置全局命名资源解析规则 ---------------------
        digester.addObjectCreate("Server/GlobalNamingResources", "org.apache.catalina.deploy.NamingResourcesImpl");
        digester.addSetProperties("Server/GlobalNamingResources");
        digester.addSetNext("Server/GlobalNamingResources", "setGlobalNamingResources", "org.apache.catalina.deploy.NamingResourcesImpl");

        // --------------------- 配置监听器解析规则 ---------------------
        digester.addRule("Server/Listener", new ListenerCreateRule(null, "className"));
        digester.addSetProperties("Server/Listener");
        digester.addSetNext("Server/Listener", "addLifecycleListener", "org.apache.catalina.LifecycleListener");

        // --------------------- 配置Service元素解析规则 ---------------------
        digester.addObjectCreate("Server/Service", "org.apache.catalina.core.StandardService", "className");
        digester.addSetProperties("Server/Service");
        digester.addSetNext("Server/Service", "addService", "org.apache.catalina.Service");

        // --------------------- 配置Service监听器解析规则 ---------------------
        digester.addObjectCreate("Server/Service/Listener", null, "className");
        digester.addSetProperties("Server/Service/Listener");
        digester.addSetNext("Server/Service/Listener", "addLifecycleListener", "org.apache.catalina.LifecycleListener");

        // --------------------- 配置Executor元素解析规则 ---------------------
        digester.addObjectCreate("Server/Service/Executor", "org.apache.catalina.core.StandardThreadExecutor", "className");
        digester.addSetProperties("Server/Service/Executor");
        digester.addSetNext("Server/Service/Executor", "addExecutor", "org.apache.catalina.Executor");

        // --------------------- 配置Connector元素解析规则 ---------------------
        digester.addRule("Server/Service/Connector", new ConnectorCreateRule());
        digester.addSetProperties("Server/Service/Connector", new String[] { "executor", "sslImplementationName", "protocol" });
        digester.addSetNext("Server/Service/Connector", "addConnector", "org.apache.catalina.connector.Connector");
        digester.addRule("Server/Service/Connector", new AddPortOffsetRule());

        // --------------------- 配置SSLHostConfig元素解析规则 ---------------------
        digester.addObjectCreate("Server/Service/Connector/SSLHostConfig", "org.apache.tomcat.util.net.SSLHostConfig");
        digester.addSetProperties("Server/Service/Connector/SSLHostConfig");
        digester.addSetNext("Server/Service/Connector/SSLHostConfig", "addSslHostConfig", "org.apache.tomcat.util.net.SSLHostConfig");

        // --------------------- 配置Certificate元素解析规则 ---------------------
        digester.addRule("Server/Service/Connector/SSLHostConfig/Certificate", new CertificateCreateRule());
        digester.addSetProperties("Server/Service/Connector/SSLHostConfig/Certificate", new String[] { "type" });
        digester.addSetNext("Server/Service/Connector/SSLHostConfig/Certificate", "addCertificate", "org.apache.tomcat.util.net.SSLHostConfigCertificate");

        // --------------------- 配置OpenSSLConf元素解析规则 ---------------------
        digester.addObjectCreate("Server/Service/Connector/SSLHostConfig/OpenSSLConf", "org.apache.tomcat.util.net.openssl.OpenSSLConf");
        digester.addSetProperties("Server/Service/Connector/SSLHostConfig/OpenSSLConf");
        digester.addSetNext("Server/Service/Connector/SSLHostConfig/OpenSSLConf", "setOpenSslConf", "org.apache.tomcat.util.net.openssl.OpenSSLConf");

        // --------------------- 配置OpenSSLConfCmd元素解析规则 ---------------------
        digester.addObjectCreate("Server/Service/Connector/SSLHostConfig/OpenSSLConf/OpenSSLConfCmd", "org.apache.tomcat.util.net.openssl.OpenSSLConfCmd");
        digester.addSetProperties("Server/Service/Connector/SSLHostConfig/OpenSSLConf/OpenSSLConfCmd");
        digester.addSetNext("Server/Service/Connector/SSLHostConfig/OpenSSLConf/OpenSSLConfCmd", "addCmd", "org.apache.tomcat.util.net.openssl.OpenSSLConfCmd");

        // --------------------- 配置Connector监听器解析规则 ---------------------
        digester.addObjectCreate("Server/Service/Connector/Listener", null, "className");
        digester.addSetProperties("Server/Service/Connector/Listener");
        digester.addSetNext("Server/Service/Connector/Listener", "addLifecycleListener", "org.apache.catalina.LifecycleListener");

        // --------------------- 配置UpgradeProtocol元素解析规则 ---------------------
        digester.addObjectCreate("Server/Service/Connector/UpgradeProtocol", null, "className");
        digester.addSetProperties("Server/Service/Connector/UpgradeProtocol");
        digester.addSetNext("Server/Service/Connector/UpgradeProtocol", "addUpgradeProtocol", "org.apache.coyote.UpgradeProtocol");

        // --------------------- 添加嵌套元素的规则集 ---------------------
        digester.addRuleSet(new NamingRuleSet("Server/GlobalNamingResources/"));
        digester.addRuleSet(new EngineRuleSet("Server/Service/"));
        digester.addRuleSet(new HostRuleSet("Server/Service/Engine/"));
        digester.addRuleSet(new ContextRuleSet("Server/Service/Engine/Host/"));
        addClusterRuleSet(digester, "Server/Service/Engine/Host/Cluster/");
        digester.addRuleSet(new NamingRuleSet("Server/Service/Engine/Host/Context/"));

        // --------------------- 设置容器的父类加载器 ---------------------
        digester.addRule("Server/Service/Engine", new SetParentClassLoaderRule(parentClassLoader));
        addClusterRuleSet(digester, "Server/Service/Engine/Cluster/");

        return digester;
    }

    /**
     * 尝试添加集群规则集（支持可选的集群功能）
     * @param digester Digester实例
     * @param prefix 规则集前缀路径
     */
    private void addClusterRuleSet(Digester digester, String prefix) {
        Class<?> clazz;
        Constructor<?> constructor;
        try {
            // 动态加载集群规则集类（支持集群功能可选）
            clazz = Class.forName("org.apache.catalina.ha.ClusterRuleSet");
            constructor = clazz.getConstructor(String.class);
            RuleSet ruleSet = (RuleSet) constructor.newInstance(prefix);
            digester.addRuleSet(ruleSet);
        } catch (Exception e) {
            // 集群功能未启用时记录日志
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("catalina.noCluster", e.getClass().getName() + ": " + e.getMessage()), e);
            } else if (log.isInfoEnabled()) {
                log.info(sm.getString("catalina.noCluster", e.getClass().getName() + ": " + e.getMessage()));
            }
        }
    }

    /**
     * 创建用于停止流程的Digester实例
     * 配置解析停止命令的规则
     * @return 初始化好的Digester实例
     */
    protected Digester createStopDigester() {
        // 初始化Digester解析器
        Digester digester = new Digester();
        digester.setUseContextClassLoader(true); // 使用上下文类加载器

        // 配置Server元素解析规则（用于停止命令）
        digester.addObjectCreate("Server", "org.apache.catalina.core.StandardServer", "className");
        digester.addSetProperties("Server");
        digester.addSetNext("Server", "setServer", "org.apache.catalina.Server");

        return digester;
    }

    /**
     * 解析服务器配置文件（启动或停止流程）
     * @param start true表示启动流程，false表示停止流程
     */
    protected void parseServerXml(boolean start) {
        // 设置配置源（支持基于catalina.base的路径解析）
        ConfigFileLoader.setSource(new CatalinaBaseConfigurationSource(Bootstrap.getCatalinaBaseFile(), getConfigFile()));
        File file = configFile();

        // 尝试使用生成的代码解析配置（提升性能）
        if (useGeneratedCode && !Digester.isGeneratedCodeLoaderSet()) {
            String loaderClassName = generatedCodePackage + ".DigesterGeneratedCodeLoader";
            try {
                // 加载生成的代码加载器
                Digester.GeneratedCodeLoader loader = (Digester.GeneratedCodeLoader) Catalina.class.getClassLoader()
                    .loadClass(loaderClassName).getDeclaredConstructor().newInstance();
                Digester.setGeneratedCodeLoader(loader);
            } catch (Exception e) {
                // 加载失败时禁用生成代码功能
                if (log.isDebugEnabled()) {
                    log.info(sm.getString("catalina.noLoader", loaderClassName), e);
                } else {
                    log.info(sm.getString("catalina.noLoader", loaderClassName));
                }
                useGeneratedCode = false;
            }
        }

        // 初始化代码生成位置
        File serverXmlLocation = null;
        String xmlClassName = null;
        if (generateCode || useGeneratedCode) {
            xmlClassName = start ? generatedCodePackage + ".ServerXml" : generatedCodePackage + ".ServerXmlStop";
        }
        if (generateCode) {
            // 处理生成代码的存储位置
            if (generatedCodeLocationParameter != null) {
                generatedCodeLocation = new File(generatedCodeLocationParameter);
                if (!generatedCodeLocation.isAbsolute()) {
                    generatedCodeLocation = new File(Bootstrap.getCatalinaHomeFile(), generatedCodeLocationParameter);
                }
            } else if (generatedCodeLocation == null) {
                generatedCodeLocation = new File(Bootstrap.getCatalinaHomeFile(), "work");
            }
            serverXmlLocation = new File(generatedCodeLocation, generatedCodePackage);
            if (!serverXmlLocation.isDirectory() && !serverXmlLocation.mkdirs()) {
                log.warn(sm.getString("catalina.generatedCodeLocationError", generatedCodeLocation.getAbsolutePath()));
                generateCode = false; // 目录创建失败时禁用代码生成
            }
        }

        // 尝试使用生成的代码解析配置
        ServerXml serverXml = null;
        if (useGeneratedCode) {
            serverXml = (ServerXml) Digester.loadGeneratedClass(xmlClassName);
        }

        if (serverXml != null) {
            // 使用生成的代码解析配置
            try {
                serverXml.load(this);
            } catch (Exception e) {
                log.warn(sm.getString("catalina.configFail", "GeneratedCode"), e);
            }
        } else {
            // 使用Digester解析配置文件
            try (ConfigurationSource.Resource resource = ConfigFileLoader.getSource().getServerXml()) {
                Digester digester = start ? createStartDigester() : createStopDigester();
                InputStream inputStream = resource.getInputStream();
                InputSource inputSource = new InputSource(resource.getURI().toURL().toString());
                inputSource.setByteStream(inputStream);

                digester.push(this);
                if (generateCode) {
                    digester.startGeneratingCode();
                    generateClassHeader(digester, start); // 生成代码头部
                }
                digester.parse(inputSource); // 解析XML

                if (generateCode) {
                    generateClassFooter(digester); // 生成代码尾部
                    // 写入生成的代码文件
                    try (FileWriter writer = new FileWriter(new File(serverXmlLocation, start ? "ServerXml.java" : "ServerXmlStop.java"))) {
                        writer.write(digester.getGeneratedCode().toString());
                    }
                    digester.endGeneratingCode();
                    Digester.addGeneratedClass(xmlClassName);
                }
            } catch (Exception e) {
                log.warn(sm.getString("catalina.configFail", file.getAbsolutePath()), e);
                if (file.exists() && !file.canRead()) {
                    log.warn(sm.getString("catalina.incorrectPermissions"));
                }
            }
        }
    }

    /**
     * 停止服务器实例（带参数版本）
     * @param arguments 命令行参数
     */
    public void stopServer(String[] arguments) {
        if (arguments != null) {
            arguments(arguments); // 解析命令行参数
        }

        Server s = getServer();
        if (s == null) {
            // 服务器实例不存在时解析配置文件
            parseServerXml(false);
            if (getServer() == null) {
                log.error(sm.getString("catalina.stopError"));
                System.exit(1);
            }
        } else {
            // 服务器已存在时直接停止
            try {
                s.stop();
                s.destroy();
            } catch (LifecycleException e) {
                log.error(sm.getString("catalina.stopError"), e);
            }
            return;
        }

        // 通过套接字发送关闭命令
        s = getServer();
        if (s.getPortWithOffset() > 0) {
            try (Socket socket = new Socket(s.getAddress(), s.getPortWithOffset());
                 OutputStream stream = socket.getOutputStream()) {
                String shutdown = s.getShutdown();
                for (int i = 0; i < shutdown.length(); i++) {
                    stream.write(shutdown.charAt(i));
                }
                stream.flush();
            } catch (ConnectException ce) {
                log.error(sm.getString("catalina.stopServer.connectException", s.getAddress(),
                    String.valueOf(s.getPortWithOffset()), String.valueOf(s.getPort()),
                    String.valueOf(s.getPortOffset())));
                log.error(sm.getString("catalina.stopError"), ce);
                System.exit(1);
            } catch (IOException e) {
                log.error(sm.getString("catalina.stopError"), e);
                System.exit(1);
            }
        } else {
            log.error(sm.getString("catalina.stopServer"));
            System.exit(1);
        }
    }

    /**
     * 停止服务器实例（无参数版本）
     */
    public void stopServer() {
        stopServer(null);
    }

    /**
     * 加载服务器配置（初始化阶段）
     */
    public void load() {
        if (loaded) {
            return; // 防止重复加载
        }
        loaded = true;

        long t1 = System.nanoTime(); // 记录开始时间

        // 初始化命名服务（JNDI）
        initNaming();

        // 解析服务器配置文件
        parseServerXml(true);
        Server s = getServer();
        if (s == null) {
            return;
        }

        // 设置服务器上下文信息
        getServer().setCatalina(this);
        getServer().setCatalinaHome(Bootstrap.getCatalinaHomeFile());
        getServer().setCatalinaBase(Bootstrap.getCatalinaBaseFile());

        // 重定向系统输入输出流（日志处理）
        initStreams();

        // 初始化服务器实例
        try {
            getServer().init();
        } catch (LifecycleException e) {
            if (throwOnInitFailure) {
                throw new Error(e); // 初始化失败时抛出错误
            } else {
                log.error(sm.getString("catalina.initError"), e);
            }
        }

        // 记录初始化耗时
        if (log.isInfoEnabled()) {
            log.info(sm.getString("catalina.init",
                Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1))));
        }
    }

    /**
     * 带参数的加载方法
     * @param args 命令行参数
     */
    public void load(String[] args) {
        try {
            if (arguments(args)) {
                load(); // 解析参数后执行加载
            }
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }
    }

    /**
     * 启动服务器实例
     */
    public void start() {
        if (getServer() == null) {
            load(); // 服务器实例不存在时先加载
        }

        if (getServer() == null) {
            log.fatal(sm.getString("catalina.noServer"));
            return;
        }

        long t1 = System.nanoTime(); // 记录开始时间

        // 启动服务器
        try {
            getServer().start();
        } catch (LifecycleException e) {
            log.fatal(sm.getString("catalina.serverStartFail"), e);
            try {
                getServer().destroy();
            } catch (LifecycleException e1) {
                log.debug(sm.getString("catalina.destroyFail"), e1);
            }
            return;
        }

        // 记录启动耗时
        if (log.isInfoEnabled()) {
            log.info(sm.getString("catalina.startup",
                Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1))));
        }

        // 生成代码功能处理
        if (generateCode) {
            generateLoader(); // 生成代码加载器
        }

        // 注册JVM关闭钩子
        if (useShutdownHook) {
            if (shutdownHook == null) {
                shutdownHook = new CatalinaShutdownHook();
            }
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            // 禁用JULI的关闭钩子（避免日志丢失）
            LogManager logManager = LogManager.getLogManager();
            if (logManager instanceof ClassLoaderLogManager) {
                ((ClassLoaderLogManager) logManager).setUseShutdownHook(false);
            }
        }

        // 等待模式处理
        if (await) {
            await(); // 阻塞等待关闭信号
            stop();  // 执行关闭流程
        }
    }

    /**
     * 停止服务器实例
     */
    public void stop() {
        try {
            // 先移除关闭钩子，避免重复执行
            if (useShutdownHook) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);

                // 重新启用JULI的关闭钩子（确保日志输出）
                LogManager logManager = LogManager.getLogManager();
                if (logManager instanceof ClassLoaderLogManager) {
                    ((ClassLoaderLogManager) logManager).setUseShutdownHook(true);
                }
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // JDK1.2环境下可能失败，忽略错误
        }

        // 关闭服务器
        try {
            Server s = getServer();
            LifecycleState state = s.getState();
            if (LifecycleState.STOPPING_PREP.compareTo(state) <= 0 && LifecycleState.DESTROYED.compareTo(state) >= 0) {
                // 服务器已在停止流程中，无需操作
            } else {
                s.stop();
                s.destroy();
            }
        } catch (LifecycleException e) {
            log.error(sm.getString("catalina.stopError"), e);
        }
    }

    /**
     * 阻塞等待关闭信号（如shutdown命令）
     */
    public void await() {
        getServer().await();
    }

    /**
     * 打印命令行用法信息
     */
    protected void usage() {
        System.out.println(sm.getString("catalina.usage"));
    }

    /**
     * 初始化系统输入输出流（重定向到日志处理器）
     */
    protected void initStreams() {
        // 替换System.out和System.err为日志处理器
        System.setOut(new SystemLogHandler(System.out));
        System.setErr(new SystemLogHandler(System.err));
    }

    /**
     * 初始化命名服务（JNDI）相关配置
     */
    protected void initNaming() {
        if (!useNaming) {
            log.info(sm.getString("catalina.noNaming"));
            System.setProperty("catalina.useNaming", "false");
        } else {
            System.setProperty("catalina.useNaming", "true");
            // 配置JNDI包前缀
            String value = "org.apache.naming";
            String oldValue = System.getProperty(javax.naming.Context.URL_PKG_PREFIXES);
            if (oldValue != null) {
                value = value + ":" + oldValue;
            }
            System.setProperty(javax.naming.Context.URL_PKG_PREFIXES, value);
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("catalina.namingPrefix", value));
            }
            // 配置初始上下文工厂
            value = System.getProperty(javax.naming.Context.INITIAL_CONTEXT_FACTORY);
            if (value == null) {
                System.setProperty(javax.naming.Context.INITIAL_CONTEXT_FACTORY,
                    "org.apache.naming.java.javaURLContextFactory");
            } else {
                log.debug(sm.getString("catalina.initialContextFactory", value));
            }
        }
    }

    /**
     * 设置安全包访问保护
     */
    protected void setSecurityProtection() {
        SecurityConfig securityConfig = SecurityConfig.newInstance();
        securityConfig.setPackageDefinition();  // 设置包定义权限
        securityConfig.setPackageAccess();       // 设置包访问权限
    }

    /**
     * 生成代码加载器（用于动态生成的配置解析类）
     */
    protected void generateLoader() {
        String loaderClassName = "DigesterGeneratedCodeLoader";
        StringBuilder code = new StringBuilder();
        code.append("package ").append(generatedCodePackage).append(';').append(System.lineSeparator());
        code.append("public class ").append(loaderClassName);
        code.append(" implements org.apache.tomcat.util.digester.Digester.GeneratedCodeLoader {")
            .append(System.lineSeparator());
        code.append("public Object loadGeneratedCode(String className) {").append(System.lineSeparator());
        code.append("switch (className) {").append(System.lineSeparator());

        // 生成类加载逻辑（基于已生成的类列表）
        for (String generatedClassName : Digester.getGeneratedClasses()) {
            code.append("case \"").append(generatedClassName).append("\" : return new ").append(generatedClassName);
            code.append("();").append(System.lineSeparator());
        }

        code.append("default: return null; }").append(System.lineSeparator());
        code.append("}}").append(System.lineSeparator());

        // 写入加载器代码文件
        File loaderLocation = new File(generatedCodeLocation, generatedCodePackage);
        try (FileWriter writer = new FileWriter(new File(loaderLocation, loaderClassName + ".java"))) {
            writer.write(code.toString());
        } catch (IOException e) {
            log.debug(sm.getString("catalina.loaderWriteFail"), e);
        }
    }

    /**
     * 生成代码类头部（用于动态生成配置解析类）
     * @param digester Digester实例
     * @param start true表示生成启动相关代码
     */
    protected void generateClassHeader(Digester digester, boolean start) {
        StringBuilder code = digester.getGeneratedCode();
        code.append("package ").append(generatedCodePackage).append(';').append(System.lineSeparator());
        code.append("public class ServerXml");
        if (!start) {
            code.append("Stop");
        }
        code.append(" implements ");
        code.append(ServerXml.class.getName().replace('$', '.')).append(" {").append(System.lineSeparator());
        code.append("public void load(").append(Catalina.class.getName());
        code.append(' ').append(digester.toVariableName(this)).append(") throws Exception {")
            .append(System.lineSeparator());
    }

    /**
     * 生成代码类尾部（用于动态生成配置解析类）
     * @param digester Digester实例
     */
    protected void generateClassFooter(Digester digester) {
        StringBuilder code = digester.getGeneratedCode();
        code.append('}').append(System.lineSeparator());
        code.append('}').append(System.lineSeparator());
    }

    /**
     * 服务器配置解析接口（用于生成代码）
     */
    public interface ServerXml {
        void load(Catalina catalina) throws Exception;
    }

    // ------------------------------- 内部类定义 -------------------------------

    /**
     * 关闭钩子线程类，用于优雅关闭Tomcat
     */
    protected class CatalinaShutdownHook extends Thread {
        @Override
        public void run() {
            try {
                if (getServer() != null) {
                    Catalina.this.stop(); // 执行Tomcat关闭流程
                }
            } catch (Throwable ex) {
                ExceptionUtils.handleThrowable(ex);
                log.error(sm.getString("catalina.shutdownHookFail"), ex);
            } finally {
                // 关闭JULI日志系统（确保日志输出完成）
                LogManager logManager = LogManager.getLogManager();
                if (logManager instanceof ClassLoaderLogManager) {
                    ((ClassLoaderLogManager) logManager).shutdown();
                }
            }
        }
    }

    /** 日志实例，用于记录启动流程信息 */
    private static final Log log = LogFactory.getLog(Catalina.class);

    /**
     * 设置容器父类加载器的规则类
     * 用于在解析配置时设置容器的类加载器
     */
    final class SetParentClassLoaderRule extends Rule {
        SetParentClassLoaderRule(ClassLoader parentClassLoader) {
            this.parentClassLoader = parentClassLoader;
        }

        ClassLoader parentClassLoader;

        @Override
        public void begin(String namespace, String name, Attributes attributes) throws Exception {
            if (digester.getLogger().isTraceEnabled()) {
                digester.getLogger().trace("Setting parent class loader");
            }

            // 获取当前解析的容器实例并设置父类加载器
            Container top = (Container) digester.peek();
            top.setParentClassLoader(parentClassLoader);

            // 生成代码时记录此操作
            StringBuilder code = digester.getGeneratedCode();
            if (code != null) {
                code.append(digester.toVariableName(top)).append(".setParentClassLoader(");
                code.append(digester.toVariableName(Catalina.this)).append(".getParentClassLoader());");
                code.append(System.lineSeparator());
            }
        }
    }
}