package com.bit.solana.api;

import com.bit.solana.result.Result;
import com.bit.solana.structure.dto.SubmitLedgerTransactionRequest;
import com.bit.solana.structure.dto.SubmitLedgerTransactionResponse;
import com.bit.solana.structure.vo.LedgerTransactionVO;
import com.bit.solana.tx.TxService;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tx")
public class TxApi {
    private final TxService txService;

    public TxApi(TxService txService) {
        this.txService = txService;
    }

    @PostMapping("/submit")
    public Result<SubmitLedgerTransactionResponse> submit(@RequestBody SubmitLedgerTransactionRequest request) {
        return txService.submitTx(request);
    }

    @GetMapping("/{transactionId}")
    public Result<LedgerTransactionVO> getTransaction(@PathVariable String transactionId) {
        return txService.getTransaction(transactionId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException exception) {
        return Result.error(exception.getMessage());
    }
}
