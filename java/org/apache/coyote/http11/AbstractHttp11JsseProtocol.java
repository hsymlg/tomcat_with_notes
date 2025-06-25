/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.coyote.http11;

import org.apache.tomcat.util.net.AbstractJsseEndpoint;
import org.apache.tomcat.util.net.openssl.OpenSSLImplementation;

/**
 * 支持JSSE（Java安全套接字扩展）的HTTP/1.1协议处理器抽象基类
 * 该类为HTTP/1.1协议处理提供SSL/TLS支持，是Http11NioProtocol等具体协议的父类
 */
public abstract class AbstractHttp11JsseProtocol<S> extends AbstractHttp11Protocol<S> {

    /**
     * 构造函数
     * @param endpoint 关联的JSSE端点实例，处理底层安全套接字操作
     */
    public AbstractHttp11JsseProtocol(AbstractJsseEndpoint<S,?> endpoint) {
        super(endpoint); // 调用父类构造函数
    }

    /**
     * 获取关联的JSSE端点（类型转换版本）
     * @return 关联的JSSE端点实例
     */
    @Override
    protected AbstractJsseEndpoint<S,?> getEndpoint() {
        // 强制类型转换为JSSE端点
        return (AbstractJsseEndpoint<S,?>) super.getEndpoint();
    }

    /**
     * 获取SSL实现的简短名称
     * @return SSL实现的简短名称（如"openssl"、"jsse"）
     */
    protected String getSslImplementationShortName() {
        // 判断SSL实现类名，返回对应的简短名称
        if (OpenSSLImplementation.class.getName().equals(getSslImplementationName())) {
            return "openssl"; // OpenSSL实现
        }
        if (getSslImplementationName() != null &&
            getSslImplementationName().endsWith(".panama.OpenSSLImplementation")) {
            return "opensslffm"; // Panama OpenSSL实现
        }
        return "jsse"; // 标准JSSE实现
    }

    /**
     * 获取SSL实现的完整类名
     * @return SSL实现类的完全限定名
     */
    public String getSslImplementationName() {
        return getEndpoint().getSslImplementationName(); // 从端点获取SSL实现类名
    }

    /**
     * 设置SSL实现的完整类名
     * @param s SSL实现类的完全限定名
     */
    public void setSslImplementationName(String s) {
        getEndpoint().setSslImplementationName(s); // 配置端点的SSL实现类
    }

    /**
     * 获取SNI（服务器名称指示）解析限制
     * @return SNI解析的最大字符数限制
     */
    public int getSniParseLimit() {
        return getEndpoint().getSniParseLimit(); // 从端点获取SNI解析限制
    }

    /**
     * 设置SNI（服务器名称指示）解析限制
     * @param sniParseLimit SNI解析的最大字符数限制
     */
    public void setSniParseLimit(int sniParseLimit) {
        getEndpoint().setSniParseLimit(sniParseLimit); // 配置端点的SNI解析限制
    }
}