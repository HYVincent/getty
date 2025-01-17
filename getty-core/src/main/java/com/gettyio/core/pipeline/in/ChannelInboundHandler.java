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
package com.gettyio.core.pipeline.in;


import com.gettyio.core.channel.SocketChannel;
import com.gettyio.core.pipeline.ChannelBoundHandler;
import com.gettyio.core.util.LinkedBlockQueue;

/**
 * ChannelInboundHandler.java
 *
 * @description:
 * @author:gogym
 * @date:2020/4/9
 * @copyright: Copyright by gettyio.com
 */
public interface ChannelInboundHandler extends ChannelBoundHandler {

    /**
     * 连接
     *
     * @param socketChannel 通道
     * @throws Exception 异常
     */
    @Override
    void channelAdded(SocketChannel socketChannel) throws Exception;

    /**
     * 连接关闭
     *
     * @param socketChannel 通道
     * @throws Exception 异常
     */
    @Override
    void channelClosed(SocketChannel socketChannel) throws Exception;

    /**
     * 消息读取
     *
     * @param obj           读取消息
     * @param socketChannel 通道
     * @throws Exception 异常
     */
    @Override
    void channelRead(SocketChannel socketChannel, Object obj) throws Exception;

    /**
     * 异常
     *
     * @param socketChannel 通道
     * @param cause         异常信息
     * @throws Exception 异常
     */
    @Override
    void exceptionCaught(SocketChannel socketChannel, Throwable cause) throws Exception;

    /**
     * 消息解码
     *
     * @param socketChannel 通道
     * @param obj           消息
     * @param out           消息队列
     * @throws Exception 异常
     */
    @Override
    void decode(SocketChannel socketChannel, Object obj, LinkedBlockQueue<Object> out) throws Exception;

}
