package com.bit.solana.p2p.protocol.impl;

import com.bit.solana.p2p.protocol.ProtocolHandler;

public class BlockProtocolHandler implements ProtocolHandler.ResultProtocolHandler {

    @Override
    public byte[] handleResult(byte[] requestParams) {
        return new byte[0];
    }
}
