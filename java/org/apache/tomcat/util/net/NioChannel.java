/*
 * 版权声明：Apache Software Foundation (ASF) 授权许可
 * 许可证信息：遵循 Apache License, Version 2.0
 * 说明：允许在遵守许可证的前提下使用、分发本软件
 */
package org.apache.tomcat.util.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.channels.SocketChannel;

import org.apache.tomcat.util.net.NioEndpoint.NioSocketWrapper;
import org.apache.tomcat.util.res.StringManager;

/**
 * SocketChannel的包装基类，由NIO端点使用
 * 该类封装了SocketChannel并提供统一接口，使SSL和非SSL通道的逻辑保持一致
 * 避免为不同类型的通道编写特殊处理逻辑
 */
public class NioChannel implements ByteChannel, ScatteringByteChannel, GatheringByteChannel {

    // 字符串资源管理器，用于获取国际化提示信息
    protected static final StringManager sm = StringManager.getManager(NioChannel.class);

    // 空ByteBuffer实例，用于优化空数据传输场景
    protected static final ByteBuffer emptyBuf = ByteBuffer.allocate(0);

    // 套接字缓冲区处理器，负责管理读写缓冲区
    protected final SocketBufferHandler bufHandler;
    // 封装的底层SocketChannel实例
    protected SocketChannel sc = null;
    // 关联的NioSocketWrapper实例，包含连接的元数据
    protected NioSocketWrapper socketWrapper = null;

    /**
     * 构造函数
     * @param bufHandler 套接字缓冲区处理器
     */
    public NioChannel(SocketBufferHandler bufHandler) {
        this.bufHandler = bufHandler;
    }

    /**
     * 重置通道状态
     * @param channel 要重置的SocketChannel
     * @param socketWrapper 关联的NioSocketWrapper
     * @throws IOException 重置通道时发生I/O错误
     */
    public void reset(SocketChannel channel, NioSocketWrapper socketWrapper) throws IOException {
        this.sc = channel;
        this.socketWrapper = socketWrapper;
        bufHandler.reset(); // 重置缓冲区处理器
    }

    /**
     * 获取关联的NioSocketWrapper
     * @return NioSocketWrapper实例
     */
    NioSocketWrapper getSocketWrapper() {
        return socketWrapper;
    }

    /**
     * 释放通道占用的内存资源
     */
    public void free() {
        bufHandler.free(); // 释放缓冲区资源
    }

    /**
     * 关闭通道
     * @throws IOException 关闭通道时发生I/O错误
     */
    @Override
    public void close() throws IOException {
        sc.close(); // 关闭底层SocketChannel
    }

    /**
     * 关闭连接
     * @param force 是否强制关闭底层套接字
     * @throws IOException 关闭通道时发生I/O错误
     */
    public void close(boolean force) throws IOException {
        if (isOpen() || force) {
            close(); // 调用标准关闭方法
        }
    }

    /**
     * 检查通道是否打开
     * @return true如果通道处于打开状态，否则false
     */
    @Override
    public boolean isOpen() {
        return sc.isOpen(); // 检查底层SocketChannel是否打开
    }

    /**
     * 从缓冲区写入数据到通道
     * @param src 包含要写入数据的缓冲区
     * @return 写入的字节数，可能为0
     * @throws IOException 发生I/O错误
     */
    @Override
    public int write(ByteBuffer src) throws IOException {
        checkInterruptStatus(); // 检查线程中断状态
        if (!src.hasRemaining()) {
            return 0; // 缓冲区无剩余数据时返回0
        }
        return sc.write(src); // 调用底层SocketChannel的write方法
    }

    /**
     * 从缓冲区数组写入数据到通道
     * @param srcs 缓冲区数组
     * @return 写入的总字节数
     * @throws IOException 发生I/O错误
     */
    @Override
    public long write(ByteBuffer[] srcs) throws IOException {
        return write(srcs, 0, srcs.length); // 调用带偏移量的写入方法
    }

    /**
     * 从缓冲区数组的指定范围写入数据到通道
     * @param srcs 缓冲区数组
     * @param offset 起始偏移量
     * @param length 要写入的缓冲区数量
     * @return 写入的总字节数
     * @throws IOException 发生I/O错误
     */
    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        checkInterruptStatus(); // 检查线程中断状态
        return sc.write(srcs, offset, length); // 调用底层SocketChannel的write方法
    }

    /**
     * 从通道读取数据到缓冲区
     * @param dst 用于存储读取数据的缓冲区
     * @return 读取的字节数，-1表示已到达流末尾
     * @throws IOException 发生I/O错误
     */
    @Override
    public int read(ByteBuffer dst) throws IOException {
        return sc.read(dst); // 调用底层SocketChannel的read方法
    }

    /**
     * 从通道读取数据到缓冲区数组
     * @param dsts 缓冲区数组
     * @return 读取的总字节数
     * @throws IOException 发生I/O错误
     */
    @Override
    public long read(ByteBuffer[] dsts) throws IOException {
        return read(dsts, 0, dsts.length); // 调用带偏移量的读取方法
    }

    /**
     * 从通道读取数据到缓冲区数组的指定范围
     * @param dsts 缓冲区数组
     * @param offset 起始偏移量
     * @param length 要读取的缓冲区数量
     * @return 读取的总字节数
     * @throws IOException 发生I/O错误
     */
    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        return sc.read(dsts, offset, length); // 调用底层SocketChannel的read方法
    }

    /**
     * 获取套接字缓冲区处理器
     * @return SocketBufferHandler实例
     */
    public SocketBufferHandler getBufHandler() {
        return bufHandler;
    }

    /**
     * 获取底层的SocketChannel
     * @return SocketChannel实例
     */
    public SocketChannel getIOChannel() {
        return sc;
    }

    /**
     * 检查通道是否正在关闭
     * @return false（非安全通道始终返回false）
     */
    public boolean isClosing() {
        return false;
    }

    /**
     * 检查握手是否完成
     * @return true（非安全通道始终返回true）
     */
    public boolean isHandshakeComplete() {
        return true;
    }

    /**
     * 执行SSL握手（非安全通道的无操作方法）
     * @param read 是否需要读取操作（非安全通道忽略）
     * @param write 是否需要写入操作（非安全通道忽略）
     * @return 始终返回0
     * @throws IOException 非安全通道不会抛出异常
     */
    public int handshake(boolean read, boolean write) throws IOException {
        return 0;
    }

    /**
     * 返回通道的字符串表示
     * @return 包含类名和底层SocketChannel的字符串
     */
    @Override
    public String toString() {
        return super.toString() + ":" + sc;
    }

    /**
     * 获取 outbound 缓冲区中剩余的字节数
     * @return 始终返回0（非安全通道无outbound缓冲区）
     */
    public int getOutboundRemaining() {
        return 0;
    }

    /**
     * 刷新outbound缓冲区（非安全通道的无操作方法）
     * @return 始终返回false
     * @throws IOException 非安全通道不会抛出异常
     */
    public boolean flushOutbound() throws IOException {
        return false;
    }

    /**
     * 检查线程中断状态
     * 在执行写入操作前调用，避免中断导致的异常
     * @throws IOException 如果当前线程已中断
     */
    protected void checkInterruptStatus() throws IOException {
        if (Thread.interrupted()) {
            throw new IOException(sm.getString("channel.nio.interrupted"));
        }
    }

    // 应用层读取缓冲区处理器
    private ApplicationBufferHandler appReadBufHandler;

    /**
     * 设置应用层读取缓冲区处理器
     * @param handler 应用层缓冲区处理器
     */
    public void setAppReadBufHandler(ApplicationBufferHandler handler) {
        this.appReadBufHandler = handler;
    }

    /**
     * 获取应用层读取缓冲区处理器
     * @return 应用层缓冲区处理器
     */
    protected ApplicationBufferHandler getAppReadBufHandler() {
        return appReadBufHandler;
    }

    /**
     * 已关闭的NioChannel静态实例
     * 用于表示已关闭的通道，避免重复创建新实例
     */
    static final NioChannel CLOSED_NIO_CHANNEL = new NioChannel(SocketBufferHandler.EMPTY) {
        @Override
        public void close() throws IOException {
            // 空实现，不执行关闭操作
        }

        @Override
        public boolean isOpen() {
            return false; // 始终返回关闭状态
        }

        @Override
        public void reset(SocketChannel channel, NioSocketWrapper socketWrapper) throws IOException {
            // 空实现，不执行重置操作
        }

        @Override
        public void free() {
            // 空实现，不执行资源释放
        }

        @Override
        protected ApplicationBufferHandler getAppReadBufHandler() {
            return ApplicationBufferHandler.EMPTY; // 返回空缓冲区处理器
        }

        @Override
        public void setAppReadBufHandler(ApplicationBufferHandler handler) {
            // 空实现，不执行设置操作
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return -1; // 读取操作返回-1（流末尾）
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return -1L; // 读取操作返回-1（流末尾）
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            checkInterruptStatus();
            throw new ClosedChannelException(); // 写入操作抛出通道已关闭异常
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            throw new ClosedChannelException(); // 写入操作抛出通道已关闭异常
        }

        @Override
        public String toString() {
            return "Closed NioChannel"; // 返回已关闭通道的字符串表示
        }
    };
}