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

import com.gettyio.core.constant.Banner;
import com.gettyio.core.constant.Version;
import com.gettyio.core.buffer.pool.PooledByteBufAllocator;
import com.gettyio.core.util.PlatformDependent;
import com.gettyio.core.channel.SocketChannel;
import com.gettyio.core.channel.NioChannel;
import com.gettyio.core.channel.SocketMode;
import com.gettyio.core.channel.UdpChannel;
import com.gettyio.core.channel.config.ServerConfig;
import com.gettyio.core.channel.loop.NioEventLoop;
import com.gettyio.core.logging.InternalLogger;
import com.gettyio.core.logging.InternalLoggerFactory;
import com.gettyio.core.pipeline.ChannelPipeline;
import com.gettyio.core.util.FastArrayList;
import com.gettyio.core.util.ThreadPool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.channels.*;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;


/**
 * NioServerStarter.java
 *
 * @description:nio服务端
 * @author:gogym
 * @date:2020/4/8
 * @copyright: Copyright by gettyio.com
 */
public class NioServerStarter extends NioStarter {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(NioServerStarter.class);

    /**
     * 服务端配置
     */
    protected ServerConfig serverConfig = new ServerConfig();

    /**
     * 服务线程运行标志
     */
    private volatile boolean running = true;

    /**
     * socket对象
     */
    private ServerSocketChannel serverSocketChannel;

    /**
     * udp通道对象
     */
    private DatagramChannel datagramChannel;

    /**
     * 多路复用选择器
     */
    private Selector selector;

    /**
     * loop集合
     */
    private final FastArrayList<NioEventLoop> nioEventLoopFastArrayList = new FastArrayList<>(NioEventLoop.class);

    /**
     * 简单启动
     *
     * @param port 服务端口
     */
    public NioServerStarter(int port) {
        serverConfig.setPort(port);
    }

    /**
     * 指定host启动
     *
     * @param host 服务地址
     * @param port 服务端口
     */
    public NioServerStarter(String host, int port) {
        serverConfig.setHost(host);
        serverConfig.setPort(port);
    }

    /**
     * 指定配置启动
     *
     * @param config 配置
     */
    public NioServerStarter(ServerConfig config) {
        this.serverConfig = config;
    }

    /**
     * 责任链
     *
     * @param channelPipeline 责任链
     * @return AioServerStarter
     */
    public NioServerStarter channelInitializer(ChannelPipeline channelPipeline) {
        this.channelPipeline = channelPipeline;
        return this;
    }


    /**
     * 设置Boss线程数
     *
     * @param threadNum 线程数
     * @return AioServerStarter
     */
    public NioServerStarter bossThreadNum(int threadNum) {
        if (threadNum >= 3) {
            this.bossThreadNum = threadNum;
        }
        return this;
    }

    /**
     * 设置work线程数
     *
     * @param threadNum
     * @return
     */
    public NioServerStarter workerThreadNum(int threadNum) {
        if (threadNum >= 3) {
            this.workerThreadNum = threadNum;
        }
        return this;
    }

    /**
     * 设置socket类型
     *
     * @param socketMode
     * @return
     */
    public NioServerStarter socketMode(SocketMode socketMode) {
        this.socketMode = socketMode;
        return this;
    }


    /**
     * 启动IO服务
     *
     * @throws Exception 异常
     */
    public void start() throws Exception {
        //打印框架信息
        LOGGER.info("\r\n" + Banner.BANNER + "\r\n  getty version:(" + Version.VERSION + ")");
        startCheck();
        //实例化内存池
        this.byteBufAllocator = new PooledByteBufAllocator(PlatformDependent.directBufferPreferred() && serverConfig.isDirect());

        //初始化boss线程池
        bossThreadPool = new ThreadPool(ThreadPool.FixedThread, bossThreadNum);
        //初始化worker线程池
        workerThreadPool = new ThreadPool(ThreadPool.FixedThread, workerThreadNum);

        //创建loop集合
        for (int i = 0; i < workerThreadNum; i++) {
            NioEventLoop nioEventLoop = new NioEventLoop(serverConfig, byteBufAllocator);
            nioEventLoop.run();
            nioEventLoopFastArrayList.add(nioEventLoop);
        }

        if (socketMode == SocketMode.TCP) {
            startTcp();
        } else {
            startUdp();
        }
    }

    /**
     * 启动TCP
     *
     * @throws IOException 异常
     */
    private void startTcp() throws IOException {
        //开启一个服务channel，
        serverSocketChannel = ServerSocketChannel.open();
        //设为非阻塞
        serverSocketChannel.configureBlocking(false);

        //绑定端口
        if (serverConfig.getHost() != null) {
            //服务端socket处理客户端socket连接是需要一定时间的。ServerSocket有一个队列，存放还没有来得及处理的客户端Socket，这个队列的容量就是backlog的含义。
            // 如果队列已经被客户端socket占满了，如果还有新的连接过来，那么ServerSocket会拒绝新的连接。
            // 也就是说backlog提供了容量限制功能，避免太多的客户端socket占用太多服务器资源
            serverSocketChannel.bind(new InetSocketAddress(serverConfig.getHost(), serverConfig.getPort()), 1000);
        } else {
            serverSocketChannel.bind(new InetSocketAddress(serverConfig.getPort()), 1000);
        }


        //创建一个selector
        selector = Selector.open();
        //将创建的serverChannel注册到selector选择器上，指定这个channel只关心OP_ACCEPT事件
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        //开启线程，开始接收客户端的连接
        bossThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                //循环监听客户端的连接
                while (running) {
                    try {
                        //select()操作，默认是阻塞模式的，即，当没有accept或者read时间到来时，将一直阻塞不往下面继续执行。
                        int readyChannels = selector.select();
                        if (readyChannels <= 0) {
                            continue;
                        }
                        //从selector上获取到了IO事件，可能是accept，也有可能是read，这里只关注可能是accept
                        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

                        while (iterator.hasNext()) {
                            SelectionKey key = iterator.next();
                            if (key.isAcceptable()) {
                                //处理OP_ACCEPT事件
                                final java.nio.channels.SocketChannel socketChannel = ((ServerSocketChannel) key.channel()).accept();
                                socketChannel.configureBlocking(false);
                                //通过线程池创建客户端连接通道
                                createTcpChannel(socketChannel);
                            }
                            iterator.remove();
                        }
                    } catch (IOException e) {
                        LOGGER.error("socketChannel accept Exception", e);
                    }
                }
            }
        });
    }


    /**
     * 启动UDP
     *
     * @throws IOException 异常
     */
    private void startUdp() throws IOException {

        datagramChannel = DatagramChannel.open();
        datagramChannel.configureBlocking(false);
        datagramChannel.bind(new InetSocketAddress(serverConfig.getPort()));
        //设置socket参数
        if (serverConfig.getSocketOptions() != null) {
            for (Map.Entry<SocketOption<Object>, Object> entry : serverConfig.getSocketOptions().entrySet()) {
                datagramChannel.setOption(entry.getKey(), entry.getValue());
            }
        }
        selector = Selector.open();
        datagramChannel.register(selector, SelectionKey.OP_READ);
        //创建udp通道
        createUdpChannel(datagramChannel, selector);

        LOGGER.info("getty server started UDP on port {},bossThreadNum:{} ,workerThreadNum:{}", serverConfig.getPort(), bossThreadNum, workerThreadNum);
        LOGGER.info("getty server config is {}", serverConfig.toString());
    }

    /**
     * 为每个新连接创建AioChannel对象
     *
     * @param channel 通道
     */
    private void createTcpChannel(java.nio.channels.SocketChannel channel) {
        SocketChannel socketChannel = null;
        try {
            //获取loop
            NioEventLoop nioEventLoop = nioEventLoopFastArrayList.round();
            socketChannel = new NioChannel(serverConfig, channel, nioEventLoop, byteBufAllocator, workerThreadPool, channelPipeline);
            //注册事件
            ((NioChannel) socketChannel).register();
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            if (socketChannel != null) {
                closeChannel(channel);
            }
        }
    }

    /**
     * 创建Udp通道
     *
     * @return void
     * @params [datagramChannel, selector]
     */
    private void createUdpChannel(DatagramChannel datagramChannel, Selector selector) {
        UdpChannel udpChannel = new UdpChannel(datagramChannel, selector, serverConfig, byteBufAllocator, channelPipeline, workerThreadNum);
        udpChannel.starRead();
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
     * 停止服务
     */
    public final void shutdown() {
        //接收线程标志置为false
        running = false;
        try {
            if (serverSocketChannel != null) {
                serverSocketChannel.close();
                serverSocketChannel = null;
            }

        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }

        if (datagramChannel != null) {
            try {
                datagramChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            datagramChannel = null;
        }

        if (selector != null) {
            try {
                selector.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            selector = null;
        }

        for (NioEventLoop nioEventLoop : nioEventLoopFastArrayList) {
            nioEventLoop.shutdown();
        }
        LOGGER.info("getty server is shutdown in " + new Date());
    }


    /**
     * 启动检查
     */
    private void startCheck() {
        if (serverConfig == null) {
            throw new NullPointerException("AioServerConfig can't null");
        }
        if (serverConfig.getPort() == 0) {
            throw new NullPointerException("AioServerConfig port can't null");
        }
        if (channelPipeline == null) {
            throw new RuntimeException("ChannelPipeline can't be null");
        }
        if (serverConfig.isFlowControl()) {
            if (serverConfig.getLowWaterMark() >= serverConfig.getHighWaterMark()) {
                throw new RuntimeException("lowWaterMark must be small than highWaterMark");
            }
            if (serverConfig.getHighWaterMark() >= serverConfig.getBufferWriterQueueSize()) {
                LOGGER.warn("HighWaterMark is meaningless if it is greater than BufferWriterQueueSize");
            }
        }
    }
}
