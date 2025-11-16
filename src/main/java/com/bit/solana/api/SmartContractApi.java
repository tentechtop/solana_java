package com.bit.solana.api;

import com.bit.solana.monitor.impl.dto.CpuMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/smartContract")
public class SmartContractApi {


    @GetMapping("/test")
    public void test() {

    }


}
