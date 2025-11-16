package com.bit.solana.util;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ClassUtil {

    /**
     * 读取class文件为字节数组
     * @param filePath class文件路径
     * @return 字节数组
     * @throws IOException 读取异常
     */
    public static byte[] readClassFile(String filePath) throws IOException {
        File file = new File(filePath);
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 读取class文件并压缩为字节数组（使用GZIP）
     * @param filePath class文件路径
     * @return 压缩后的字节数组
     * @throws IOException 读写或压缩异常
     */
    public static byte[] readAndCompressClassFile(String filePath) throws IOException {
        byte[] originalBytes = readClassFile(filePath); // 先读取原始字节码
        // 使用GZIP压缩
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOS = new GZIPOutputStream(bos)) {
            gzipOS.write(originalBytes);
            gzipOS.finish(); // 完成压缩
            return bos.toByteArray();
        }
    }

    /**
     * 解压压缩后的字节数组（对应GZIP压缩）
     * @param compressedBytes 压缩后的字节数组
     * @return 原始class字节码
     * @throws IOException 解压异常
     */
    public static byte[] decompressClassBytes(byte[] compressedBytes) throws IOException {
        try (GZIPInputStream gzipIS = new GZIPInputStream(new ByteArrayInputStream(compressedBytes));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipIS.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }


}
