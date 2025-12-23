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

    }


    /**
     * 判断比特币地址类型
     * @param addressStr 地址字符串（Base58/Bech32格式）
     * @return 地址类型描述
     * @throws AddressFormatException 地址格式非法
     */
    public static String judgeAddressType(String addressStr) throws AddressFormatException {
        // 1. 先解析地址为 Address 实例
        Address address = Address.fromString(null, addressStr);
        String addressType;

        // 2. 判断是 LegacyAddress（传统地址）还是 SegwitAddress（隔离见证地址）
        if (address instanceof LegacyAddress) {
            LegacyAddress legacyAddress = (LegacyAddress) address;
            Script.ScriptType scriptType = legacyAddress.getOutputScriptType();
            if (scriptType == Script.ScriptType.P2PKH) {
                addressType = "P2PKH（传统单公钥地址，Base58编码，主网以1开头）";
            } else if (scriptType == Script.ScriptType.P2SH) {
                addressType = "P2SH（传统脚本/多签地址，Base58编码，主网以3开头）";
            } else {
                addressType = "未知传统地址类型";
            }
        } else if (address instanceof SegwitAddress) {
            SegwitAddress segwitAddress = (SegwitAddress) address;
            Script.ScriptType scriptType = segwitAddress.getOutputScriptType();
            if (scriptType == Script.ScriptType.P2WPKH) {
                addressType = "P2WPKH（隔离见证单公钥地址，Bech32编码，主网以bc1q开头）";
            } else if (scriptType == Script.ScriptType.P2WSH) {
                addressType = "P2WSH（隔离见证脚本/多签地址，Bech32编码，主网以bc1q开头）";
            } else if (scriptType == Script.ScriptType.P2TR) {
                addressType = "P2TR（Taproot隔离见证地址，Bech32编码，主网以bc1p开头）";
            } else {
                addressType = "未知隔离见证地址类型";
            }
        } else {
            addressType = "不支持的地址类型";
        }

        // 补充地址网络信息
        NetworkParameters params = address.getParameters();
        String networkInfo = params == MainNetParams.get() ? "主网" : (params == TestNet3Params.get() ? "测试网" : "未知网络");
        return String.format("[%s] - %s", networkInfo, addressType);
    }

    // ===================== 新增工具方法2：从地址恢复对应哈希（公钥哈希/脚本哈希） =====================
    /**
     * 从地址恢复哈希（单向不可逆，无法恢复原始公钥/脚本）
     * @param addressStr 地址字符串
     * @return 哈希信息（16进制格式）
     * @throws AddressFormatException 地址格式非法
     */
    public static String recoverHashFromAddress(String addressStr) throws AddressFormatException {
        Address address = Address.fromString(null, addressStr);
        byte[] hash = address.getHash(); // 核心方法：获取地址对应的核心哈希
        String hashType;

        if (address instanceof LegacyAddress) {
            LegacyAddress legacyAddress = (LegacyAddress) address;
            if (legacyAddress.getOutputScriptType() == Script.ScriptType.P2PKH) {
                hashType = "公钥哈希（PubKeyHash）";
            } else {
                hashType = "脚本哈希（ScriptHash）";
            }
        } else if (address instanceof SegwitAddress) {
            SegwitAddress segwitAddress = (SegwitAddress) address;
            if (segwitAddress.getOutputScriptType() == Script.ScriptType.P2WPKH) {
                hashType = "公钥哈希（PubKeyHash，见证程序）";
            } else {
                hashType = "脚本SHA256哈希（ScriptSHA256Hash，见证程序）";
            }
        } else {
            hashType = "未知哈希类型";
        }

        return String.format("%s：%s（长度：%d字节）", hashType, Utils.HEX.encode(hash), hash.length);
    }



}