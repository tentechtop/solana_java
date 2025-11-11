package com.bit.solana.structure.account.json;

import lombok.Data;

@Data
public class AccountDTO {
    private String pubkey;

    private long balance;

}
