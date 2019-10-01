/**
 * 包名：org.getty.core.handler.timeout
 * 版权：Copyright by www.getty.com
 * 描述：
 * 邮箱：189155278@qq.com
 * 时间：2019/9/27
 */
package org.getty.core.handler.timeout;

import org.getty.core.channel.AioChannel;
import org.getty.core.channel.ChannelState;
import org.getty.core.pipeline.PipelineDirection;
import org.getty.core.pipeline.in.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 类名：HeartBeatTimeOutHandler.java
 * 描述：心跳检测
 * 修改人：gogym
 * 时间：2019/9/27
 */
public class HeartBeatTimeOutHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(HeartBeatTimeOutHandler.class);
    private int loss_connect_time = 0;

    @Override
    public void userEventTriggered(AioChannel aioChannel, IdleState evt) {
        if (evt == IdleState.READER_IDLE) {
            loss_connect_time++;
            if (loss_connect_time > 2) {
                // 超过3次检测没有心跳就关闭这个连接
                try {
                    System.out.println("[关闭不活跃的channel:" + aioChannel.getRemoteAddress().getHostString() + "]");
                    logger.info("[closed inactive channel:" + aioChannel.getRemoteAddress().getHostString() + "]");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                aioChannel.close();
            }
        } else {
            super.userEventTriggered(aioChannel, evt);
        }
    }

    @Override
    public void handler(ChannelState channelStateEnum, byte[] bytes, Throwable cause, AioChannel aioChannel, PipelineDirection pipelineDirection) {
        switch (channelStateEnum) {
            case CHANNEL_READ:
                loss_connect_time = 0;
                break;
        }
        super.handler(channelStateEnum, bytes, cause, aioChannel, pipelineDirection);
    }
}
