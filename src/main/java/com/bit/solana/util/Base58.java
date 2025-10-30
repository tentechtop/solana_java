package com.bit.solana.util;

import java.util.Arrays;

public class Base58 {
    private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final int[] INDEXES = new int[128];

    static {
        Arrays.fill(INDEXES, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            INDEXES[ALPHABET[i]] = i;
        }
    }

    public static String encode(byte[] input) {
        if (input.length == 0) {
            return "";
        }
        input = Arrays.copyOf(input, input.length);
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            ++zeros;
        }
        byte[] temp = new byte[input.length * 2];
        int length = 0;
        int startAt = zeros;
        while (startAt < input.length) {
            byte mod = divmod58(input, startAt);
            if (input[startAt] == 0) {
                ++startAt;
            }
            temp[temp.length - 1 - length] = (byte) ALPHABET[mod & 0xFF];
            ++length;
        }
        while (length < temp.length && temp[temp.length - length - 1] == ALPHABET[0]) {
            ++length;
        }
        while (--zeros >= 0) {
            temp[temp.length - length - 1] = (byte) ALPHABET[0];
            ++length;
        }
        byte[] output = new byte[length];
        System.arraycopy(temp, temp.length - length, output, 0, length);
        return new String(output);
    }

    public static byte[] decode(String input) {
        if (input.isEmpty()) {
            return new byte[0];
        }
        byte[] input58 = new byte[input.length()];
        for (int i = 0; i < input.length(); ++i) {
            char c = input.charAt(i);
            input58[i] = (byte) ((c < 128) ? INDEXES[c] : -1);
        }
        int zeros = 0;
        while (zeros < input58.length && input58[zeros] == 0) {
            ++zeros;
        }
        byte[] temp = new byte[input.length()];
        int length = 0;
        int startAt = zeros;
        while (startAt < input58.length) {
            byte mod = divmod256(input58, startAt);
            if (input58[startAt] == 0) {
                ++startAt;
            }
            temp[temp.length - 1 - length] = mod;
            ++length;
        }
        while (length < temp.length && temp[temp.length - length - 1] == 0) {
            --length;
        }
        length += zeros;
        byte[] output = new byte[length];
        System.arraycopy(temp, temp.length - length, output, 0, length);
        return output;
    }

    private static byte divmod58(byte[] number, int startAt) {
        int remainder = 0;
        for (int i = startAt; i < number.length; ++i) {
            int digit = (int) number[i] & 0xFF;
            int temp = remainder * 256 + digit;
            digit = temp / 58;
            remainder = temp % 58;
            number[i] = (byte) digit;
        }
        return (byte) remainder;
    }

    private static byte divmod256(byte[] number58, int startAt) {
        int remainder = 0;
        for (int i = startAt; i < number58.length; ++i) {
            int digit = (int) number58[i] & 0xFF;
            if (digit == -1) {
                throw new IllegalArgumentException("Invalid Base58 character");
            }
            int temp = remainder * 58 + digit;
            digit = temp / 256;
            remainder = temp % 256;
            number58[i] = (byte) digit;
        }
        return (byte) remainder;
    }
}