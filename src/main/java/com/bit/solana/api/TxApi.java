package com.bit.solana.api;

import com.bit.solana.tx.TxService;
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
     * 查询
     */




}
