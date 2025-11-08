package com.bit.solana.api;

import com.bit.solana.result.Result;
import com.bit.solana.service.TxService;
import com.bit.solana.structure.tx.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/tx")
public class TxApi {

    @Autowired
    private TxService txService;


    /**
     * 通过Http提交一笔交易
     * @param tx
     * @return
     */
    public Result<String> submitTx(Transaction tx) {
        return  txService.submitTx(tx);
    }

}
