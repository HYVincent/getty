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
package com.gettyio.expansion.handler.codec.http.request;

import com.gettyio.core.buffer.AutoByteBuffer;
import com.gettyio.core.channel.SocketChannel;
import com.gettyio.core.handler.codec.MessageToByteEncoder;
import com.gettyio.expansion.handler.codec.http.HttpEncodeSerializer;

/**
 * HttpRequestEncoder.java
 *
 * @description:http请求编码类
 * @author:gogym
 * @date:2020/4/9
 * @copyright: Copyright by gettyio.com
 */
public class HttpRequestEncoder extends MessageToByteEncoder {

    @Override
    public void encode(SocketChannel socketChannel, Object obj) throws Exception {
        AutoByteBuffer buffer = AutoByteBuffer.newByteBuffer();
        if (obj instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) obj;
            HttpEncodeSerializer.encodeInitialLine(buffer, httpRequest);
            HttpEncodeSerializer.encodeHeaders(buffer, httpRequest);
            HttpEncodeSerializer.encodeContent(buffer, httpRequest);
            obj = buffer.readableBytesArray();
        }
        super.encode(socketChannel, obj);
    }
}
