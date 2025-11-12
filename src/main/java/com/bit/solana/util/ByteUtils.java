package com.bit.solana.util;

import java.nio.ByteBuffer;

public class ByteUtils {

    /**
     * long 类型转 byte[]
     *
     * @return
     */
    public static byte[] longToBytes(long value) {
        byte[] bytes = new byte[8]; // long 占 8 字节，必须初始化长度为 8
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (value >>> (8 * i)); // 小端模式（低位在前）
        }
        return bytes;
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


    public static byte[] combine(byte[] lastHash, byte[] data, long sequence) {
        byte[] sequenceBytes = ByteUtils.longToBytes(sequence);
        byte[] combined = new byte[lastHash.length + data.length + sequenceBytes.length];
        System.arraycopy(lastHash, 0, combined, 0, lastHash.length);
        System.arraycopy(data, 0, combined, lastHash.length, data.length);
        System.arraycopy(sequenceBytes, 0, combined, lastHash.length + data.length, sequenceBytes.length);
        return combined;
    }


/*    public byte[] combine(byte[] lastHash, byte[] data, long sequence) {
        byte[] sequenceBytes = longToBytes(sequence);
        byte[] combined = new byte[lastHash.length + data.length + sequenceBytes.length];
        System.arraycopy(lastHash, 0, combined, 0, lastHash.length);
        System.arraycopy(data, 0, combined, lastHash.length, data.length);
        System.arraycopy(sequenceBytes, 0, combined, lastHash.length + data.length, sequenceBytes.length);
        return combined;
    }*/


}
