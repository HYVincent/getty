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
package com.gettyio.expansion.handler.timeout;

import com.gettyio.core.channel.AioChannel;
import com.gettyio.core.channel.NioChannel;
import com.gettyio.core.channel.SocketChannel;
import com.gettyio.core.channel.config.BaseConfig;
import com.gettyio.core.channel.starter.ConnectHandler;
import com.gettyio.core.handler.ssl.sslfacade.IHandshakeCompletedListener;
import com.gettyio.core.logging.InternalLogger;
import com.gettyio.core.logging.InternalLoggerFactory;
import com.gettyio.core.pipeline.in.ChannelInboundHandlerAdapter;
import com.gettyio.core.util.ThreadPool;
import com.gettyio.core.util.timer.HashedWheelTimer;
import com.gettyio.core.util.timer.Timeout;
import com.gettyio.core.util.timer.TimerTask;

import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * ReConnectHandler.java
 *
 * @description:异常断线重连
 * @author:gogym
 * @date:2020/4/9
 * @copyright: Copyright by gettyio.com
 */
public class ReConnectHandler extends ChannelInboundHandlerAdapter implements TimerTask {

    private final InternalLogger logger = InternalLoggerFactory.getInstance(ReConnectHandler.class);
    /**
     * 时间基数，重连时间会越来越长
     */
    private int attempts = 0;
    /**
     * 间隔阈值
     */
    private long threshold = 1000;
    /**
     * 创建一个定时器
     */
    private final HashedWheelTimer timer = new HashedWheelTimer();

    private SocketChannel channel;

    /**
     * 连接回调
     */
    private ConnectHandler connectHandler;

    /**
     * 默认3s
     */
    private int connectTimeout = 3000;

    public ReConnectHandler(ConnectHandler connectHandler) {
        this.connectHandler = connectHandler;
    }


    public ReConnectHandler(int threshold, ConnectHandler connectHandler) {
        this.threshold = threshold;
        this.connectHandler = connectHandler;
    }

    public ReConnectHandler(int threshold, int connectTimeout, ConnectHandler connectHandler) {
        this.connectTimeout = connectTimeout;
        this.connectHandler = connectHandler;
    }


    @Override
    public void channelAdded(SocketChannel socketChannel) throws Exception {
        this.channel = socketChannel;
        //重置时间基数
        attempts = 0;
        super.channelAdded(socketChannel);
    }


    @Override
    public void channelClosed(SocketChannel socketChannel) throws Exception {
        if (!socketChannel.isInitiateClose() && timer.workerState == HashedWheelTimer.WORKER_STATE_INIT) {
            //如果不是主动关闭，则发起重连
            reConnect(socketChannel);
        }
        super.channelClosed(socketChannel);
    }


    @Override
    public void exceptionCaught(SocketChannel socketChannel, Throwable cause) throws Exception {
        if (timer.workerState == HashedWheelTimer.WORKER_STATE_INIT) {
            reConnect(socketChannel);
        }
        super.exceptionCaught(socketChannel, cause);
    }

    @Override
    public void run(Timeout timeout) throws Exception {

        final BaseConfig clientConfig = channel.getConfig();
        final ThreadPool workerThreadPool = new ThreadPool(ThreadPool.FixedThread, 2);

        if (channel instanceof AioChannel) {
            AsynchronousSocketChannel socketChannel = AsynchronousSocketChannel.open(AsynchronousChannelGroup.withFixedThreadPool(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable target) {
                    return new Thread(target);
                }
            }));
            if (clientConfig.getSocketOptions() != null) {
                for (Map.Entry<SocketOption<Object>, Object> entry : clientConfig.getSocketOptions().entrySet()) {
                    socketChannel.setOption(entry.getKey(), entry.getValue());
                }
            }
            final AsynchronousSocketChannel finalSocketChannel = socketChannel;
            /**
             * 非阻塞连接
             */
            socketChannel.connect(new InetSocketAddress(clientConfig.getHost(), clientConfig.getPort()), socketChannel, new java.nio.channels.CompletionHandler<Void, AsynchronousSocketChannel>() {
                @Override
                public void completed(Void result, AsynchronousSocketChannel attachment) {
                    logger.info("connect aio server success");
                    //连接成功则构造AIOSession对象
                    channel = new AioChannel(finalSocketChannel, clientConfig, new com.gettyio.core.channel.internal.ReadCompletionHandler(workerThreadPool), new com.gettyio.core.channel.internal.WriteCompletionHandler(), channel.getByteBufAllocator(), channel.getChannelPipeline());

                    if (null != connectHandler) {
                        if (null != channel.getSslHandler()) {
                            channel.setSslHandshakeCompletedListener(new IHandshakeCompletedListener() {
                                @Override
                                public void onComplete() {
                                    logger.info("Ssl Handshake Completed");
                                    connectHandler.onCompleted(channel);
                                }
                            });
                        } else {
                            connectHandler.onCompleted(channel);
                        }
                    }
                    channel.starRead();
                }

                @Override
                public void failed(Throwable exc, AsynchronousSocketChannel attachment) {
                    logger.error("connect aio server  error", exc);
                    reConnect(channel);
                    if (null != connectHandler) {
                        connectHandler.onFailed(exc);
                    }
                }
            });
        } else if (channel instanceof NioChannel) {

            final java.nio.channels.SocketChannel socketChannel = java.nio.channels.SocketChannel.open();

            if (clientConfig.getSocketOptions() != null) {
                for (Map.Entry<SocketOption<Object>, Object> entry : clientConfig.getSocketOptions().entrySet()) {
                    socketChannel.setOption(entry.getKey(), entry.getValue());
                }
            }
            socketChannel.configureBlocking(false);
            /*
             * 连接到指定的服务地址
             */
            socketChannel.connect(new InetSocketAddress(clientConfig.getHost(), clientConfig.getPort()));
            /*
             * 创建一个事件选择器Selector
             */
            Selector selector = Selector.open();

            /*
             * 将创建的SocketChannel注册到指定的Selector上，并指定关注的事件类型为OP_CONNECT
             */
            socketChannel.register(selector, SelectionKey.OP_CONNECT);
            while (selector.select() > 0) {
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey sk = it.next();
                    if (sk.isConnectable()) {
                        java.nio.channels.SocketChannel channels = (java.nio.channels.SocketChannel) sk.channel();
                        //during connecting, finish the connect
                        if (channels.isConnectionPending()) {
                            try {
                                channels.finishConnect();
                                channel = new NioChannel(clientConfig, socketChannel, ((NioChannel) channel).getNioEventLoop(),channel.getByteBufAllocator(),channel.getWorkerThreadPool(), channel.getChannelPipeline());
                                if (null != connectHandler) {
                                    if (null != channel.getSslHandler()) {
                                        channel.setSslHandshakeCompletedListener(new IHandshakeCompletedListener() {
                                            @Override
                                            public void onComplete() {
                                                logger.info("Ssl Handshake Completed");
                                                connectHandler.onCompleted(channel);
                                            }
                                        });
                                    } else {
                                        connectHandler.onCompleted(channel);
                                    }
                                }
                                //创建成功立即开始读
                                ((NioChannel) channel).register();
                            } catch (Exception e) {
                                logger.error(e.getMessage(), e);
                                reConnect(channel);
                                if (null != connectHandler) {
                                    connectHandler.onFailed(e);
                                }
                                return;
                            }
                        }
                    }
                }
                it.remove();
            }
        }
    }


    /**
     * 重连
     *
     * @param socketChannel
     */
    public void reConnect(SocketChannel socketChannel) {
        //判断是否已经连接
        if (socketChannel.isInvalid()) {
            logger.debug("reconnect...");
            // 重连的间隔时间会越来越长
            long timeout = attempts * threshold;
            //启动定时器，通过定时器连接
            timer.newTimeout(this, timeout, TimeUnit.MILLISECONDS);
            if (attempts < 10) {
                attempts++;
            }
        }
    }
}
