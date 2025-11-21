package com.bit.solana.p2p.impl;

import com.bit.solana.p2p.PeerService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.cert.CertificateException;

@Slf4j
@Data
@Component
public class PeerServiceImpl implements PeerService {




    @Override
    public void init() throws IOException, CertificateException {

    }
}
