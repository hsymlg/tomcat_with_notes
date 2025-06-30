/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;

/**
 * Wrapper接口表示Web应用部署描述符中的单个Servlet定义，是一个容器组件。
 * 它提供了拦截器机制来处理所有发往该Servlet的请求，并负责管理Servlet的生命周期（初始化和销毁）。
 * Wrapper的父容器通常是Context实现，表示该Servlet执行的Servlet上下文（即Web应用）。
 * 注意：Wrapper不允许有子容器，因此addChild()方法应抛出IllegalArgumentException。
 *
 * @author Craig R. McClanahan
 */
public interface Wrapper extends Container {

    /** 添加Wrapper映射时触发的容器事件 */
    String ADD_MAPPING_EVENT = "addMapping";

    /** 移除Wrapper映射时触发的容器事件 */
    String REMOVE_MAPPING_EVENT = "removeMapping";

    // ------------------------------------------------------------- 属性方法

    /**
     * 获取Servlet可用时间（从纪元开始的毫秒数）
     * - 若时间在未来：请求返回SC_SERVICE_UNAVAILABLE错误
     * - 若值为0：Servlet当前可用
     * - 若值为Long.MAX_VALUE：表示永久不可用
     *
     * @return Servlet可用时间戳
     */
    long getAvailable();

    /**
     * 设置Servlet可用时间（从纪元开始的毫秒数）
     * - 若时间在未来：请求返回SC_SERVICE_UNAVAILABLE错误
     * - 若值为Long.MAX_VALUE：表示永久不可用
     *
     * @param available 新的可用时间戳
     */
    void setAvailable(long available);

    /**
     * 获取Servlet的加载顺序值（负值表示首次调用时加载）
     *
     * @return 加载顺序值
     */
    int getLoadOnStartup();

    /**
     * 设置Servlet的加载顺序值（负值表示首次调用时加载）
     *
     * @param value 新的加载顺序值
     */
    void setLoadOnStartup(int value);

    /**
     * 获取Servlet的运行身份（run-as角色）
     *
     * @return run-as身份名称
     */
    String getRunAs();

    /**
     * 设置Servlet的运行身份（run-as角色）
     *
     * @param runAs 新的run-as身份名称
     */
    void setRunAs(String runAs);

    /**
     * 获取Servlet的完整类名
     *
     * @return Servlet类的全限定名
     */
    String getServletClass();

    /**
     * 设置Servlet的完整类名
     *
     * @param servletClass Servlet类的全限定名
     */
    void setServletClass(String servletClass);

    /**
     * 获取Servlet支持的HTTP方法列表（用于OPTIONS请求的Allow响应头）
     *
     * @return Servlet支持的方法数组
     * @throws ServletException 当无法加载目标Servlet时抛出
     */
    String[] getServletMethods() throws ServletException;

    /**
     * 判断Servlet是否当前不可用
     *
     * @return true表示不可用，false表示可用
     */
    boolean isUnavailable();

    /**
     * 获取关联的Servlet实例
     *
     * @return Servlet实例
     */
    Servlet getServlet();

    /**
     * 设置关联的Servlet实例
     *
     * @param servlet 要关联的Servlet实例
     */
    void setServlet(Servlet servlet);

    // --------------------------------------------------------- 公共方法

    /**
     * 添加Servlet初始化参数
     *
     * @param name 初始化参数名称
     * @param value 初始化参数值
     */
    void addInitParameter(String name, String value);

    /**
     * 添加Wrapper映射路径
     *
     * @param mapping 新的映射路径
     */
    void addMapping(String mapping);

    /**
     * 添加安全角色引用（Servlet内部角色与Web应用角色的映射）
     *
     * @param name Servlet内部使用的角色名
     * @param link Web应用中实际的角色名
     */
    void addSecurityReference(String name, String link);

    /**
     * 分配一个已初始化的Servlet实例（可直接调用service方法）
     * 可能返回之前已初始化的实例
     *
     * @return 新的Servlet实例
     * @throws ServletException Servlet初始化失败或加载错误时抛出
     */
    Servlet allocate() throws ServletException;

    /**
     * 减少Servlet实例的分配计数（返回实例到池）
     *
     * @param servlet 要返回的Servlet实例
     * @throws ServletException 释放实例时发生错误
     */
    void deallocate(Servlet servlet) throws ServletException;

    /**
     * 查找指定名称的初始化参数值
     *
     * @param name 初始化参数名称
     * @return 参数值（不存在时返回null）
     */
    String findInitParameter(String name);

    /**
     * 获取所有初始化参数的名称
     *
     * @return 初始化参数名称数组
     */
    String[] findInitParameters();

    /**
     * 获取Wrapper关联的所有映射路径
     *
     * @return 映射路径数组
     */
    String[] findMappings();

    /**
     * 查找指定安全角色引用对应的Web应用角色名
     *
     * @param name Servlet内部使用的角色名
     * @return Web应用中的角色名（不存在时返回null）
     */
    String findSecurityReference(String name);

    /**
     * 获取所有安全角色引用名称
     *
     * @return 安全角色引用名称数组
     */
    String[] findSecurityReferences();

    /**
     * 增加错误计数（用于监控）
     */
    void incrementErrorCount();

    /**
     * 加载并初始化Servlet实例（若尚未初始化）
     * 用于加载部署描述符中标记为启动时加载的Servlet
     *
     * @throws ServletException Servlet初始化失败或加载错误时抛出
     */
    void load() throws ServletException;

    /**
     * 移除指定的初始化参数
     *
     * @param name 要移除的初始化参数名称
     */
    void removeInitParameter(String name);

    /**
     * 移除Wrapper映射路径
     *
     * @param mapping 要移除的映射路径
     */
    void removeMapping(String mapping);

    /**
     * 移除指定的安全角色引用
     *
     * @param name 要移除的安全角色名
     */
    void removeSecurityReference(String name);

    /**
     * 处理UnavailableException，标记Servlet为不可用
     *
     * @param unavailable 不可用异常（null表示永久不可用）
     */
    void unavailable(UnavailableException unavailable);

    /**
     * 卸载所有已初始化的Servlet实例（调用每个实例的destroy方法）
     * 用于Servlet引擎关闭或类重新加载前
     *
     * @throws ServletException 卸载过程中发生错误
     */
    void unload() throws ServletException;

    /**
     * 获取Servlet的多部分配置
     *
     * @return MultipartConfigElement实例（未定义时返回null）
     */
    MultipartConfigElement getMultipartConfigElement();

    /**
     * 设置Servlet的多部分配置
     *
     * @param multipartConfig 多部分配置（null表示清除配置）
     */
    void setMultipartConfigElement(MultipartConfigElement multipartConfig);

    /**
     * 判断Servlet是否支持异步处理
     *
     * @return true表示支持异步处理
     */
    boolean isAsyncSupported();

    /**
     * 设置Servlet的异步处理支持状态
     *
     * @param asyncSupport 新的异步支持状态
     */
    void setAsyncSupported(boolean asyncSupport);

    /**
     * 判断Servlet是否启用
     *
     * @return true表示已启用
     */
    boolean isEnabled();

    /**
     * 设置Servlet的启用状态
     *
     * @param enabled 新的启用状态
     */
    void setEnabled(boolean enabled);

    /**
     * 判断Servlet是否可被ServletContainerInitializer覆盖
     *
     * @return true表示可覆盖
     */
    boolean isOverridable();

    /**
     * 设置Servlet的可覆盖状态
     *
     * @param overridable 新的可覆盖状态
     */
    void setOverridable(boolean overridable);
}