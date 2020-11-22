package com.gettyio.core.channel.loop;

import com.gettyio.core.buffer.*;
import com.gettyio.core.channel.NioChannel;
import com.gettyio.core.channel.config.BaseConfig;
import com.gettyio.core.logging.InternalLogger;
import com.gettyio.core.logging.InternalLoggerFactory;
import com.gettyio.core.util.ThreadPool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;


/**
 * NioEventLoop.java
 *
 * @description:nio循环事件处理
 * @author:gogym
 * @date:2020/6/17
 * @copyright: Copyright by gettyio.com
 */
public class NioEventLoop implements EventLoop {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private boolean shutdown = false;

    private BaseConfig config;

    /**
     * selector包装
     */
    private SelectedSelector selector;

    /**
     * 创建一个2个线程的线程池，负责读和写
     */
    private ThreadPool workerThreadPool;
    /**
     * 内存池
     */
    protected ChunkPool chunkPool;


    /**
     * 数据输出类
     */
    protected NioBufferWriter nioBufferWriter;

    /**
     * 写缓冲
     */
    protected ChannelByteBuffer writeByteBuffer;


    public NioEventLoop(BaseConfig config, ChunkPool chunkPool) {
        this.config = config;
        this.chunkPool = chunkPool;
        this.workerThreadPool = new ThreadPool(ThreadPool.FixedThread, 2);
        //初始化数据输出类
        nioBufferWriter = new NioBufferWriter(chunkPool, config.getBufferWriterQueueSize(), config.getChunkPoolBlockTime());
        try {
            selector = new SelectedSelector(Selector.open());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        //循环读
        workerThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                while (true && !shutdown) {
                    try {
                        selector.select();
                    } catch (IOException e) {
                        LOGGER.error(e);
                    }
                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey sk = it.next();
                        Object obj = sk.attachment();
                        if (obj instanceof NioChannel) {
                            NioChannel nioChannel = (NioChannel) obj;

                            java.nio.channels.SocketChannel channel = (java.nio.channels.SocketChannel) sk.channel();
                            if (sk.isConnectable()) {
                                //during connecting, finish the connect
                                if (channel.isConnectionPending()) {
                                    try {
                                        channel.finishConnect();
                                    } catch (IOException e) {
                                        LOGGER.error(e);
                                        nioChannel.close();
                                        break;
                                    }
                                }
                            } else if (sk.isReadable()) {

                                ByteBuffer readBuffer = null;
                                //接收数据
                                try {
                                    readBuffer = chunkPool.allocate(config.getReadBufferSize(), config.getChunkPoolBlockTime());
                                    int reccount = channel.read(readBuffer);
                                    if (reccount == -1) {
                                        chunkPool.deallocate(readBuffer);
                                        nioChannel.close();
                                        break;
                                    }
                                } catch (Exception e) {
                                    LOGGER.error(e);
                                    if (null != readBuffer) {
                                        chunkPool.deallocate(readBuffer);
                                    }
                                    nioChannel.close();
                                    break;
                                }

                                //读取缓冲区数据到管道
                                if (null != readBuffer) {
                                    readBuffer.flip();
                                    //读取缓冲区数据，输送到责任链
                                    while (readBuffer.hasRemaining()) {
                                        byte[] bytes = new byte[readBuffer.remaining()];
                                        readBuffer.get(bytes, 0, bytes.length);
                                        nioChannel.doRead(bytes);
                                    }
                                }
                                //触发读取完成，清理缓冲区
                                chunkPool.deallocate(readBuffer);
                            }
                        }
                    }
                    it.remove();
                }
            }
        });

        //循环写
        workerThreadPool.execute(new Runnable() {
            @Override
            public void run() {

                while (true && !shutdown) {
                    if (writeByteBuffer == null) {
                        writeByteBuffer = nioBufferWriter.poll();
                    } else if (!writeByteBuffer.getByteBuffer().hasRemaining()) {
                        //写完及时释放
                        chunkPool.deallocate(writeByteBuffer.getByteBuffer());
                        writeByteBuffer = nioBufferWriter.poll();
                    }

                    if (writeByteBuffer != null) {
                        //再次写
                        try {
                            if (writeByteBuffer.getNioChannel().isInvalid()) {
                                chunkPool.deallocate(writeByteBuffer.getByteBuffer());
                                writeByteBuffer = null;
                                continue;
                            }
                            writeByteBuffer.getNioChannel().getSocketChannel().write(writeByteBuffer.getByteBuffer());
                        } catch (IOException e) {
                            writeByteBuffer.getNioChannel().close();
                            chunkPool.deallocate(writeByteBuffer.getByteBuffer());
                            writeByteBuffer = null;
                            continue;
                        }

                        if (!writeByteBuffer.getNioChannel().isKeepAlive()) {
                            writeByteBuffer.getNioChannel().close();
                            chunkPool.deallocate(writeByteBuffer.getByteBuffer());
                            writeByteBuffer = null;
                            continue;
                        }
                    }

                }
            }
        });

    }

    @Override
    public void shutdown() {

        shutdown = true;

        if (nioBufferWriter != null) {
            try {
                nioBufferWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        if (!workerThreadPool.isShutDown()) {
            workerThreadPool.shutdown();
        }
    }

    @Override
    public SelectedSelector getSelector() {
        return selector;
    }

    @Override
    public NioBufferWriter getBufferWriter() {
        return nioBufferWriter;
    }


}