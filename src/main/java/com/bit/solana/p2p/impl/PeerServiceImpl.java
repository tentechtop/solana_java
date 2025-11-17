package com.bit.solana.p2p.impl;

import com.bit.solana.p2p.peer.Peer;
import com.bit.solana.p2p.PeerService;
import com.bit.solana.p2p.peer.RoutingTable;
import com.bit.solana.p2p.peer.Settings;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PeerServiceImpl implements PeerService {
    // 消息过期时间
    public static final long MESSAGE_EXPIRATION_TIME = 30;//秒
    // 节点过期时间
    public static final long NODE_EXPIRATION_TIME = 60;//秒
    //节点信息
    private Peer self;//本地节点信息


    @Autowired
    private Settings settings;

    @Autowired
    private RoutingTable routingTable;


    /**
     * 初始化节点信息
     */
    @PostConstruct
    public void init() {
        self = new Peer();



    }


}
