
package com.gettyio.core.handler.codec.websocket;

public class WebSocketConstants {
    public static final String FILED_IP = "ip";// ip地址
    public static final String FILED_CLIENT_IP = "clientIp";// ip地址

    public static final String FILED_MSG = "msg";// 消息内容地址

    public static final char BEGIN_CHAR = 0x00;// 开始字符
    public static final char END_CHAR = 0xFF;// 结束字符
    public static final String BEGIN_MSG = String.valueOf(BEGIN_CHAR);// 消息开始
    public static final String END_MSG = String.valueOf(END_CHAR); // 消息结束
    public static final String BLANK = "";// 空白字符串
    public static final String UTF8 = "utf-8";//utf-8编码
    public static final String HANDSHAKE = "handshake";//握手标识

    public static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    public static final String HEADER_CODE = "iso-8859-1";
    public static final int SPLITVERSION0 = 0;// 版本0
    public static final int SPLITVERSION6 = 6;// 版本6
    public static final int SPLITVERSION7 = 7;// 版本7

    public static final String REQUEST_INDEX = "requestIndex";// 请求索引数
}
