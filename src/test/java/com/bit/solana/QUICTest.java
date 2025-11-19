package com.bit.solana;

import com.bit.solana.p2p.impl.PeerClient;
import com.bit.solana.p2p.impl.PeerServiceImpl;
import com.bit.solana.p2p.peer.RoutingTable;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;


public class QUICTest {


    //main
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        PeerClient peerClient = new PeerClient();



        peerClient.init(); // 手动调用 init

        // 手动连接本地测试节点
        peerClient.connect(new InetSocketAddress("127.0.0.1", 8333));


    }





}
