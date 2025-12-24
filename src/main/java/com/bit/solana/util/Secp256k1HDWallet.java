package com.bit.solana.util;

import com.bit.solana.structure.key.KeyInfo;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.SignatureDecodeException;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.encoders.Hex;
import org.bitcoinj.core.Base58;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class Secp256k1HDWallet {
    // BIP-44 标准路径参数（可根据对应链调整 coinType，这里默认比特币，可自定义）
    private static final int BIP44_PURPOSE = 44;
    private static final int BITCOIN_COIN_TYPE = 0; // 比特币:0，以太坊:60，可按需修改
    // Secp256k1 密钥长度（私钥32字节，公钥压缩格式33字节/非压缩格式65字节，这里默认32字节私钥）
    private static final int PRIVATE_KEY_LENGTH = 32;
    // 助记词熵长度（12个单词对应128位熵）
    private static final int MNEMONIC_ENTROPY_LENGTH = 16; // 16*8=128 bits

    // BIP-32 根密钥派生的 HMAC 盐值
    private static final byte[] BIP32_SEED = "Bitcoin seed".getBytes(StandardCharsets.UTF_8);

    /**
     * 生成 12 个单词的助记词（BIP-39 标准）
     */
    public static List<String> generateMnemonic() {
        try {
            byte[] entropy = new byte[MNEMONIC_ENTROPY_LENGTH];
            new SecureRandom().nextBytes(entropy);
            return MnemonicCode.INSTANCE.toMnemonic(entropy);
        } catch (Exception e) {
            throw new RuntimeException("生成助记词失败", e);
        }
    }

    /**
     * 从助记词生成种子（BIP-39 标准）
     */
    public static byte[] generateSeed(List<String> mnemonic, String passphrase) {
        try {
            MnemonicCode.INSTANCE.check(mnemonic);
            return MnemonicCode.toSeed(mnemonic, passphrase);
        } catch (MnemonicException e) {
            throw new RuntimeException("生成种子失败", e);
        }
    }

    /**
     * 从种子生成 Secp256k1 根私钥（BIP-32 标准）
     */
    public static byte[] generateRootPrivateKey(byte[] seed) {
        if (seed.length != 64) {
            throw new IllegalArgumentException("种子必须为 64 字节（512 位）");
        }
        HMac hmac = new HMac(new SHA512Digest());
        hmac.init(new KeyParameter(BIP32_SEED));
        hmac.update(seed, 0, seed.length);
        byte[] hmacResult = new byte[64]; // HMAC-SHA512 结果固定64字节
        hmac.doFinal(hmacResult, 0);
        // 前32字节为根私钥，后32字节为链码（这里仅返回私钥，如需链码可单独存储）
        return Arrays.copyOfRange(hmacResult, 0, PRIVATE_KEY_LENGTH);
    }

    /**
     * 派生 Secp256k1 子私钥（BIP-32 标准，支持强化/非强化派生）
     * 注：基于 bitcoinj 的 HDKeyDerivation 实现，更稳定可靠
     */
    public static byte[] deriveChildPrivateKey(byte[] parentPrivateKey, int index) {
        if (parentPrivateKey.length != PRIVATE_KEY_LENGTH) {
            throw new IllegalArgumentException("父私钥必须为 32 字节");
        }

        // 构建父确定性密钥（链码这里默认用空？实际应从根密钥派生时获取链码，这里简化处理，完整场景需传入链码）
        // 完整流程：根密钥派生时返回 私钥+链码，这里为了和 Ed25519HDWallet 方法对齐，做简化
        DeterministicKey parentKey = HDKeyDerivation.createMasterPrivateKey(parentPrivateKey);
        DeterministicKey childKey = HDKeyDerivation.deriveChildKey(parentKey, index);

        // 返回子私钥的字节数组
        return childKey.getPrivKeyBytes();
    }

    /**
     * 从 Secp256k1 私钥派生公钥（非压缩格式，如需压缩格式可调整）
     */
    public static byte[] derivePublicKeyFromPrivateKey(byte[] privateKey) {
        if (privateKey.length != PRIVATE_KEY_LENGTH) {
            throw new IllegalArgumentException("私钥必须为 32 字节");
        }
        ECKey ecKey = ECKey.fromPrivate(privateKey);
        return ecKey.getPubKey(); // 非压缩格式65字节，ecKey.getPubKeyHash() 是160位哈希，ecKey.getCompressedPubKey() 是33字节
    }

    /**
     * 生成 Secp256k1 对应的地址（Base58 编码，这里默认比特币地址格式，可按需调整）
     */
    public static String getAddress(byte[] publicKey) {
        ECKey ecKey = ECKey.fromPublicOnly(publicKey);
        // 比特币地址：公钥哈希(Base58Check编码)，如需以太坊地址可改为：Hex.toHexString(ecKey.getPubKeyHash()).substring(24)
        return Base58.encode(ecKey.getPubKeyHash());
    }

    /**
     * 从助记词生成 Secp256k1 密钥对（完整流程，对应 BIP-44 路径）
     */
    public static KeyInfo getKeyPair(List<String> mnemonic, int accountIndex, int addressIndex) {
        byte[] seed = generateSeed(mnemonic, "");
        log.debug("种子(hex): {}", Hex.toHexString(seed));

        byte[] rootPrivateKey = generateRootPrivateKey(seed);
        log.debug("根私钥(hex): {}", Hex.toHexString(rootPrivateKey));

        // 派生路径：m/44'/coinType'/account'/0/addressIndex
        byte[] childKey = rootPrivateKey;
        childKey = deriveChildPrivateKey(childKey, BIP44_PURPOSE | 0x80000000); // 44' 强化派生
        childKey = deriveChildPrivateKey(childKey, BITCOIN_COIN_TYPE | 0x80000000); // 币种' 强化派生
        childKey = deriveChildPrivateKey(childKey, accountIndex | 0x80000000); // 账户' 强化派生
        childKey = deriveChildPrivateKey(childKey, 0); // 0 非强化派生
        childKey = deriveChildPrivateKey(childKey, addressIndex); // 地址索引 非强化派生

        byte[] publicKey = derivePublicKeyFromPrivateKey(childKey);
        String address = getAddress(publicKey);
        String path = String.format("m/44'/%d'/%d'/0/%d", BITCOIN_COIN_TYPE, accountIndex, addressIndex);

        return new KeyInfo(childKey, publicKey, address, path);
    }

    /**
     * int 转 4 字节数组（大端序，兼容 BIP-32 派生需求）
     */
    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    // 测试方法
    public static void main(String[] args) throws SignatureDecodeException {
        // 1. 生成助记词
        List<String> mnemonic = generateMnemonic();
        System.out.println("助记词: " + String.join(" ", mnemonic));

        // 2. 生成密钥对
        KeyInfo keyInfo = getKeyPair(mnemonic, 0, 0);
        System.out.println("派生路径: " + keyInfo.getPath());
        System.out.println("私钥(hex): " + Hex.toHexString(keyInfo.getPrivateKey()));
        System.out.println("公钥(hex): " + Hex.toHexString(keyInfo.getPublicKey()));
        System.out.println("对应地址: " + keyInfo.getAddress());

        // 3. 验证地址一致性
        KeyInfo keyInfo2 = getKeyPair(mnemonic, 0, 0);
        System.out.println("地址一致性验证: " + keyInfo.getAddress().equals(keyInfo2.getAddress())); // true

        // 4. 派生不同账户/地址
        KeyInfo keyInfo3 = getKeyPair(mnemonic, 1, 0); // 账户1
        KeyInfo keyInfo4 = getKeyPair(mnemonic, 0, 1); // 地址1
        System.out.println("账户1 地址: " + keyInfo3.getAddress());
        System.out.println("地址1 地址: " + keyInfo4.getAddress());

        // 5. 签名与验证示例（基于 bitcoinj 的 ECKey 签名）
        byte[] data = "测试Secp256k1签名".getBytes(StandardCharsets.UTF_8);

        byte[] hash = Sha256Hash.hash(data);

        ECKey ecKey = ECKey.fromPrivate(keyInfo.getPrivateKey());

        // 签名
        long signStartTime = System.nanoTime();
        byte[] signature = ecKey.sign(Sha256Hash.wrap(hash)).encodeToDER();
        long signEndTime = System.nanoTime();
        double signDurationMillis = (signEndTime - signStartTime) / 1_000_000.0;

        // 验证
        long verifyStartTime = System.nanoTime();
        boolean verifyResult = ecKey.verify(hash, signature);
        long verifyEndTime = System.nanoTime();
        double verifyDurationMillis = (verifyEndTime - verifyStartTime) / 1_000_000.0;

        System.out.println("签名耗时: " + signDurationMillis + " 毫秒");
        System.out.println("验证耗时: " + verifyDurationMillis + " 毫秒");
        System.out.println("签名验证结果: " + verifyResult); // true
    }
}