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
package com.gettyio.core.handler.codec.string;

import com.gettyio.core.buffer.AutoByteBuffer;
import com.gettyio.core.channel.SocketChannel;
import com.gettyio.core.pipeline.in.ChannelInboundHandlerAdapter;
import com.gettyio.core.util.LinkedBlockQueue;


/**
 * DelimiterFrameDecoder.java
 *
 * @description:按标识符分割消息，目前默认\r\n
 * @author:gogym
 * @date:2020/4/9
 * @copyright: Copyright by gettyio.com
 */
public class DelimiterFrameDecoder extends ChannelInboundHandlerAdapter {

    /**
     * 默认分隔符
     */
    public static byte[] lineDelimiter = new byte[]{'\r', '\n'};
    AutoByteBuffer preBuffer = AutoByteBuffer.newByteBuffer();

    /**
     * 消息结束标志
     */
    private byte[] endFLag;
    /**
     * 本次校验的结束标索引位
     */
    private int exceptIndex;

    public DelimiterFrameDecoder(byte[] endFLag) {
        this.endFLag = endFLag;
    }

    @Override
    public void decode(SocketChannel socketChannel, Object obj, LinkedBlockQueue<Object> out) throws Exception {

        byte[] bytes = (byte[]) obj;
        int index = 0;
        while (index < bytes.length) {
            byte data = bytes[index];
            if (data != endFLag[exceptIndex]) {
                preBuffer.writeByte(data);
                exceptIndex = 0;
            } else if (++exceptIndex == endFLag.length) {
                //传递到下一个解码器
                super.decode(socketChannel, preBuffer.allWriteBytesArray(), out);
                preBuffer.clear();
                exceptIndex = 0;
            }
            index++;
        }

    }
}
