/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.NetworkChannel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.apache.tomcat.util.net.openssl.ciphers.Cipher;

/**
 * 基于JSSE(Java Secure Socket Extension)的端点抽象实现
 * 提供SSL/TLS相关的网络端点功能，继承自AbstractEndpoint
 *
 * @param <S> 套接字包装器类型
 * @param <U> 底层套接字类型
 */
public abstract class AbstractJsseEndpoint<S, U> extends AbstractEndpoint<S, U> {

    // SSL实现名称（如"JSSE"）
    private String sslImplementationName = null;
    // SNI解析限制（字节数），防止过大的SNI请求导致内存问题
    private int sniParseLimit = 64 * 1024;

    // SSL实现实例，用于创建SSL上下文和工具类
    private SSLImplementation sslImplementation = null;

    /**
     * 获取SSL实现名称
     *
     * @return SSL实现名称
     */
    public String getSslImplementationName() {
        return sslImplementationName;
    }

    /**
     * 设置SSL实现名称
     *
     * @param s SSL实现名称（如"JSSE"）
     */
    public void setSslImplementationName(String s) {
        this.sslImplementationName = s;
    }

    /**
     * 获取SSL实现实例
     *
     * @return SSLImplementation实例
     */
    public SSLImplementation getSslImplementation() {
        return sslImplementation;
    }

    /**
     * 获取SNI解析限制
     *
     * @return SNI解析限制（字节数）
     */
    public int getSniParseLimit() {
        return sniParseLimit;
    }

    /**
     * 设置SNI解析限制
     *
     * @param sniParseLimit SNI解析限制（字节数）
     */
    public void setSniParseLimit(int sniParseLimit) {
        this.sniParseLimit = sniParseLimit;
    }

    /**
     * 初始化SSL相关配置
     * 初始化SSL实现并为每个SSL主机配置创建SSL上下文
     *
     * @throws Exception 初始化失败时抛出
     */
    protected void initialiseSsl() throws Exception {
        if (isSSLEnabled()) {
            // 获取SSL实现实例
            sslImplementation = SSLImplementation.getInstance(getSslImplementationName());

            // 为每个SSL主机配置创建SSL上下文
            for (SSLHostConfig sslHostConfig : sslHostConfigs.values()) {
                createSSLContext(sslHostConfig);
            }

            // 验证默认SSL主机配置是否存在
            if (sslHostConfigs.get(getDefaultSSLHostConfigName()) == null) {
                throw new IllegalArgumentException(
                    sm.getString("endpoint.noSslHostConfig", getDefaultSSLHostConfigName(), getName()));
            }
        }
    }

    /**
     * 创建SSL上下文
     * 为指定的SSL主机配置创建SSL上下文
     *
     * @param sslHostConfig SSL主机配置
     * @throws IllegalArgumentException 配置错误时抛出
     */
    @Override
    protected void createSSLContext(SSLHostConfig sslHostConfig) throws IllegalArgumentException {
        // 检查HTTP/2与证书验证模式的兼容性
        if (sslHostConfig.getCertificateVerification().isOptional() && negotiableProtocols.contains("h2")) {
            getLog().warn(sm.getString("sslHostConfig.certificateVerificationWithHttp2", sslHostConfig.getHostName()));
        }

        boolean firstCertificate = true;
        // 处理每个证书配置
        for (SSLHostConfigCertificate certificate : sslHostConfig.getCertificates(true)) {
            // 获取SSL工具类
            SSLUtil sslUtil = sslImplementation.getSSLUtil(certificate);
            if (firstCertificate) {
                firstCertificate = false;
                // 设置主机配置的启用协议和密码套件
                sslHostConfig.setEnabledProtocols(sslUtil.getEnabledProtocols());
                sslHostConfig.setEnabledCiphers(sslUtil.getEnabledCiphers());
            }

            // 获取证书的SSL上下文
            SSLContext sslContext = certificate.getSslContext();
            SSLContext sslContextGenerated = certificate.getSslContextGenerated();
            // 如果需要，生成SSL上下文
            if (sslContext == null || sslContext == sslContextGenerated) {
                try {
                    sslContext = sslUtil.createSSLContext(negotiableProtocols);
                } catch (Exception e) {
                    throw new IllegalArgumentException(e.getMessage(), e);
                }
                // 设置生成的SSL上下文
                certificate.setSslContextGenerated(sslContext);
            }

            // 记录证书信息
            logCertificate(certificate);
        }
    }

    /**
     * 创建SSLEngine
     * 根据SNI主机名和客户端请求创建SSLEngine
     *
     * @param sniHostName SNI主机名
     * @param clientRequestedCiphers 客户端请求的密码套件
     * @param clientRequestedApplicationProtocols 客户端请求的应用层协议
     * @return 配置好的SSLEngine
     */
    protected SSLEngine createSSLEngine(String sniHostName, List<Cipher> clientRequestedCiphers,
                                        List<String> clientRequestedApplicationProtocols) {
        // 获取匹配的SSL主机配置
        SSLHostConfig sslHostConfig = getSSLHostConfig(sniHostName);

        // 选择合适的证书
        SSLHostConfigCertificate certificate = selectCertificate(sslHostConfig, clientRequestedCiphers);

        // 获取证书的SSL上下文
        SSLContext sslContext = certificate.getSslContext();
        if (sslContext == null) {
            throw new IllegalStateException(sm.getString("endpoint.jsse.noSslContext", sniHostName));
        }

        // 创建SSLEngine并配置
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false); // 设置为服务器模式
        engine.setEnabledCipherSuites(sslHostConfig.getEnabledCiphers()); // 设置启用的密码套件
        engine.setEnabledProtocols(sslHostConfig.getEnabledProtocols()); // 设置启用的协议

        // 配置SSL参数
        SSLParameters sslParameters = engine.getSSLParameters();
        sslParameters.setUseCipherSuitesOrder(sslHostConfig.getHonorCipherOrder()); // 配置密码套件顺序策略

        // 协商应用层协议（如HTTP/2）
        if (clientRequestedApplicationProtocols != null && clientRequestedApplicationProtocols.size() > 0 &&
            negotiableProtocols.size() > 0) {
            List<String> commonProtocols = new ArrayList<>(negotiableProtocols);
            commonProtocols.retainAll(clientRequestedApplicationProtocols);
            if (commonProtocols.size() > 0) {
                sslParameters.setApplicationProtocols(commonProtocols.toArray(new String[0]));
            }
        }

        // 配置客户端证书验证策略
        switch (sslHostConfig.getCertificateVerification()) {
            case NONE:
                sslParameters.setNeedClientAuth(false);
                sslParameters.setWantClientAuth(false);
                break;
            case OPTIONAL:
            case OPTIONAL_NO_CA:
                sslParameters.setWantClientAuth(true);
                break;
            case REQUIRED:
                sslParameters.setNeedClientAuth(true);
                break;
        }

        // 应用SSL参数
        engine.setSSLParameters(sslParameters);

        return engine;
    }

    /**
     * 选择合适的证书
     * 根据客户端支持的密码套件选择匹配的证书
     *
     * @param sslHostConfig SSL主机配置
     * @param clientCiphers 客户端支持的密码套件
     * @return 选择的证书
     */
    private SSLHostConfigCertificate selectCertificate(SSLHostConfig sslHostConfig, List<Cipher> clientCiphers) {
        // 获取主机配置中的所有证书
        Set<SSLHostConfigCertificate> certificates = sslHostConfig.getCertificates(true);
        if (certificates.size() == 1) {
            return certificates.iterator().next(); // 只有一个证书时直接返回
        }

        // 获取服务器支持的密码套件
        LinkedHashSet<Cipher> serverCiphers = sslHostConfig.getCipherList();

        // 计算客户端和服务器的公共密码套件
        List<Cipher> candidateCiphers = new ArrayList<>();
        if (sslHostConfig.getHonorCipherOrder()) {
            candidateCiphers.addAll(serverCiphers);
            candidateCiphers.retainAll(clientCiphers);
        } else {
            candidateCiphers.addAll(clientCiphers);
            candidateCiphers.retainAll(serverCiphers);
        }

        // 查找与密码套件兼容的证书
        for (Cipher candidate : candidateCiphers) {
            for (SSLHostConfigCertificate certificate : certificates) {
                if (certificate.getType().isCompatibleWith(candidate.getAu())) {
                    return certificate;
                }
            }
        }

        // 没有找到匹配的证书时返回第一个证书（握手会因无匹配密码套件失败）
        return certificates.iterator().next();
    }

    /**
     * 取消绑定资源
     * 释放SSL相关资源
     *
     * @throws Exception 释放资源失败时抛出
     */
    @Override
    public void unbind() throws Exception {
        // 清除生成的SSL上下文
        for (SSLHostConfig sslHostConfig : sslHostConfigs.values()) {
            for (SSLHostConfigCertificate certificate : sslHostConfig.getCertificates()) {
                certificate.setSslContextGenerated(null);
            }
        }
    }

    /**
     * 获取服务器套接字通道（由子类实现）
     *
     * @return 服务器套接字通道
     */
    protected abstract NetworkChannel getServerSocket();

    /**
     * 获取本地地址
     *
     * @return 本地地址
     * @throws IOException 获取地址失败时抛出
     */
    @Override
    protected final InetSocketAddress getLocalAddress() throws IOException {
        NetworkChannel serverSock = getServerSocket();
        if (serverSock == null) {
            return null;
        }
        SocketAddress sa = serverSock.getLocalAddress();
        if (sa instanceof InetSocketAddress) {
            return (InetSocketAddress) sa;
        }
        return null;
    }
}