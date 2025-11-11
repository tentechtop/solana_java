package com.bit.solana.structure.dto;

import com.bit.solana.common.Pubkey;
import lombok.Data;

@Data
public class AccountDTO {
    private String pubkey;

    private long balance;

}
