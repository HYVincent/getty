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
package com.gettyio.core.handler.ssl;

import com.gettyio.core.channel.SocketChannel;
import com.gettyio.core.handler.ssl.sslfacade.IHandshakeCompletedListener;
import com.gettyio.core.handler.ssl.sslfacade.ISSLListener;
import com.gettyio.core.handler.ssl.sslfacade.ISessionClosedListener;
import com.gettyio.core.logging.InternalLogger;
import com.gettyio.core.logging.InternalLoggerFactory;
import com.gettyio.core.pipeline.all.ChannelAllBoundHandlerAdapter;
import com.gettyio.core.util.LinkedBlockQueue;

import java.nio.ByteBuffer;

/**
 * SslHandler.java
 *
 * @description:SSL 编解码器
 * @author:gogym
 * @date:2020/4/9
 * @copyright: Copyright by gettyio.com
 */
public class SslHandler extends ChannelAllBoundHandlerAdapter {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(SslHandler.class);

    private final SslService sslService;
    private final SocketChannel socketChannel;
    LinkedBlockQueue<Object> out;

    public SslHandler(SocketChannel socketChannel, SslService sslService) {
        this.socketChannel = socketChannel;
        this.sslService = sslService;
        this.socketChannel.setSslHandler(this);
        sslService.createSSLFacade(new handshakeCompletedListener(), new SSLListener(), new sessionClosedListener());
    }


    public SslService getSslService() {
        return sslService;
    }

    @Override
    public void encode(SocketChannel socketChannel, Object obj) throws Exception {
        byte[] bytes = (byte[]) obj;
        if (!sslService.getSsl().isHandshakeCompleted() && obj != null) {
            //握手
            ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
            try {
                sslService.getSsl().decrypt(byteBuffer);
                byte[] b = new byte[byteBuffer.remaining()];
                byteBuffer.get(bytes, 0, b.length);
                socketChannel.writeToChannel(b);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                sslService.getSsl().close();
            }
        } else if (bytes != null) {
            ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
            //SSL doUnWard
            sslService.getSsl().encrypt(byteBuffer);
        }
    }

    @Override
    public void decode(SocketChannel socketChannel, Object obj, LinkedBlockQueue<Object> out) throws Exception {
        this.out = out;
        byte[] bytes = (byte[]) obj;
        if (!sslService.getSsl().isHandshakeCompleted() && obj != null) {
            //握手
            ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
            try {
                sslService.getSsl().decrypt(byteBuffer);
                byte[] b = new byte[byteBuffer.remaining()];
                byteBuffer.get(bytes, 0, b.length);
                socketChannel.writeToChannel(b);
            } catch (Exception e) {
                sslService.getSsl().close();
            }
        } else if (bytes != null) {
            ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
            //SSL doUnWard
            sslService.getSsl().decrypt(byteBuffer);
        }
    }


    /**
     * 握手成功回调
     */
    class handshakeCompletedListener implements IHandshakeCompletedListener {
        @Override
        public void onComplete() {
            logger.info("ssl handshake completed");
            socketChannel.setHandShake(true);
            IHandshakeCompletedListener iHandshakeCompletedListener = socketChannel.getSslHandshakeCompletedListener();
            if (iHandshakeCompletedListener != null) {
                iHandshakeCompletedListener.onComplete();
            }
        }
    }


    /**
     * 握手关闭回调
     */
    class sessionClosedListener implements ISessionClosedListener {
        @Override
        public void onSessionClosed() {
            logger.info("ssl handshake failure");
            //当握手失败时，关闭当前客户端连接
            socketChannel.close();
        }
    }


    /**
     * 消息回调
     */
    class SSLListener implements ISSLListener {

        @Override
        public void onWrappedData(ByteBuffer wrappedBytes) {
            try {
                byte[] b = new byte[wrappedBytes.remaining()];
                wrappedBytes.get(b, 0, b.length);
                //回调父类方法
                SslHandler.super.encode(socketChannel, b);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }

        @Override
        public void onPlainData(ByteBuffer plainBytes) {
            //消息解码
            byte[] b = new byte[plainBytes.remaining()];
            plainBytes.get(b, 0, b.length);
            try {
                SslHandler.super.decode(socketChannel, b, out);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }


}
