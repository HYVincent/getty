/*
 * Copyright 2019 The Getty Project
 *
 * The Getty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.gettyio.core.channel;

import com.gettyio.core.buffer.BufferWriter;
import com.gettyio.core.channel.config.BaseConfig;
import com.gettyio.core.channel.internal.ReadCompletionHandler;
import com.gettyio.core.channel.internal.WriteCompletionHandler;
import com.gettyio.core.function.Function;
import com.gettyio.core.handler.ssl.SslHandler;
import com.gettyio.core.handler.ssl.sslfacade.IHandshakeCompletedListener;
import com.gettyio.core.pipeline.ChannelPipeline;
import com.gettyio.core.buffer.allocator.ByteBufAllocator;
import com.gettyio.core.buffer.buffer.ByteBuf;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * AioChannel.java
 *
 * @description: aio通道
 * @author:gogym
 * @date:2020/4/8
 * @copyright: Copyright by gettyio.com
 */
public class AioChannel extends SocketChannel implements Function<BufferWriter, Void> {

    /**
     * 通信channel对象
     */
    protected AsynchronousSocketChannel channel;

    /**
     * 读缓冲。
     */
    protected ByteBuf readByteBuffer;
    /**
     * 写缓冲
     */
    protected ByteBuf writeByteBuffer;
    /**
     * 输出信号量
     */
    private final Semaphore semaphore = new Semaphore(1);

    /**
     * 读写回调
     */
    private final ReadCompletionHandler readCompletionHandler;
    private final WriteCompletionHandler writeCompletionHandler;

    /**
     * SSL服务
     */
    private SslHandler sslHandler;
    private IHandshakeCompletedListener handshakeCompletedListener;

    /**
     * 数据输出组建
     */
    protected BufferWriter bufferWriter;

    /**
     * 处理器链
     */
    private final ChannelPipeline channelPipeline;


    /**
     * @param channel                通道
     * @param config                 配置
     * @param readCompletionHandler  读回调
     * @param writeCompletionHandler 写回调
     * @param byteBufAllocator       内存池
     * @param channelPipeline        责任链
     */
    public AioChannel(AsynchronousSocketChannel channel, BaseConfig config, ReadCompletionHandler readCompletionHandler, WriteCompletionHandler writeCompletionHandler, ByteBufAllocator byteBufAllocator, ChannelPipeline channelPipeline) {
        this.channel = channel;
        this.readCompletionHandler = readCompletionHandler;
        this.writeCompletionHandler = writeCompletionHandler;
        this.config = config;
        this.byteBufAllocator = byteBufAllocator;
        this.channelPipeline = channelPipeline;

        try {
            //注意该方法可能抛异常
            channelPipeline.initChannel(this);
        } catch (Exception e) {
            try {
                channel.close();
            } catch (IOException e1) {
                logger.error(e1);
            }
            throw new RuntimeException("channelPipeline init exception", e);
        }

        //初始化数据输出类
        bufferWriter = new BufferWriter(byteBufAllocator, this, config.getBufferWriterQueueSize());

        //触发责任链
        try {
            invokePipeline(ChannelState.NEW_CHANNEL);
        } catch (Exception e) {
            logger.error(e);
        }
    }


    /**
     * 开始读取，很重要，只有调用该方法，才会开始监听消息读取
     */
    @Override
    public void starRead() {
        //主动关闭标志设置为false;
        initiateClose = false;
        continueRead();
        if (this.sslHandler != null) {
            //若开启了SSL，则需要握手
            this.sslHandler.getSslService().beginHandshake(handshakeCompletedListener);
        }
    }


    /**
     * 立即关闭会话
     */
    @Override
    public synchronized void close() {

        if (status == CHANNEL_STATUS_CLOSED) {
            return;
        }

        if (readByteBuffer != null && readByteBuffer.refCnt() > 0) {
            readByteBuffer.release();
        }

        if (writeByteBuffer != null && writeByteBuffer.refCnt() > 0) {
            writeByteBuffer.release();
        }

        if (channelFutureListener != null) {
            channelFutureListener.operationComplete(this);
        }

        try {
            if (!bufferWriter.isClosed()) {
                bufferWriter.close();
            }
            bufferWriter = null;
        } catch (IOException e) {
            logger.error(e);
        }

        try {
            channel.shutdownInput();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        try {
            channel.shutdownOutput();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        try {
            channel.close();
        } catch (IOException e) {
            logger.error("close channel exception", e);
        }
        //更新状态
        status = CHANNEL_STATUS_CLOSED;
        //触发责任链通知
        try {
            invokePipeline(ChannelState.CHANNEL_CLOSED);
        } catch (Exception e) {
            logger.error("close channel exception", e);
        }

        //最后需要清空责任链
        if (defaultChannelPipeline != null) {
            defaultChannelPipeline.clean();
            defaultChannelPipeline = null;
        }

    }

    /**
     * 主动关闭
     *
     * @param initiateClose
     */
    @Override
    public synchronized void close(boolean initiateClose) {
        this.initiateClose = initiateClose;
        close();
    }


    //--------------------------------------------------------------------------

    /**
     * 读取socket通道内的数据
     */
    protected void continueRead() {
        if (status == CHANNEL_STATUS_CLOSED) {
            return;
        }
        //初始化读缓冲区
        this.readByteBuffer = byteBufAllocator.ioBuffer(config.getReadBufferSize());
        ByteBuffer readByteBuf = readByteBuffer.nioBuffer(readByteBuffer.writerIndex(), readByteBuffer.writableBytes());
        channel.read(readByteBuf, this, readCompletionHandler);
    }


    /**
     * socket通道的读回调操作
     *
     * @param eof 状态回调标记
     */
    public void readFromChannel(boolean eof) {

        final ByteBuf readBuffer = this.readByteBuffer;
        if (readBuffer == null || readBuffer.refCnt() == 0) {
            return;
        }
        readBuffer.writerIndex(readBuffer.getNioBuffer().flip().remaining());
        //读取缓冲区数据到管道
        //读取缓冲区数据，输送到责任链
        while (readBuffer.isReadable()) {
            byte[] bytes = new byte[readBuffer.readableBytes()];
            readBuffer.readBytes(bytes, 0, bytes.length);
            try {
                readToPipeline(bytes);
            } catch (Exception e) {
                logger.error(e);
                try {
                    invokePipeline(ChannelState.INPUT_EXCEPTION);
                } catch (Exception e1) {
                    logger.error(e1);
                }
                close();
                return;
            }
        }
        if (eof) {
            close();
            return;
        }
        //触发读取完成，处理后续操作
        readCompleted(readBuffer);
    }

    /**
     * socket读取完成
     *
     * @param readBuffer 读取的缓冲区
     */
    public void readCompleted(ByteBuf readBuffer) {

        if (readBuffer == null || readBuffer.refCnt() == 0) {
            return;
        }
        readBuffer.release();
        //再次调用读取方法。循环监听socket通道数据的读取
        continueRead();
    }


//-------------------------------------------------------------------------------------------------

    /**
     * 写数据到责任链管道
     *
     * @param obj 写入的数据
     */
    @Override
    public boolean writeAndFlush(Object obj) {
        try {
            if (config.isFlowControl()) {
                if (bufferWriter.getCount() >= config.getHighWaterMark()) {
                    super.writeable = false;
                    return false;
                }
                if (bufferWriter.getCount() <= config.getLowWaterMark()) {
                    super.writeable = true;
                }
            }
            reverseInvokePipeline(ChannelState.CHANNEL_WRITE, obj);
        } catch (Exception e) {
            logger.error(e);
        }
        return true;
    }

    /**
     * 写到BufferWriter输出器，不经过责任链
     *
     * @param obj 写入的数组
     */
    @Override
    public void writeToChannel(Object obj) {
        try {
            bufferWriter.writeAndFlush((byte[]) obj);
        } catch (Exception e) {
            logger.error(e);
        }
    }

    /**
     * 继续写
     *
     * @param writeBuffer 写入的缓冲区
     */
    private void continueWrite(ByteBuffer writeBuffer) {
        channel.write(writeBuffer, 0L, TimeUnit.MILLISECONDS, this, writeCompletionHandler);
    }


    /**
     * 写操作完成回调
     * 需要同步控制
     */
    public void writeCompleted() {
        writeByteBuffer.readerIndex(writeByteBuffer.getNioBuffer().position());
        if (!writeByteBuffer.isReadable()) {
            //写完及时释放内存
            writeByteBuffer.release();
            //继续循环写出
            writeByteBuffer = bufferWriter.poll();
        }

        if (writeByteBuffer != null && writeByteBuffer.isReadable()) {
            //再次写
            continueWrite(writeByteBuffer.nioBuffer());
            //这里return是为了确保这个线程可以完全写完需要输出的数据。因此不释放信号量
            return;
        }
        //释放信号量
        semaphore.release();
        if (!keepAlive) {
            this.close();
        }
    }

    //-----------------------------------------------------------------------------------


    /**
     * 获取本地地址
     *
     * @return InetSocketAddress
     * @throws IOException 异常
     */
    @Override
    public final InetSocketAddress getLocalAddress() throws IOException {
        assertChannel();
        return (InetSocketAddress) channel.getLocalAddress();
    }

    /**
     * 获取远程地址
     *
     * @return InetSocketAddress
     * @throws IOException 异常
     */
    @Override
    public final InetSocketAddress getRemoteAddress() throws IOException {
        assertChannel();
        return (InetSocketAddress) channel.getRemoteAddress();
    }

    /**
     * 断言
     *
     * @throws IOException 异常
     */
    private void assertChannel() throws IOException {
        if (status == CHANNEL_STATUS_CLOSED || channel == null) {
            throw new IOException("channel is closed");
        }
    }


//--------------------------------------------------------------------------------------


    @Override
    public AsynchronousSocketChannel getAsynchronousSocketChannel() {
        return channel;
    }

    @Override
    public ChannelPipeline getChannelPipeline() {
        return channelPipeline;
    }

    /**
     * 设置SSLHandler
     *
     * @return AioChannel
     */
    @Override
    public void setSslHandler(SslHandler sslHandler) {
        this.sslHandler = sslHandler;
    }

    @Override
    public SslHandler getSslHandler() {
        return this.sslHandler;
    }


    @Override
    public void setSslHandshakeCompletedListener(IHandshakeCompletedListener handshakeCompletedListener) {
        this.handshakeCompletedListener = handshakeCompletedListener;
    }

    @Override
    public IHandshakeCompletedListener getSslHandshakeCompletedListener() {
        return this.handshakeCompletedListener;
    }

    @Override
    public Void apply(BufferWriter input) {

        //获取信息量
        if (semaphore.tryAcquire()) {
            this.writeByteBuffer = input.poll();
            if (null != writeByteBuffer && writeByteBuffer.isReadable()) {
                this.continueWrite(writeByteBuffer.nioBuffer());
            } else {
                semaphore.release();
            }
        }
        return null;
    }

}
