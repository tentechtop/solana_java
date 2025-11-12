package com.bit.solana.service.impl;

import com.bit.solana.common.BlockHash;
import com.bit.solana.common.PubkeyHash;
import com.bit.solana.result.Result;
import com.bit.solana.service.AccountService;
import com.bit.solana.structure.account.AccountMeta;
import com.bit.solana.structure.account.json.AccountDTO;
import com.bit.solana.structure.dto.CreateAccountByMnemonicAndIndex;
import com.bit.solana.structure.key.KeyInfo;
import com.bit.solana.structure.tx.Instruction;
import com.bit.solana.structure.tx.Signature;
import com.bit.solana.structure.tx.Transaction;
import com.bit.solana.structure.tx.json.TransferTx;
import com.bit.solana.txpool.TxPool;
import com.bit.solana.util.ByteUtils;
import com.bit.solana.util.Ed25519Signer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.List;

import static com.bit.solana.util.Ed25519HDWallet.generateMnemonic;
import static com.bit.solana.util.Ed25519HDWallet.getSolanaKeyPair;

/**
 * 租户模式  当账户金额大于等于 10sol  永久保留账户 当小于10sol  每月扣除1SOL  直到余额为零 等待清除
 */

@Slf4j
@Component
public class AccountServiceImpl implements AccountService {

    @Autowired
    private TxPool txPool;

    //生成助记词
    @Override
    public Result createMnemonic() {
        List<String> mnemonic = generateMnemonic();
        String mnemonicStr = String.join(" ", mnemonic);
        return Result.OKData(mnemonicStr);
    }

    @Override
    public Result createAccount(CreateAccountByMnemonicAndIndex req) {
        String mnemonic = req.getMnemonic();
        String[] s = mnemonic.split(" ");
        KeyInfo baseKey = getSolanaKeyPair(List.of(s), req.getAccountIndex(), req.getAddressIndex());
        return Result.OKData(baseKey.getAddress());
    }

    @Override
    public Result<Long> getBalance(String publicKey) {
        return Result.OKData((long)0);
    }

    @Override
    public Result<String> transfer(String fromPublicKey, String toPublicKey, long lamports, String privateKey) {
        return null;
    }

    @Override
    public Result<AccountDTO> getAccountDetail(String publicKey) {
        AccountDTO accountDTO = new AccountDTO();
        return Result.OK(accountDTO);
    }

    @Override
    public Result<String> submitTx(TransferTx transferTx) {
        Transaction solanaTx  = convertToSolanaTx(transferTx);
        // 2. 验证交易基本格式
        if (!txPool.validateTransaction(solanaTx)) {
            return Result.error("交易格式验证失败");
        }
        // 3. 提交到交易池
        return txPool.addTransaction(solanaTx);
    }

    // 核心修正：使用HexToBytes转换公钥/哈希
    private Transaction convertToSolanaTx(TransferTx transferTx) {
        Transaction tx = new Transaction();
        byte[] privateKeyBytes = ByteUtils.hexToBytes(transferTx.getPrivateKey());
        PrivateKey privateKey = Ed25519Signer.recoverPrivateKeyFromCore(privateKeyBytes);
        AccountMeta sender = new AccountMeta(
                PubkeyHash.fromBytes(ByteUtils.hexToBytes(transferTx.getFromAddress())),
                true,
                true);
        // 接收者账户（可写+非签名者）
        AccountMeta receiver = new AccountMeta(
                PubkeyHash.fromBytes(ByteUtils.hexToBytes(transferTx.getToAddress())),
                true,
                true
        );
        List<AccountMeta> accounts = List.of(sender, receiver);
        // 添加相关账户（只读）
        if (transferTx.getRelatedAccounts() != null) {
            for (String addrHex : transferTx.getRelatedAccounts()) {
                accounts.add(new AccountMeta(
                        PubkeyHash.fromBytes(ByteUtils.hexToBytes(addrHex)),
                        false, // 只读
                        false  // 非签名者
                ));
            }
        }
        tx.setAccounts(accounts);
        // 3. 处理智能合约指令
        if (transferTx.getInstruction() != null && !transferTx.getInstruction().isEmpty()) {
            Instruction instruction = new Instruction();
            // 合约地址即程序ID（Hex转字节）
            instruction.setProgramId(ByteUtils.hexToBytes(transferTx.getToAddress()));
            // 指令数据（JSON字符串转字节）
            instruction.setData(transferTx.getInstructionData().getBytes(StandardCharsets.UTF_8));
            tx.setInstructions(List.of(instruction));
        }
        // 4. 处理最近区块哈希（Hex转字节）
        tx.setRecentBlockhash(new BlockHash(
                ByteUtils.hexToBytes(transferTx.getRecentBlockhash())
        ));
        byte[] signatureBytes = Ed25519Signer.applySignature(privateKey, tx.buildSignData());
        // 4. 设置签名列表
        tx.setSignatures(List.of(new Signature(signatureBytes)));
        // 5. 生成交易ID（Solana交易ID为第一个签名的前32字节）
        byte[] txId = Arrays.copyOfRange(signatureBytes, 0, 32);
        tx.setTxId(txId);
        return tx;
    }



}
