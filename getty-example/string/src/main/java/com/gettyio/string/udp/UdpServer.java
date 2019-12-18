package com.gettyio.string.udp;


import com.gettyio.core.channel.AioChannel;
import com.gettyio.core.channel.SocketChannel;
import com.gettyio.core.channel.config.AioServerConfig;
import com.gettyio.core.channel.starter.AioServerStarter;
import com.gettyio.core.handler.codec.datagramPacket.DatagramPacketDecoder;
import com.gettyio.core.handler.codec.datagramPacket.DatagramPacketEncoder;
import com.gettyio.core.pipeline.ChannelInitializer;
import com.gettyio.core.pipeline.DefaultChannelPipeline;

import java.net.StandardSocketOptions;

public class UdpServer {


    public static void main(String[] args) {

        try {
            //初始化配置对象
            AioServerConfig aioServerConfig = new AioServerConfig();
            //设置host,不设置默认localhost
            aioServerConfig.setHost("127.0.0.1");
            //设置端口号
            aioServerConfig.setPort(8888);
            //设置服务器端内存池最大可分配空间大小，默认256mb，内存池空间可以根据吞吐量设置。
            // 尽量可以设置大一点，因为这不会真正的占用系统内存，只有真正使用时才会分配
            aioServerConfig.setServerChunkSize(512 * 1024 * 1024);
            //设置数据输出器队列大小，一般不用设置这个参数，默认是10*1024*1024
            aioServerConfig.setBufferWriterQueueSize(10 * 1024 * 1024);
            //设置读取缓存块大小，一般不用设置这个参数，默认128字节
            aioServerConfig.setReadBufferSize(128);
            //设置内存池等待分配内存的最大阻塞时间，默认是1秒
            aioServerConfig.setChunkPoolBlockTime(1000);
            //设置SocketOptions
            aioServerConfig.setOption(StandardSocketOptions.SO_RCVBUF, 8192);

            AioServerStarter server = new AioServerStarter(8888);
            server.socketChannel(SocketChannel.UDP).channelInitializer(new ChannelInitializer() {
                @Override
                public void initChannel(AioChannel channel) throws Exception {
                    //获取责任链对象
                    DefaultChannelPipeline defaultChannelPipeline = channel.getDefaultChannelPipeline();


                    defaultChannelPipeline.addLast(new DatagramPacketEncoder());
                    defaultChannelPipeline.addLast(new DatagramPacketDecoder());
                    defaultChannelPipeline.addLast(new SimpleHandler());
                }
            }).start();
            System.out.println("启动Udp");
        } catch (Exception e) {

        }

    }
}