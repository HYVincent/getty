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
package com.gettyio.core.channel.starter;

import com.gettyio.core.buffer.pool.PooledByteBufAllocator;
import com.gettyio.core.util.PlatformDependent;
import com.gettyio.core.channel.*;
import com.gettyio.core.channel.SocketChannel;
import com.gettyio.core.channel.config.ClientConfig;
import com.gettyio.core.channel.loop.NioEventLoop;
import com.gettyio.core.handler.ssl.sslfacade.IHandshakeCompletedListener;
import com.gettyio.core.logging.InternalLogger;
import com.gettyio.core.logging.InternalLoggerFactory;
import com.gettyio.core.pipeline.ChannelPipeline;
import com.gettyio.core.util.ThreadPool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Map;

/**
 * NioClientStarter.java
 *
 * @description:nio客户端
 * @author:gogym
 * @date:2020/4/8
 * @copyright: Copyright by gettyio.com
 */
public class NioClientStarter extends NioStarter {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(NioClientStarter.class);

    /**
     * 客户端配置
     */
    private ClientConfig clientConfig = new ClientConfig();
    /**
     * channel通道
     */
    private SocketChannel nioChannel;

    /**
     * loop
     */
    private NioEventLoop nioEventLoop;

    /**
     * 多路复用
     */
    Selector selector;

    /**
     * 简单启动
     *
     * @param host 服务器地址
     * @param port 服务器端口号
     */
    public NioClientStarter(String host, int port) {
        clientConfig.setHost(host);
        clientConfig.setPort(port);
    }


    /**
     * 配置文件启动
     *
     * @param clientConfig 配置
     */
    public NioClientStarter(ClientConfig clientConfig) {
        if (null == clientConfig.getHost() || "".equals(clientConfig.getHost())) {
            throw new NullPointerException("The connection host is null.");
        }
        if (0 == clientConfig.getPort()) {
            throw new NullPointerException("The connection port is null.");
        }
        this.clientConfig = clientConfig;
    }


    /**
     * 设置责任链
     *
     * @param channelPipeline 责任链
     * @return AioClientStarter
     */
    public NioClientStarter channelInitializer(ChannelPipeline channelPipeline) {
        this.channelPipeline = channelPipeline;
        return this;
    }


    public NioClientStarter socketMode(SocketMode socketMode) {
        this.socketMode = socketMode;
        return this;
    }


    /**
     * 启动客户端。
     *
     * @throws Exception 异常
     */
    public final void start() throws Exception {
        try {
            start0(null);
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            throw new Exception(e);
        }
    }

    /**
     * 启动客户端,并且回调
     *
     * @throws Exception 异常
     */
    public final void start(ConnectHandler connectHandler) {
        try {
            start0(connectHandler);
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            connectHandler.onFailed(e);
            return;
        }
    }

    /**
     * 内部启动
     *
     * @throws Exception
     */
    private void start0(ConnectHandler connectHandler) throws Exception {
        startCheck();
        //初始化worker线程池
        workerThreadPool = new ThreadPool(ThreadPool.FixedThread, 1);
        //初始化内存池
        byteBufAllocator = new PooledByteBufAllocator(PlatformDependent.directBufferPreferred() && clientConfig.isDirect());
        //调用内部启动
        nioEventLoop = new NioEventLoop(clientConfig, byteBufAllocator);
        nioEventLoop.run();
        if (socketMode == SocketMode.TCP) {
            startTcp(connectHandler);
        } else {
            startUdp(connectHandler);
        }
    }


    /**
     * 该方法为非阻塞连接。连接成功与否，会回调
     */
    private void startTcp(final ConnectHandler connectHandler) throws Exception {

        final java.nio.channels.SocketChannel socketChannel = java.nio.channels.SocketChannel.open();
        if (clientConfig.getSocketOptions() != null) {
            for (Map.Entry<SocketOption<Object>, Object> entry : clientConfig.getSocketOptions().entrySet()) {
                socketChannel.setOption(entry.getKey(), entry.getValue());
            }
        }
        //设为非阻塞
        socketChannel.configureBlocking(false);
        /*
         * 连接到指定的服务地址
         */
        socketChannel.connect(new InetSocketAddress(clientConfig.getHost(), clientConfig.getPort()));

        /*
         * 创建一个事件选择器Selector
         */
        selector = Selector.open();
        /*
         * 将创建的SocketChannel注册到指定的Selector上，并指定关注的事件类型为OP_CONNECT
         */
        socketChannel.register(selector, SelectionKey.OP_CONNECT);

        while (selector.select() > 0) {
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey sk = it.next();
                if (sk.isConnectable()) {
                    java.nio.channels.SocketChannel channel = (java.nio.channels.SocketChannel) sk.channel();
                    //during connecting, finish the connect
                    if (channel.isConnectionPending()) {
                        channel.finishConnect();
                        try {
                            nioChannel = new NioChannel(clientConfig, socketChannel, nioEventLoop, byteBufAllocator, workerThreadPool, channelPipeline);
                            if (connectHandler != null) {
                                if (null != nioChannel.getSslHandler()) {
                                    nioChannel.setSslHandshakeCompletedListener(new IHandshakeCompletedListener() {
                                        @Override
                                        public void onComplete() {
                                            LOGGER.info("Ssl Handshake Completed");
                                            connectHandler.onCompleted(nioChannel);
                                        }
                                    });
                                } else {
                                    connectHandler.onCompleted(nioChannel);
                                }
                            }
                            //创建成功注册
                            ((NioChannel) nioChannel).register();
                        } catch (Exception e) {
                            LOGGER.error(e.getMessage(), e);
                            if (nioChannel != null) {
                                closeChannel(socketChannel);
                            }
                            if (null != connectHandler) {
                                connectHandler.onFailed(e);
                            }
                        }
                    }
                }
            }
            it.remove();
        }
    }

    /**
     * 启动udp
     *
     * @param connectHandler
     * @throws IOException
     */
    private final void startUdp(ConnectHandler connectHandler) throws IOException {

        DatagramChannel datagramChannel = DatagramChannel.open();
        datagramChannel.configureBlocking(false);
        Selector selector = Selector.open();
        datagramChannel.register(selector, SelectionKey.OP_READ);
        nioChannel = new UdpChannel(datagramChannel, selector, clientConfig, byteBufAllocator, channelPipeline, 3);
        nioChannel.starRead();
        if (null != connectHandler) {
            connectHandler.onCompleted(nioChannel);
        }
    }


    /**
     * 停止客户端
     */
    public final void shutdown() {
        if (nioChannel != null) {
            nioChannel.close();
            nioChannel = null;
        }

        if (selector != null && selector.isOpen()) {
            try {
                selector.close();
            } catch (IOException e) {
                LOGGER.error(e);
            }
        }

        if (nioEventLoop != null) {
            nioEventLoop.shutdown();
        }
    }


    /**
     * 关闭客户端连接通道
     *
     * @param channel 通道
     */
    private void closeChannel(java.nio.channels.SocketChannel channel) {
        try {
            channel.shutdownInput();
        } catch (IOException e) {
            LOGGER.debug(e.getMessage(), e);
        }
        try {
            channel.shutdownOutput();
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
        try {
            channel.close();
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    /**
     * 启动检查
     */
    private void startCheck() {
        if (null == clientConfig.getHost() || "".equals(clientConfig.getHost())) {
            throw new NullPointerException("The connection host is null.");
        }
        if (0 == clientConfig.getPort()) {
            throw new NullPointerException("The connection port is null.");
        }
        if (channelPipeline == null) {
            throw new RuntimeException("ChannelPipeline can't be null");
        }
        if (clientConfig.isFlowControl()) {
            if (clientConfig.getLowWaterMark() >= clientConfig.getHighWaterMark()) {
                throw new RuntimeException("lowWaterMark must be small than highWaterMark");
            }
            if (clientConfig.getHighWaterMark() >= clientConfig.getBufferWriterQueueSize()) {
                LOGGER.warn("HighWaterMark is meaningless if it is greater than BufferWriterQueueSize");
            }
        }
    }

}
