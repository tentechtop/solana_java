package com.bit.solana.netversion;

import lombok.Getter;

/**
 * 网络版本枚举 - 统一管理主网/测试网/开发网的标识信息
 * 采用1字节前缀区分网络（兼容主流区块链设计，简洁且易扩展）
 */
@Getter
public enum NetVersion {

    // ------------------------------
    // 枚举常量：预定义网络（可根据业务扩展）
    // ------------------------------
    MAINNET(
            (byte) 0x01,    // 1字节主网前缀（唯一标识）
            "mainnet",      // 网络名称（小写，用于日志/API）
            "Main Network"  // 网络描述（全称）
    ),
    TESTNET(
            (byte) 0x02,    // 1字节测试网前缀
            "testnet",
            "Test Network"
    ),
    DEVNET(
            (byte) 0x03,    // 1字节开发网前缀
            "devnet",
            "Development Network"
    );

    // ------------------------------
    // 枚举属性（不可变，通过构造函数初始化）
    // ------------------------------
    private final byte prefix;       // 1字节网络前缀（核心标识，用于地址生成）
    private final String name;       // 网络名称（如"mainnet"，用于业务逻辑判断）
    private final String description;// 网络描述（用于日志/UI展示）

    // 构造函数（私有，仅枚举内部使用）
    NetVersion(byte prefix, String name, String description) {
        this.prefix = prefix;
        this.name = name;
        this.description = description;
    }

    // ------------------------------
    // 工具方法：通过属性反查枚举（核心功能）
    // ------------------------------

    /**
     * 根据1字节前缀查找对应的网络版本
     * @param prefix 网络前缀（1字节）
     * @return 匹配的NetVersion，无匹配则返回null
     */
    public static NetVersion getByPrefix(byte prefix) {
        for (NetVersion version : values()) {
            if (version.prefix == prefix) {
                return version;
            }
        }
        return null;
    }

    /**
     * 根据网络名称（如"mainnet"）查找对应的网络版本
     * @param name 网络名称（不区分大小写）
     * @return 匹配的NetVersion，无匹配则返回null
     */
    public static NetVersion getByName(String name) {
        if (name == null) return null;
        for (NetVersion version : values()) {
            if (version.name.equalsIgnoreCase(name)) {
                return version;
            }
        }
        return null;
    }

    // ------------------------------
    // 辅助方法：简化使用
    // ------------------------------

    /**
     * 获取当前网络的1字节前缀（作为字节数组，方便HMAC等操作）
     * @return 长度为1的字节数组（前缀）
     */
    public byte[] getPrefixBytes() {
        return new byte[]{this.prefix};
    }

    /**
     * 判断当前网络是否为主网
     */
    public boolean isMainnet() {
        return this == MAINNET;
    }

    /**
     * 判断当前网络是否为测试网（含开发网）
     */
    public boolean isTestnet() {
        return this == TESTNET || this == DEVNET;
    }
}