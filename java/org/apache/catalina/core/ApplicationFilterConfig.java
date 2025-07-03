/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina.core;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.management.ObjectName;
import javax.naming.NamingException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityUtil;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.log.SystemLogHandler;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.modeler.Util;
import org.apache.tomcat.util.res.StringManager;

/**
 * FilterConfig接口的具体实现类（管理Web应用过滤器的配置信息）
 *
 * 核心职责：
 * 1. 提供过滤器的初始化参数访问
 * 2. 管理过滤器实例的生命周期（创建、初始化、销毁）
 * 3. 暴露过滤器信息到JMX管理接口
 * 4. 实现序列化以支持容器重启时的状态恢复
 *
 * @author Craig R. McClanahan （框架核心开发者）
 */
public final class ApplicationFilterConfig implements FilterConfig, Serializable {

    // 序列化版本号（确保不同版本间的兼容性）
    private static final long serialVersionUID = 1L;

    // 字符串资源管理器（提供国际化错误信息）
    static final StringManager sm = StringManager.getManager(ApplicationFilterConfig.class);

    // 日志记录器（ transient 防止序列化时保存日志状态）
    private transient Log log = LogFactory.getLog(ApplicationFilterConfig.class);

    // 空字符串列表（用于空枚举的基础数据）
    private static final List<String> emptyString = Collections.emptyList();

    // ----------------------------------------------------------- 构造方法

    /**
     * 为指定的过滤器定义创建ApplicationFilterConfig实例
     *
     * @param context 所属的Context容器
     * @param filterDef 过滤器定义对象
     * @throws ClassCastException 过滤器类未实现Filter接口时抛出
     * @throws ClassNotFoundException 找不到过滤器类时抛出
     * @throws IllegalAccessException 无法访问过滤器类时抛出
     * @throws InstantiationException 实例化过滤器失败时抛出
     * @throws ServletException 过滤器init方法抛出异常时
     * @throws NamingException JNDI查找失败时抛出
     * @throws SecurityException 安全管理器阻止创建时抛出
     * @throws IllegalArgumentException 配置不合法时抛出
     */
    ApplicationFilterConfig(Context context, FilterDef filterDef)
        throws ClassCastException, ReflectiveOperationException, ServletException, NamingException,
        IllegalArgumentException, SecurityException {
        super();
        this.context = context; // 保存所属的Context容器引用
        this.filterDef = filterDef; // 保存过滤器定义对象

        // 若过滤器定义中没有已创建的过滤器实例，则创建新实例
        if (filterDef.getFilter() == null) {
            getFilter();
        } else {
            this.filter = filterDef.getFilter(); // 直接使用已存在的过滤器实例
            context.getInstanceManager().newInstance(filter); // 通知实例管理器初始化实例
            initFilter(); // 初始化过滤器
        }
    }

    // ----------------------------------------------------- 实例变量

    /** 所属的Context容器（与过滤器配置关联的上下文） */
    private final transient Context context;

    /** 过滤器实例（transient 防止序列化时保存实例状态） */
    private transient Filter filter = null;

    /** 过滤器定义对象（包含过滤器的配置信息） */
    private final FilterDef filterDef;

    /** JMX注册名称（用于将过滤器暴露到JMX管理接口） */
    private ObjectName oname;

    // --------------------------------------------------- FilterConfig接口实现

    /**
     * 获取过滤器名称（实现FilterConfig接口）
     * @return web.xml中配置的过滤器名称
     */
    @Override
    public String getFilterName() {
        return filterDef.getFilterName();
    }

    /**
     * 获取过滤器类名（非接口方法，提供内部使用）
     * @return 过滤器的完整类名
     */
    public String getFilterClass() {
        return filterDef.getFilterClass();
    }

    /**
     * 获取过滤器初始化参数（实现FilterConfig接口）
     * @param name 参数名称
     * @return 参数值，若无则返回null
     */
    @Override
    public String getInitParameter(String name) {
        Map<String, String> map = filterDef.getParameterMap(); // 获取过滤器参数映射
        if (map == null) {
            return null;
        }
        return map.get(name); // 返回指定名称的参数值
    }

    /**
     * 获取所有初始化参数名称（实现FilterConfig接口）
     * @return 初始化参数名称的枚举
     */
    @Override
    public Enumeration<String> getInitParameterNames() {
        Map<String, String> map = filterDef.getParameterMap(); // 获取过滤器参数映射
        if (map == null) {
            return Collections.enumeration(emptyString); // 无参数时返回空枚举
        }
        return Collections.enumeration(map.keySet()); // 返回参数名称枚举
    }

    /**
     * 获取ServletContext（实现FilterConfig接口）
     * @return 所属的ServletContext实例
     */
    @Override
    public ServletContext getServletContext() {
        return this.context.getServletContext(); // 通过Context获取ServletContext
    }

    /**
     * 对象字符串表示（用于调试和日志）
     * @return 包含过滤器名称和类名的字符串
     */
    @Override
    public String toString() {
        return "ApplicationFilterConfig[" + "name=" + filterDef.getFilterName() + ", filterClass=" +
            filterDef.getFilterClass() + ']';
    }

    // --------------------------------------------------------- 公共方法

    /**
     * 获取过滤器初始化参数的不可修改映射
     * @return 包含所有初始化参数的不可修改Map
     */
    public Map<String, String> getFilterInitParameterMap() {
        return Collections.unmodifiableMap(filterDef.getParameterMap()); // 返回不可修改的参数Map
    }

    // -------------------------------------------------------- 包级访问方法

    /**
     * 获取过滤器实例（核心方法，创建并初始化过滤器）
     * @return 过滤器实例
     * @throws ClassCastException 类转换失败时抛出
     * @throws ClassNotFoundException 类未找到时抛出
     * @throws IllegalAccessException 访问权限不足时抛出
     * @throws InstantiationException 实例化失败时抛出
     * @throws ServletException 初始化失败时抛出
     * @throws NamingException JNDI错误时抛出
     */
    Filter getFilter() throws ClassCastException, ReflectiveOperationException, ServletException, NamingException,
        IllegalArgumentException, SecurityException {
        // 若已有过滤器实例，直接返回
        if (this.filter != null) {
            return this.filter;
        }
        // 获取过滤器类名并通过实例管理器创建实例
        String filterClass = filterDef.getFilterClass();
        this.filter = (Filter) context.getInstanceManager().newInstance(filterClass);
        initFilter(); // 初始化过滤器
        return this.filter;
    }

    /**
     * 初始化过滤器实例（包含日志捕获和JMX注册）
     * @throws ServletException 初始化失败时抛出
     */
    private void initFilter() throws ServletException {
        // 若Context配置了吞入输出（如System.out），则捕获日志
        if (context instanceof StandardContext && context.getSwallowOutput()) {
            try {
                SystemLogHandler.startCapture(); // 开始捕获System.out/err
                filter.init(this); // 调用过滤器的init方法
            } finally {
                String capturedlog = SystemLogHandler.stopCapture(); // 停止捕获并获取日志
                if (capturedlog != null && !capturedlog.isEmpty()) {
                    getServletContext().log(capturedlog); // 将捕获的日志写入ServletContext
                }
            }
        } else {
            filter.init(this); // 直接调用过滤器的init方法
        }
        registerJMX(); // 注册JMX管理接口
    }

    /**
     * 获取过滤器定义对象（供外部访问配置信息）
     * @return 过滤器定义对象
     */
    FilterDef getFilterDef() {
        return this.filterDef;
    }

    /**
     * 释放过滤器资源（销毁过滤器实例）
     */
    void release() {
        unregisterJMX(); // 取消JMX注册

        if (this.filter != null) {
            try {
                // 安全模式下使用特权操作调用destroy方法
                if (Globals.IS_SECURITY_ENABLED) {
                    try {
                        SecurityUtil.doAsPrivilege("destroy", filter);
                    } finally {
                        SecurityUtil.remove(filter);
                    }
                } else {
                    filter.destroy(); // 非安全模式直接调用destroy
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t); // 处理Error类型异常
                context.getLogger().error(sm.getString("applicationFilterConfig.release", filterDef.getFilterName(),
                    filterDef.getFilterClass()), t); // 记录错误日志
            }
            // 若Context不忽略注解，调用实例管理器的销毁方法
            if (!context.getIgnoreAnnotations()) {
                try {
                    context.getInstanceManager().destroyInstance(this.filter);
                } catch (Exception e) {
                    Throwable t = ExceptionUtils.unwrapInvocationTargetException(e);
                    ExceptionUtils.handleThrowable(t);
                    context.getLogger().error(sm.getString("applicationFilterConfig.preDestroy",
                        filterDef.getFilterName(), filterDef.getFilterClass()), t);
                }
            }
            this.filter = null; // 清空过滤器引用
        }
    }

    // -------------------------------------------------------- 私有方法

    /**
     * 注册JMX管理接口（将过滤器暴露到JMX）
     */
    private void registerJMX() {
        String parentName = context.getName();
        if (!parentName.startsWith("/")) {
            parentName = "/" + parentName; // 确保名称以斜杠开头
        }

        String hostName = context.getParent().getName();
        hostName = (hostName == null) ? "DEFAULT" : hostName; // 获取主机名

        // 获取引擎名称作为JMX domain
        String domain = context.getParent().getParent().getName();

        String webMod = "//" + hostName + parentName; // 构建Web模块标识
        String onameStr;
        String filterName = filterDef.getFilterName();
        // 若过滤器名称需要转义，使用ObjectName.quote处理
        if (Util.objectNameValueNeedsQuote(filterName)) {
            filterName = ObjectName.quote(filterName);
        }

        // 根据Context类型构建不同的JMX ObjectName
        if (context instanceof StandardContext) {
            StandardContext standardContext = (StandardContext) context;
            onameStr = domain + ":j2eeType=Filter,WebModule=" + webMod + ",name=" + filterName + ",J2EEApplication=" +
                standardContext.getJ2EEApplication() + ",J2EEServer=" + standardContext.getJ2EEServer();
        } else {
            onameStr = domain + ":j2eeType=Filter,name=" + filterName + ",WebModule=" + webMod;
        }

        try {
            oname = new ObjectName(onameStr); // 创建ObjectName
            Registry.getRegistry(null).registerComponent(this, oname, null); // 注册JMX组件
        } catch (Exception ex) {
            log.warn(sm.getString("applicationFilterConfig.jmxRegisterFail", getFilterClass(), getFilterName()), ex);
        }
    }

    /**
     * 取消JMX注册（移除过滤器的JMX管理接口）
     */
    private void unregisterJMX() {
        if (oname != null) {
            try {
                Registry.getRegistry(null).unregisterComponent(oname); // 取消注册
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("applicationFilterConfig.jmxUnregister", getFilterClass(), getFilterName()));
                }
            } catch (Exception ex) {
                log.warn(sm.getString("applicationFilterConfig.jmxUnregisterFail", getFilterClass(), getFilterName()), ex);
            }
        }
    }

    /**
     * 反序列化时的特殊处理（重新初始化日志记录器）
     * @param ois 输入流
     * @throws ClassNotFoundException 类未找到时抛出
     * @throws IOException I/O错误时抛出
     */
    private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
        ois.defaultReadObject(); // 执行默认反序列化
        log = LogFactory.getLog(ApplicationFilterConfig.class); // 重新初始化日志记录器
    }
}