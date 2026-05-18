package com.bit.solana.tx;

import com.bit.solana.result.Result;
import com.bit.solana.structure.dto.SubmitLedgerTransactionRequest;
import com.bit.solana.structure.dto.SubmitLedgerTransactionResponse;
import com.bit.solana.structure.vo.LedgerTransactionVO;

public interface TxService {
    Result<SubmitLedgerTransactionResponse> submitTx(SubmitLedgerTransactionRequest request);

    Result<LedgerTransactionVO> getTransaction(String transactionId);
}
