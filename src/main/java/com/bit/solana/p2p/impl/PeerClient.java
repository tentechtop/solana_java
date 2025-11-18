package com.bit.solana.p2p.impl;

import io.netty.buffer.ByteBuf;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

@Slf4j
@Component
public class PeerClient {

    //主动连接别人

    //和其他节点建立的连接


    /**
     * 连接指定节点  连接成功后 添加到路由表
     */


    // 方法1：无返回值，仅发送数据（参数：目标地址 + 数据）
    public void sendData(InetSocketAddress targetAddr, byte[] data) throws Exception {
        // 实现发送逻辑（不等待响应）
    }

    // 方法2：有返回值，发送并等待响应
    public byte[] sendData(InetSocketAddress targetAddr, byte[] requestData, long timeout) throws Exception {
        // 实现发送并等待响应的逻辑
        return null;
    }

    /**
     * 维护通道  断线重连  重试失败 后删除节点 并从路由表删除
     */

}
