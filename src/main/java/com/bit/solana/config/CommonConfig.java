package com.bit.solana.config;

import com.bit.solana.database.DataBase;
import com.bit.solana.database.rocksDb.TableEnum;
import com.bit.solana.structure.key.KeyInfo;
import com.bit.solana.util.SolanaEd25519Signer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.bit.solana.util.ECCWithAESGCM.generateCurve25519KeyPair;
import static com.bit.solana.util.Ed25519HDWallet.generateMnemonic;
import static com.bit.solana.util.Ed25519HDWallet.getSolanaKeyPair;


@Slf4j
@Component
public class CommonConfig {


    public static byte[][] SelfKey = null;

    @Autowired
    private SystemConfig config;


    @PostConstruct
    public void init() throws Exception {
        DataBase dataBase = config.getDataBase();
        byte[] bytes = dataBase.get(TableEnum.PEER, PEER_KEY);




    }



    // 本地节点标识
    public static final byte[] PEER_KEY = "LOCAL_PEER".getBytes();








    @PreDestroy
    public void shutdown() throws InterruptedException {

    }


}
