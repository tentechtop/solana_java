package com.bit.solana;

import com.bit.solana.account.impl.AccountServiceImpl;
import com.bit.solana.api.AccountApi;
import com.bit.solana.api.BlockApi;
import com.bit.solana.api.PohApi;
import com.bit.solana.api.TxApi;
import com.bit.solana.api.VersionApi;
import com.bit.solana.blockchain.impl.BlockChainImpl;
import com.bit.solana.blockchain.impl.LedgerCoordinator;
import com.bit.solana.config.NodeProperties;
import com.bit.solana.database.rocksDb.LedgerRocksStore;
import com.bit.solana.poh.impl.POHEngineImpl;
import com.bit.solana.poh.impl.POHServiceImpl;
import com.bit.solana.tx.impl.TxServiceImpl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableConfigurationProperties(NodeProperties.class)
@Import({
        AccountApi.class,
        BlockApi.class,
        PohApi.class,
        TxApi.class,
        VersionApi.class,
        AccountServiceImpl.class,
        TxServiceImpl.class,
        BlockChainImpl.class,
        LedgerCoordinator.class,
        LedgerRocksStore.class,
        POHEngineImpl.class,
        POHServiceImpl.class
})
public class SolanaApplication {
    public static void main(String[] args) {
        SpringApplication.run(SolanaApplication.class, args);
    }
}
