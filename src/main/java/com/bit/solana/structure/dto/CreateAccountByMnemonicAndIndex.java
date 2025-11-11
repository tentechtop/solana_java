package com.bit.solana.structure.dto;

import lombok.Data;

@Data
public class CreateAccountByMnemonicAndIndex {
    private String mnemonic;
    private int accountIndex;
    private int addressIndex;
}
