package com.gettyio.string.aio;

import com.gettyio.core.channel.SocketChannel;
import com.gettyio.core.channel.config.ClientConfig;
import com.gettyio.core.channel.starter.AioClientStarter;
import com.gettyio.core.channel.starter.ConnectHandler;
import com.gettyio.core.handler.codec.string.DelimiterFrameDecoder;
import com.gettyio.core.handler.codec.string.StringDecoder;
import com.gettyio.core.handler.codec.string.StringEncoder;
import com.gettyio.core.handler.ssl.SslConfig;
import com.gettyio.core.handler.ssl.SslHandler;
import com.gettyio.core.handler.ssl.SslService;
import com.gettyio.core.pipeline.ChannelInitializer;
import com.gettyio.core.pipeline.DefaultChannelPipeline;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

public class AioClient {

    public static void main(String[] args) throws InterruptedException, ExecutionException, IOException {

        int i = 0;
        while (i < 1) {
            AioClient ac = new AioClient();
            ac.test(8888);
            i++;
        }
    }


    private void test(int port) {


        ClientConfig aioConfig = new ClientConfig();
        aioConfig.setHost("127.0.0.1");
        aioConfig.setPort(port);
        aioConfig.setFlowControl(false);
        aioConfig.setHighWaterMark(2);
        aioConfig.setLowWaterMark(1);


        final AioClientStarter client = new AioClientStarter(aioConfig);
        client.channelInitializer(new ChannelInitializer() {
            @Override
            public void initChannel(SocketChannel channel) throws Exception {
                //责任链
                DefaultChannelPipeline defaultChannelPipeline = channel.getDefaultChannelPipeline();


                //获取证书
                String pkPath = getClass().getClassLoader().getResource("clientStore.jks").getPath();
                //ssl配置
                SslConfig sSLConfig = new SslConfig();
                sSLConfig.setKeyFile(pkPath);
                sSLConfig.setKeyPassword("123456");
                sSLConfig.setKeystorePassword("123456");
                //sSLConfig.setTrustFile(pkPath);
                //sSLConfig.setTrustPassword("123456");
                //设置服务器模式
                sSLConfig.setClientMode(true);
                //初始化ssl服务
                SslService sSLService = new SslService(sSLConfig);
                //defaultChannelPipeline.addFirst(new SslHandler(channel, sSLService));


                defaultChannelPipeline.addLast(new StringEncoder());
                //指定结束符解码器
                defaultChannelPipeline.addLast(new DelimiterFrameDecoder(DelimiterFrameDecoder.lineDelimiter));
                //字符串解码器
                defaultChannelPipeline.addLast(new StringDecoder());
                //定义消息解码器
                defaultChannelPipeline.addLast(new ClientSimpleHandler());
            }
        });

        client.start(new ConnectHandler() {
            @Override
            public void onCompleted(final SocketChannel socketChannel) {

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String s = "12\r\n";
                            byte[] msgBody = s.getBytes("utf-8");
                            long ct = System.currentTimeMillis();

                            int i = 0;
                            while (i < 100) {
                                boolean flag = socketChannel.writeAndFlush(msgBody);
                                System.out.printf(flag + " ");
                                if (flag) {
                                    i++;
                                }
                            }

                            long lt = System.currentTimeMillis();
                            System.out.printf("总耗时(ms)：" + (lt - ct) + "\r\n");
                            System.out.printf("发送消息数量：" + i + "条\r\n");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }).start();


            }

            @Override
            public void onFailed(Throwable exc) {

            }
        });


    }


}
