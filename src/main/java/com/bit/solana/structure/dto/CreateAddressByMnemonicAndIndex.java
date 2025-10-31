package com.bit.solana.structure.dto;

import lombok.Data;

@Data
public class CreateAddressByMnemonicAndIndex {
    private String mnemonic;
    private int index;
}
