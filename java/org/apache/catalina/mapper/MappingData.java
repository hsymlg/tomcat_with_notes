/*
 * 版权声明：本类由Apache软件基金会（ASF）授权，采用Apache License 2.0协议
 * 许可说明：未经许可不得使用，如需使用需遵守许可证中的条款
 * 版权信息：贡献者版权协议通过NOTICE文件分发，具体版权归属见该文件
 */
package org.apache.catalina.mapper;

import jakarta.servlet.http.MappingMatch;

import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Wrapper;
import org.apache.tomcat.util.buf.MessageBytes;

/**
 * 请求映射数据容器
 *
 * 核心作用：
 * 1. 存储请求映射过程中解析出的各种组件引用
 * 2. 保存请求路径的解析结果和匹配类型
 * 3. 为请求路由提供上下文信息
 * 4. 支持数据回收复用，减少对象创建开销
 *
 * 设计特点：
 * - 所有字段公开，便于直接访问
 * - 提供recycle()方法实现对象池复用
 * - 使用MessageBytes存储路径信息，支持高效内存管理
 */
public class MappingData {

    // ------------------------- 请求映射的目标组件 -------------------------
    /** 匹配的虚拟主机(Host)容器 */
    public Host host = null;
    /** 匹配的Web应用(Context)容器 */
    public Context context = null;
    /** Context路径的斜杠数量（用于路径匹配算法） */
    public int contextSlashCount = 0;
    /** 匹配的Context数组（用于模糊匹配场景） */
    public Context[] contexts = null;
    /** 匹配的Servlet包装器(Wrapper) */
    public Wrapper wrapper = null;
    /** 是否匹配JSP通配符（如*.jsp） */
    public boolean jspWildCard = false;

    // ------------------------- 路径解析结果 -------------------------
    /** 原始请求路径（未处理） */
    public final MessageBytes requestPath = MessageBytes.newInstance();
    /** Servlet映射路径（Wrapper路径） */
    public final MessageBytes wrapperPath = MessageBytes.newInstance();
    /** 路径信息（PathInfo部分） */
    public final MessageBytes pathInfo = MessageBytes.newInstance();
    /** 重定向路径（用于重定向逻辑） */
    public final MessageBytes redirectPath = MessageBytes.newInstance();

    // ------------------------- Servlet映射匹配类型 -------------------------
    /** jakarta.servlet.http.HttpServletMapping匹配类型 */
    public MappingMatch matchType = null;

    /**
     * 回收对象资源（重置所有字段）
     * 实现对象池模式，避免重复创建对象
     * 调用后可重新用于新的请求映射
     */
    public void recycle() {
        host = null;
        context = null;
        contextSlashCount = 0;
        contexts = null;
        wrapper = null;
        jspWildCard = false;
        requestPath.recycle();     // 回收MessageBytes资源
        wrapperPath.recycle();     // 重置路径解析结果
        pathInfo.recycle();        // 清空PathInfo信息
        redirectPath.recycle();    // 重置重定向路径
        matchType = null;          // 清除匹配类型
    }
}