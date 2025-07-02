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
package org.apache.coyote;

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.apache.juli.logging.Log;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;

/**
 * 轻量级处理器抽象实现，作为所有处理器实现的基础类
 * 涵盖从HTTP/AJP处理器到轻量级协议升级处理器等各种实现
 */
public abstract class AbstractProcessorLight implements Processor {

    // 存储待处理的调度任务集合，使用线程安全的CopyOnWriteArraySet
    private final Set<DispatchType> dispatches = new CopyOnWriteArraySet<>();


    /**
     * 处理套接字事件的主循环方法
     * 支持标准HTTP请求、异步请求、协议升级等多种处理模式
     *
     * @param socketWrapper 套接字包装对象，包含底层网络连接信息
     * @param status        待处理的套接字事件类型
     * @return 处理完成后的套接字状态
     * @throws IOException 发生I/O异常时抛出
     */
    @Override
    public SocketState process(SocketWrapperBase<?> socketWrapper, SocketEvent status) throws IOException {

        SocketState state = SocketState.CLOSED;  // 默认状态为关闭
        Iterator<DispatchType> dispatches = null;  // 待处理的调度任务迭代器

        // 主处理循环：处理所有待处理的调度任务和套接字事件
        do {
            if (dispatches != null) {
                // 处理已注册的调度任务
                DispatchType nextDispatch = dispatches.next();
                if (getLog().isTraceEnabled()) {
                    getLog().trace("Processing dispatch type: [" + nextDispatch + "]");
                }
                state = dispatch(nextDispatch.getSocketStatus());  // 执行调度任务

                // 如果所有调度任务处理完毕，检查是否有管道数据需要处理
                if (!dispatches.hasNext()) {
                    state = checkForPipelinedData(state, socketWrapper);
                }
            } else if (status == SocketEvent.DISCONNECT) {
                // 断开连接事件：不做处理，等待处理器回收
            } else if (isAsync() || isUpgrade() || state == SocketState.ASYNC_END) {
                // 异步请求、协议升级或异步结束状态：通过dispatch方法处理事件
                state = dispatch(status);
                state = checkForPipelinedData(state, socketWrapper);  // 检查管道数据
            } else if (status == SocketEvent.OPEN_WRITE) {
                // 写事件：可能是异步操作后的额外写事件，忽略并保持长连接状态
                state = SocketState.LONG;
            } else if (status == SocketEvent.OPEN_READ) {
                // 读事件：处理标准HTTP请求
                state = service(socketWrapper);
            } else if (status == SocketEvent.CONNECT_FAIL) {
                // 连接失败事件：记录访问日志
                logAccess(socketWrapper);
            } else {
                // 未知或不支持的事件类型：默认关闭套接字
                state = SocketState.CLOSED;
            }

            // 记录详细的状态转换信息（调试级别）
            if (getLog().isTraceEnabled()) {
                getLog().trace(
                    "Socket: [" + socketWrapper + "], Status in: [" + status + "], State out: [" + state + "]");
            }

            /*
             * 异步请求后处理逻辑：
             * 1. 仅在非关闭状态下执行异步后处理
             * 2. 避免在CLOSED状态下执行，防止状态被错误修改
             */
            if (isAsync() && state != SocketState.CLOSED) {
                state = asyncPostProcess();
                if (getLog().isTraceEnabled()) {
                    getLog().trace(
                        "Socket: [" + socketWrapper + "], State after async post processing: [" + state + "]");
                }
            }

            // 如果当前调度任务处理完毕，获取并清空下一批调度任务
            if (dispatches == null || !dispatches.hasNext()) {
                // 仅当有新的调度任务时返回非空迭代器
                dispatches = getIteratorAndClearDispatches();
            }
        } while (state == SocketState.ASYNC_END || dispatches != null && state != SocketState.CLOSED);

        return state;
    }


    /**
     * 检查并处理HTTP管道数据
     * 当处理器状态为OPEN时，表示可能存在管道中的后续请求，需要立即处理
     *
     * @param inState       当前处理器状态
     * @param socketWrapper 套接字包装对象
     * @return 处理后的新状态
     * @throws IOException 发生I/O异常时抛出
     */
    private SocketState checkForPipelinedData(SocketState inState, SocketWrapperBase<?> socketWrapper)
        throws IOException {
        if (inState == SocketState.OPEN) {
            // 状态为OPEN时，可能存在管道数据需要读取
            // 立即处理以避免处理器回收导致数据丢失
            return service(socketWrapper);
        } else {
            return inState;
        }
    }


    /**
     * 添加一个调度任务到待处理集合
     * 调度任务将在后续的处理循环中被执行
     *
     * @param dispatchType 待添加的调度任务类型
     */
    public void addDispatch(DispatchType dispatchType) {
        synchronized (dispatches) {
            dispatches.add(dispatchType);
        }
    }


    /**
     * 获取待处理调度任务的迭代器并清空集合
     * 注意：根据AbstractProtocol中的逻辑，只有当集合非空时才返回非空迭代器
     *
     * @return 调度任务迭代器（集合为空时返回null）
     */
    public Iterator<DispatchType> getIteratorAndClearDispatches() {
        Iterator<DispatchType> result;
        synchronized (dispatches) {
            // 同步操作确保迭代器生成和集合清空的原子性
            result = dispatches.iterator();
            if (result.hasNext()) {
                dispatches.clear();  // 清空集合
            } else {
                result = null;  // 集合为空时返回null
            }
        }
        return result;
    }


    /**
     * 清空所有待处理的调度任务
     */
    protected void clearDispatches() {
        synchronized (dispatches) {
            dispatches.clear();
        }
    }


    /**
     * 记录连接失败的访问日志
     * 默认实现为空，具体实现由子类提供
     *
     * @param socketWrapper 连接相关的套接字包装对象
     * @throws IOException 发生I/O异常时抛出
     */
    protected void logAccess(SocketWrapperBase<?> socketWrapper) throws IOException {
        // 默认不执行任何操作，由子类根据需要实现
    }


    /**
     * 处理标准HTTP请求的抽象方法
     * 负责处理新请求以及部分读取的HTTP请求行或头部
     * 支持HTTP请求的管道化处理
     *
     * @param socketWrapper 待处理的套接字包装对象
     * @return 处理后的套接字状态
     * @throws IOException 发生I/O异常时抛出
     */
    protected abstract SocketState service(SocketWrapperBase<?> socketWrapper) throws IOException;

    /**
     * 处理非标准HTTP模式的请求
     * 包括Servlet 3.0异步请求和HTTP协议升级连接等场景
     * 这些请求通常从HTTP请求开始，然后转换为其他模式
     *
     * @param status 待处理的套接字事件
     * @return 处理后的套接字状态
     * @throws IOException 发生I/O异常时抛出
     */
    protected abstract SocketState dispatch(SocketEvent status) throws IOException;

    /**
     * 调用异步状态机的后处理方法
     * 处理异步操作完成后的清理和状态转换工作
     *
     * @return 处理后的套接字状态
     */
    protected abstract SocketState asyncPostProcess();

    /**
     * 获取与当前处理器类型关联的日志记录器
     *
     * @return 日志记录器实例
     */
    protected abstract Log getLog();
}