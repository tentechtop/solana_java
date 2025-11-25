package com.bit.solana.api.mock;

import com.bit.solana.p2p.impl.PeerClient;
import com.bit.solana.p2p.impl.QuicNodeWrapper;
import lombok.extern.slf4j.Slf4j;

import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.bit.solana.p2p.protocol.ProtocolEnum.TEXT_V1;


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
/*

        InetSocketAddress inetSocketAddress = new InetSocketAddress("101.35.87.31",8333);

        byte[] bytes = peerClient.invokeWithReturn(inetSocketAddress, ProtocolEnum.BLOCK_V1, data.getBytes());
        log.info("节点回复：{}", new String(bytes));

        BlockHeader blockHeader = new BlockHeader();
        blockHeader.setSlot(123456);
        byte[] bytes1 = peerClient.invokeWithReturn(inetSocketAddress, ProtocolEnum.CHAIN_V1, blockHeader.serialize());
        BlockHeader deserialize = BlockHeader.deserialize(bytes1);
        log.info("节点回复：{}", deserialize);
*/

        return "";
    }


    /**
     * 连接节点
     */
    @GetMapping("/connect")
    public String connect(String url) throws Exception {
        //节点回复反转换后的数据
        QuicNodeWrapper connect = peerClient.connect(url);
        log.info("节点连接成功：{}", connect);
        return "";
    }

    @GetMapping("/sendMsg")
    public String sendMsg(String nodeId) throws Exception {
        //节点回复反转换后的数据
        byte[] bytes = peerClient.sendData(Base58.decode(nodeId), TEXT_V1, new byte[]{0x01}, 5);
        log.info("节点回复：{}", new String(bytes));
        return new String(bytes);
    }


}
