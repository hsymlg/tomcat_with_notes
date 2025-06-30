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
package org.apache.catalina.core;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.catalina.Executor;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.ResizableExecutor;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.TaskThreadFactory;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;

/**
 * StandardThreadExecutor是Tomcat中用于管理线程池的核心组件，
 * 它实现了多种线程池管理接口，提供了可配置的线程池功能，
 * 用于处理Servlet请求等异步任务。
 */
public class StandardThreadExecutor extends LifecycleMBeanBase implements Executor, ExecutorService, ResizableExecutor {

    // 使用StringManager管理国际化资源
    protected static final StringManager sm = StringManager.getManager(StandardThreadExecutor.class);

    // ---------------------------------------------- Properties
    /**
     * 线程默认优先级，默认为Thread.NORM_PRIORITY(5)
     */
    protected int threadPriority = Thread.NORM_PRIORITY;

    /**
     * 是否以守护线程模式运行
     * 守护线程会在主线程退出时自动终止
     */
    protected boolean daemon = true;

    /**
     * 线程名称前缀，用于标识线程池创建的线程
     */
    protected String namePrefix = "tomcat-exec-";

    /**
     * 线程池最大线程数
     * 当任务数量超过此值时，新任务将被放入队列或拒绝
     */
    protected int maxThreads = 200;

    /**
     * 线程池最小空闲线程数
     * 即使没有任务，线程池也会保持这些线程处于活跃状态
     */
    protected int minSpareThreads = 25;

    /**
     * 线程最大空闲时间(毫秒)
     * 超过此时间的空闲线程将被回收，除非线程数小于minSpareThreads
     */
    protected int maxIdleTime = 60000;

    /**
     * 实际执行任务的线程池执行器
     */
    protected ThreadPoolExecutor executor = null;

    /**
     * 线程池名称，用于标识不同的线程池实例
     */
    protected String name;

    /**
     * 任务队列最大容量
     * 当线程池已满时，新任务将被放入此队列
     */
    protected int maxQueueSize = Integer.MAX_VALUE;

    /**
     * 线程更新延迟时间(毫秒)
     * 用于在上下文停止后，控制线程的逐步更新，避免同时销毁所有线程
     */
    protected long threadRenewalDelay = org.apache.tomcat.util.threads.Constants.DEFAULT_THREAD_RENEWAL_DELAY;

    // 任务队列实现
    private TaskQueue taskqueue = null;

    // ---------------------------------------------- Constructors
    /**
     * 默认构造函数，供Digester解析配置文件时使用
     */
    public StandardThreadExecutor() {
        // empty constructor for the digester
    }


    // ---------------------------------------------- Public Methods

    /**
     * 启动线程池组件
     * 实现Lifecycle接口的startInternal方法
     *
     * @exception LifecycleException 如果启动过程中发生致命错误
     */
    @Override
    protected void startInternal() throws LifecycleException {

        // 创建任务队列，指定最大容量
        taskqueue = new TaskQueue(maxQueueSize);
        // 创建线程工厂，设置线程名称前缀、是否为守护线程和线程优先级
        TaskThreadFactory tf = new TaskThreadFactory(namePrefix, daemon, getThreadPriority());
        // 创建线程池执行器，配置核心线程数、最大线程数、空闲线程超时时间等参数
        executor = new ThreadPoolExecutor(getMinSpareThreads(), getMaxThreads(), maxIdleTime, TimeUnit.MILLISECONDS,
            taskqueue, tf);
        // 设置线程更新延迟时间
        executor.setThreadRenewalDelay(threadRenewalDelay);
        // 将任务队列与线程池执行器关联
        taskqueue.setParent(executor);

        // 更新生命周期状态为STARTING
        setState(LifecycleState.STARTING);
    }


    /**
     * 停止线程池组件
     * 实现Lifecycle接口的stopInternal方法
     *
     * @exception LifecycleException 如果停止过程中发生致命错误
     */
    @Override
    protected void stopInternal() throws LifecycleException {

        // 更新生命周期状态为STOPPING
        setState(LifecycleState.STOPPING);
        // 关闭线程池执行器并立即终止所有正在执行的任务
        if (executor != null) {
            executor.shutdownNow();
        }
        // 释放资源引用
        executor = null;
        taskqueue = null;
    }


    /**
     * 执行一个Runnable任务
     * 实现Executor接口的execute方法
     *
     * @param command 要执行的任务
     * @throws IllegalStateException 如果线程池未启动
     */
    @Override
    public void execute(Runnable command) {
        if (executor != null) {
            // 委托给实际的线程池执行器执行任务
            // 注意：由于使用TaskQueue，任何RejectedExecutionException将由ThreadPoolExecutor处理
            executor.execute(command);
        } else {
            // 线程池未启动时抛出异常
            throw new IllegalStateException(sm.getString("standardThreadExecutor.notStarted"));
        }
    }

    /**
     * 通知线程池某个上下文正在停止
     * 用于触发线程池中的线程更新操作
     */
    public void contextStopping() {
        if (executor != null) {
            executor.contextStopping();
        }
    }

    // 以下是属性的getter和setter方法

    public int getThreadPriority() {
        return threadPriority;
    }

    public boolean isDaemon() {
        return daemon;
    }

    public String getNamePrefix() {
        return namePrefix;
    }

    public int getMaxIdleTime() {
        return maxIdleTime;
    }

    @Override
    public int getMaxThreads() {
        return maxThreads;
    }

    public int getMinSpareThreads() {
        return minSpareThreads;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * 设置线程优先级
     * 如果线程池已启动，会更新所有线程的优先级
     */
    public void setThreadPriority(int threadPriority) {
        this.threadPriority = threadPriority;
    }

    public void setDaemon(boolean daemon) {
        this.daemon = daemon;
    }

    public void setNamePrefix(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    /**
     * 设置线程最大空闲时间
     * 如果线程池已启动，会同步更新线程池的keepAliveTime参数
     */
    public void setMaxIdleTime(int maxIdleTime) {
        this.maxIdleTime = maxIdleTime;
        if (executor != null) {
            executor.setKeepAliveTime(maxIdleTime, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 设置线程池最大线程数
     * 如果线程池已启动，会动态调整线程池的最大容量
     */
    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
        if (executor != null) {
            executor.setMaximumPoolSize(maxThreads);
        }
    }

    /**
     * 设置线程池最小空闲线程数
     * 如果线程池已启动，会动态调整线程池的核心线程数
     */
    public void setMinSpareThreads(int minSpareThreads) {
        this.minSpareThreads = minSpareThreads;
        if (executor != null) {
            executor.setCorePoolSize(minSpareThreads);
        }
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setMaxQueueSize(int size) {
        this.maxQueueSize = size;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public long getThreadRenewalDelay() {
        return threadRenewalDelay;
    }

    public void setThreadRenewalDelay(long threadRenewalDelay) {
        this.threadRenewalDelay = threadRenewalDelay;
        if (executor != null) {
            executor.setThreadRenewalDelay(threadRenewalDelay);
        }
    }

    // 以下是获取线程池统计信息的方法

    /**
     * 获取当前活跃线程数
     */
    @Override
    public int getActiveCount() {
        return (executor != null) ? executor.getActiveCount() : 0;
    }

    /**
     * 获取已完成的任务总数
     */
    public long getCompletedTaskCount() {
        return (executor != null) ? executor.getCompletedTaskCount() : 0;
    }

    /**
     * 获取线程池核心线程数
     */
    public int getCorePoolSize() {
        return (executor != null) ? executor.getCorePoolSize() : 0;
    }

    /**
     * 获取线程池历史最大线程数
     */
    public int getLargestPoolSize() {
        return (executor != null) ? executor.getLargestPoolSize() : 0;
    }

    /**
     * 获取线程池当前线程总数
     */
    @Override
    public int getPoolSize() {
        return (executor != null) ? executor.getPoolSize() : 0;
    }

    /**
     * 获取任务队列当前大小
     */
    public int getQueueSize() {
        return (executor != null) ? executor.getQueue().size() : -1;
    }


    /**
     * 调整线程池大小
     * 实现ResizableExecutor接口的方法
     *
     * @param corePoolSize    新的核心线程数
     * @param maximumPoolSize 新的最大线程数
     * @return 调整是否成功
     */
    @Override
    public boolean resizePool(int corePoolSize, int maximumPoolSize) {
        if (executor == null) {
            return false;
        }

        // 动态调整线程池大小
        executor.setCorePoolSize(corePoolSize);
        executor.setMaximumPoolSize(maximumPoolSize);
        return true;
    }


    /**
     * 调整任务队列大小
     * 本实现不支持调整队列大小，始终返回false
     */
    @Override
    public boolean resizeQueue(int capacity) {
        return false;
    }


    /**
     * 获取MBean域名
     * 由于无法导航到Engine，返回null，需要外部设置域名
     */
    @Override
    protected String getDomainInternal() {
        return null;
    }

    /**
     * 获取MBean对象名称的键属性
     */
    @Override
    protected String getObjectNameKeyProperties() {
        return "type=Executor,name=" + getName();
    }


    // 以下是实现ExecutorService接口的方法
    // 这些方法主要委托给内部的executor执行，或根据生命周期状态进行相应处理

    @Override
    public void shutdown() {
        // 由Lifecycle控制关闭过程，不直接调用
    }


    @Override
    public List<Runnable> shutdownNow() {
        // 由Lifecycle控制关闭过程，不直接调用
        return Collections.emptyList();
    }


    @Override
    public boolean isShutdown() {
        if (executor != null) {
            return executor.isShutdown();
        } else {
            throw new IllegalStateException(sm.getString("standardThreadExecutor.notStarted"));
        }
    }


    @Override
    public boolean isTerminated() {
        if (executor != null) {
            return executor.isTerminated();
        } else {
            throw new IllegalStateException(sm.getString("standardThreadExecutor.notStarted"));
        }
    }


    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        // 不支持等待终止，直接返回false
        return false;
    }


    @Override
    public <T> Future<T> submit(Callable<T> task) {
        if (executor != null) {
            return executor.submit(task);
        } else {
            throw new IllegalStateException(sm.getString("standardThreadExecutor.notStarted"));
        }
    }


    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        if (executor != null) {
            return executor.submit(task, result);
        } else {
            throw new IllegalStateException(sm.getString("standardThreadExecutor.notStarted"));
        }
    }


    @Override
    public Future<?> submit(Runnable task) {
        if (executor != null) {
            return executor.submit(task);
        } else {
            throw new IllegalStateException(sm.getString("standardThreadExecutor.notStarted"));
        }
    }


    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        if (executor != null) {
            return executor.invokeAll(tasks);
        } else {
            throw new IllegalStateException(sm.getString("standardThreadExecutor.notStarted"));
        }
    }


    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException {
        if (executor != null) {
            return executor.invokeAll(tasks, timeout, unit);
        } else {
            throw new IllegalStateException(sm.getString("standardThreadExecutor.notStarted"));
        }
    }


    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        if (executor != null) {
            return executor.invokeAny(tasks);
        } else {
            throw new IllegalStateException(sm.getString("standardThreadExecutor.notStarted"));
        }
    }


    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (executor != null) {
            return executor.invokeAny(tasks, timeout, unit);
        } else {
            throw new IllegalStateException(sm.getString("standardThreadExecutor.notStarted"));
        }
    }
}