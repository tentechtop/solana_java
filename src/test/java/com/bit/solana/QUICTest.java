package com.bit.solana;

import java.net.InetSocketAddress;
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
