package com.bit.solana.api;

import com.bit.solana.poh.POHService;
import com.bit.solana.result.Result;
import com.bit.solana.structure.vo.PohStatusVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/poh")
public class PohApi {
    private final POHService pohService;

    public PohApi(POHService pohService) {
        this.pohService = pohService;
    }

    @GetMapping("/status")
    public Result<PohStatusVO> status() {
        return Result.OKData(pohService.getStatus());
    }
}
