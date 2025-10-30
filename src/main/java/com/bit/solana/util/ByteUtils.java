package com.bit.solana.util;

import java.nio.ByteBuffer;

public class ByteUtils {

    /**
     * long 类型转 byte[]
     *
     * @param val
     * @return
     */
    public static byte[] longToBytes(long val) {
        return ByteBuffer.allocate(Long.BYTES).putLong(val).array();
    }

    /**
     * byte[] to long
     */
    public static long bytesToLong(byte[] bytes) {
        return ByteBuffer.wrap(bytes).getLong();
    }

    /**
     * 字节数组转十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * 十六进制字符串转字节数组
     */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }


}
