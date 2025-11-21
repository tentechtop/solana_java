package com.bit.solana;

import com.bit.solana.p2p.impl.PeerClient;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;


public class QUICTest {


    //main
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        PeerClient peerClient = new PeerClient();



        peerClient.init(); // 手动调用 init




    }





}
