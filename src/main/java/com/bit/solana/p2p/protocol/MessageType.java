package com.bit.solana.p2p.protocol;

import java.util.HashMap;
import java.util.Map;


public enum MessageType {
    EMPTY(0, "空消息"),
    FIND_NODE_REQ(1, "寻找节点请求"),
    FIND_NODE_RES(2, "寻找节点响应"),
    PING(3, "ping"),
    PONG(4, "pong"),

    SHUTDOWN(5, "关闭"),
    TRANSACTION(10, "交易广播消息"),
    BLOCK(11, "区块广播消息"),

    GET_BLOCK_HEADERS_REQ(20, "索取区块头数据请求"),
    GET_BLOCK_HEADERS_RES(21, "索取区块头数据响应"),
    GET_BLOCK_REQ(22, "索取区块数据请求"),
    GET_BLOCK_RES(23, "索取区块数据响应"),

    GET_BLOCK_CHAIN_REQ(24, "索取/同步区块链请求"),
    GET_BLOCK_CHAIN_RES(25, "索取/同步区块链响应"),


    GET_BALANCE_REQ(30, "查询钱包余额请求"),
    GET_BALANCE_RES(31, "查询钱包余额响应"),
    GET_TRANSACTION_RECORD_REQ(32, "查询交易记录请求"),
    GET_TRANSACTION_RECORD_RES(33, "查询交易记录响应"),

    HANDSHAKE_REQ(34, "握手请求"),
    HANDSHAKE_RES(35, "握手响应"),

    VERSION(36, "版本信息"),
    INV(37, "数据清单"),
    GETDATA(38, "请求数据"),

    GETBLOCKS(39, "请求区块列表"),
    GETHEADERS(40, "请求区块头"),
    GETADDR(41, "请求地址信息"),
    HEADERS(42, "区块头数据"),

    NOTFOUND(45, "数据未找到"),
    MEMPOOL(46, "内存池信息"),
    REJECT(47, "拒绝请求"),

    UTXOS(49, "未花费输出数据"),
    SENDHEADERS(50, "发送区块头"),

    COINBASE(51, "挖矿"),
    REWARD(52, "奖励"),
    FEE(53, "手续费"),

    //查询分叉点请求
    GET_FORK_POINT_REQ(60, "查询分叉点请求"),
    GET_FORK_POINT_RES(61, "查询分叉点响应"),

    //根据区块高度查询区块
    GET_BLOCK_BY_HEIGHT_REQ(70, "根据区块高度查询区块请求"),
    GET_BLOCK_BY_HEIGHT_RES(71, "根据区块高度查询区块响应"),

    RPC_REQUEST(80, "RPC请求"),
    RPC_RESPONSE(81, "RPC响应"),

    ;

    private final int code;
    private final String description;
    private static final Map<Integer, MessageType> codeMap = new HashMap<>();

    static {
        for (MessageType type : MessageType.values()) {
            codeMap.put(type.code, type);
        }
    }

    public static String getDescriptionByCode(int code){
        MessageType type = codeMap.get(code);
        if (type == null) {
            return "未知消息类型";
        }
        return type != null ? type.getDescription() : "未知消息类型"; // 处理不存在的code
    }

    MessageType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static MessageType fromCode(int code) {
        return codeMap.get(code);
    }
}