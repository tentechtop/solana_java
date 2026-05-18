package com.bit.solana.api;

import com.bit.solana.account.AccountService;
import com.bit.solana.result.Result;
import com.bit.solana.structure.dto.CreateLedgerAccountRequest;
import com.bit.solana.structure.dto.CreateLedgerAccountResponse;
import com.bit.solana.structure.dto.SubmitLedgerTransactionRequest;
import com.bit.solana.structure.dto.SubmitLedgerTransactionResponse;
import com.bit.solana.structure.vo.LedgerAccountVO;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/account")
public class AccountApi {
    private final AccountService accountService;

    public AccountApi(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/createAccount")
    public Result<CreateLedgerAccountResponse> createAccount(
            @RequestBody(required = false) CreateLedgerAccountRequest request
    ) {
        return accountService.createAccount(request);
    }

    @GetMapping("/{accountId}")
    public Result<LedgerAccountVO> getAccount(@PathVariable String accountId) {
        return accountService.getAccount(accountId);
    }

    @GetMapping("/detail")
    public Result<LedgerAccountVO> getAccountDetail(@RequestParam String accountId) {
        return accountService.getAccount(accountId);
    }

    @GetMapping("/balance")
    public Result<Long> getBalance(@RequestParam String accountId) {
        return accountService.getBalance(accountId);
    }

    @PostMapping("/transfer")
    public Result<SubmitLedgerTransactionResponse> transfer(@RequestBody SubmitLedgerTransactionRequest request) {
        return accountService.transfer(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException exception) {
        return Result.error(exception.getMessage());
    }
}
