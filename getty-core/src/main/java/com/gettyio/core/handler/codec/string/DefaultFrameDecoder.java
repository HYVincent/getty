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

import com.gettyio.core.channel.SocketChannel;
import com.gettyio.core.pipeline.in.ChannelInboundHandlerAdapter;
import com.gettyio.core.util.LinkedBlockQueue;


/**
 * DefaultFrameDecoder.java
 *
 * @description:字符串默认解码器，收到多少传回多少，中间不做任何处理
 * @author:gogym
 * @date:2020/4/9
 * @copyright: Copyright by gettyio.com
 */
public class DefaultFrameDecoder extends ChannelInboundHandlerAdapter {

    @Override
    public void decode(SocketChannel socketChannel,Object obj, LinkedBlockQueue<Object> out) throws Exception {
        super.decode(socketChannel, obj, out);
    }

}
