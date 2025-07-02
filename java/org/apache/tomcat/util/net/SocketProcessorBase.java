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

import java.util.Objects;
import java.util.concurrent.locks.Lock;

/**
 * 套接字处理器基类，实现Runnable接口用于线程池执行
 * 提供套接字事件处理的基本框架和线程安全机制
 */
public abstract class SocketProcessorBase<S> implements Runnable {

    // 套接字包装器，封装底层套接字和相关操作
    protected SocketWrapperBase<S> socketWrapper;
    // 待处理的套接字事件（如读、写、连接等）
    protected SocketEvent event;

    /**
     * 构造方法，初始化套接字包装器和事件
     *
     * @param socketWrapper 套接字包装器实例
     * @param event         套接字事件类型
     */
    public SocketProcessorBase(SocketWrapperBase<S> socketWrapper, SocketEvent event) {
        reset(socketWrapper, event);
    }


    /**
     * 重置处理器状态，允许重用处理器实例
     *
     * @param socketWrapper 新的套接字包装器
     * @param event         新的套接字事件
     */
    public void reset(SocketWrapperBase<S> socketWrapper, SocketEvent event) {
        // 校验事件参数非空（避免NPE）
        Objects.requireNonNull(event);
        this.socketWrapper = socketWrapper;
        this.event = event;
    }


    /**
     * 线程执行入口方法（实现Runnable接口）
     * 包含线程安全的锁机制和套接字状态检查
     */
    @Override
    public final void run() {
        // 获取套接字包装器的锁对象（保证线程安全）
        Lock lock = socketWrapper.getLock();
        lock.lock();  // 加锁
        try {
            // 检查套接字是否已关闭（避免处理无效连接）
            if (socketWrapper.isClosed()) {
                return;
            }
            // 执行具体的处理逻辑（由子类实现）
            doRun();
        } finally {
            lock.unlock();  // 释放锁（确保最终执行）
        }
    }


    /**
     * 子类必须实现的具体处理逻辑方法
     * 在锁保护的代码块中被调用
     */
    protected abstract void doRun();
}