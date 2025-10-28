package com.bit.solana.common;

/**
 * 费用计算器哈希（feeCalculatorHash，32字节），标识交易费用计算规则的版本，确保全网计费逻辑一致
 */
public class FeeCalculatorHash extends ByteHash32 {
    // 费用计算器哈希的零值常量（如初始默认费用规则的哈希）
    public static final FeeCalculatorHash ZERO = new FeeCalculatorHash(new byte[HASH_LENGTH]);

    private FeeCalculatorHash(byte[] value) {
        super(value); // 复用基类的32字节校验和不可变性保证
    }

    /**
     * 从字节数组创建FeeCalculatorHash实例
     * @param bytes 32字节的原始哈希数组
     * @return FeeCalculatorHash实例
     */
    public static FeeCalculatorHash fromBytes(byte[] bytes) {
        return new FeeCalculatorHash(bytes);
    }

    /**
     * 从十六进制字符串创建FeeCalculatorHash实例
     * @param hex 64位十六进制字符串（大小写均可）
     * @return FeeCalculatorHash实例
     */
    @Override
    public FeeCalculatorHash fromHex(String hex) {
        byte[] bytes = hexToBytes(hex); // 复用基类的十六进制转字节逻辑
        return new FeeCalculatorHash(bytes);
    }
}