package com.bit.solana.api.mock;

import com.bit.solana.p2p.impl.PeerClient;
import com.bit.solana.p2p.protocol.ProtocolEnum;
import com.bit.solana.structure.block.BlockHeader;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;

@Slf4j
@RestController
@RequestMapping("/mockSend")
public class SendApi {

    @Autowired
    private PeerClient peerClient;

    //发送数据给指定的节点
    @GetMapping("/sendData")
    public String sendData(String data) throws Exception {
        //节点回复反转换后的数据

        InetSocketAddress inetSocketAddress = new InetSocketAddress("101.35.87.31",8333);

        byte[] bytes = peerClient.invokeWithReturn(inetSocketAddress, ProtocolEnum.BLOCK_V1, data.getBytes());
        log.info("节点回复：{}", new String(bytes));

        BlockHeader blockHeader = new BlockHeader();
        blockHeader.setSlot(123456);
        byte[] bytes1 = peerClient.invokeWithReturn(inetSocketAddress, ProtocolEnum.CHAIN_V1, blockHeader.serialize());
        BlockHeader deserialize = BlockHeader.deserialize(bytes1);
        log.info("节点回复：{}", deserialize);

        return new String(bytes);
    }


}
