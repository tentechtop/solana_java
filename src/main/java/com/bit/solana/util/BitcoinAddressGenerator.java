package com.bit.solana.util;

import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.*;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.bit.solana.util.ByteUtils.bytesToHex;

@Slf4j
public class BitcoinAddressGenerator {
    /**
     * path路径
     */
    private final static ImmutableList<ChildNumber> BIP44_ETH_ACCOUNT_ZERO_PATH =
            ImmutableList.of(new ChildNumber(44, true), new ChildNumber(60, true),
                    ChildNumber.ZERO_HARDENED, ChildNumber.ZERO);



    void createWallet() throws IOException {
        NetworkParameters params = TestNet3Params.get();
        Wallet wallet = Wallet.createDeterministic(params, Script.ScriptType.P2PKH);
        File walletFile = new File("baeldung.dat");
        wallet.saveToFile(walletFile);
    }

    Wallet loadWallet() throws IOException, UnreadableWalletException {
        File walletFile = new File("baeldung.dat");
        Wallet wallet = Wallet.loadFromFile(walletFile);
        log.info("地址: " + wallet.currentReceiveAddress().toString());
        log.info("助记词: " + wallet.getKeyChainSeed().getMnemonicString());
        log.info("余额: " + wallet.getBalance().toFriendlyString());
        log.info("公钥: " + wallet.findKeyFromAddress(wallet.currentReceiveAddress()).getPublicKeyAsHex());
        log.info("私钥: " + wallet.findKeyFromAddress(wallet.currentReceiveAddress()).getPrivateKeyAsHex());
        return wallet;
    }

    Wallet loadUsingSeed(String seedWord) throws UnreadableWalletException {
        NetworkParameters params = TestNet3Params.get();
        DeterministicSeed seed = new DeterministicSeed(seedWord, null, "", Utils.currentTimeSeconds());
        return Wallet.fromSeed(params, seed);
    }



    public static void main(String[] args) {
        ECKey ecKey = new ECKey();
        NetworkParameters networkParameters = null;
        networkParameters = MainNetParams.get();
        String privateKeyAsHex = ecKey.getPrivateKeyAsHex();
        log.info("私钥: " + privateKeyAsHex);
        // 额外：获取公钥（16进制格式）
        String publicKeyAsHex = ecKey.getPublicKeyAsHex();
        log.info("公钥（16进制）: " + publicKeyAsHex);

        // 4. 生成 P2PKH 传统地址（最常用的传统比特币地址，以1开头）
        Address p2pkhAddress = Address.fromKey(networkParameters, ecKey, Script.ScriptType.P2PKH);
        log.info("P2PKH 传统地址（以1开头）: " + p2pkhAddress.toString());

        // 5. 生成 P2WPKH 隔离见证地址（以bc1开头，手续费更低、更安全）
        Address p2wpkhAddress = Address.fromKey(networkParameters, ecKey, Script.ScriptType.P2WPKH);
        log.info("P2WPKH 隔离见证地址（以bc1开头）: " + p2wpkhAddress.toString());


        // ===================== 1. 准备工作：指定网络 + 生成多个 ECKey 密钥对 =====================
        // 指定比特币网络（主网：MainNetParams；测试网：TestNet3Params，按需切换）
        NetworkParameters networkParams = MainNetParams.get();
        // 生成3个 ECKey 密钥对（用于构建 2-of-3 多签脚本，可根据需求增减数量）
        ECKey key1 = new ECKey();
        ECKey key2 = new ECKey();
        ECKey key3 = new ECKey();
        // 存储所有 ECKey，方便后续输出私钥（仅用于演示，实际场景需妥善保管私钥）
        List<ECKey> ecKeyList = Arrays.asList(key1, key2, key3);

        Script multiSigOutputScript = ScriptBuilder.createMultiSigOutputScript(2,ecKeyList);
        byte[] scriptProgram = multiSigOutputScript.getProgram();
        byte[] bytes = Sha.applySHA256(scriptProgram);
        byte[] bytes1 = Sha.applyRIPEMD160(bytes);
        LegacyAddress address3 = LegacyAddress.fromScriptHash(networkParams,bytes1);
        log.info("2-of-3 多签地址: " + address3.toString());

        SegwitAddress p2wshAddress = SegwitAddress.fromHash(networkParams, Sha.applySHA256(Sha.applySHA256(scriptProgram)));
        log.info("P2WSH 隔离见证地址（以bc1开头）: " + p2wshAddress.toString());

        Address address = SegwitAddress.fromString(networkParams, p2wshAddress.toString());
        log.info("地址类型: " + address.getOutputScriptType());

        byte[] hash = address.getHash();
        log.info("地址hash: " + bytesToHex(hash));
        log.info("地址hash: " + bytesToHex(p2pkhAddress.getHash()));
        log.info("地址hash: " + bytesToHex(p2wpkhAddress.getHash()));



    }









}