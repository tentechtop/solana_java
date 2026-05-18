package com.bit.solana.util;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class LedgerCryptoSupport {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private LedgerCryptoSupport() {
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate Ed25519 key pair", exception);
        }
    }

    public static String encodePublicKey(PublicKey publicKey) {
        return ENCODER.encodeToString(publicKey.getEncoded());
    }

    public static String encodePrivateKey(PrivateKey privateKey) {
        return ENCODER.encodeToString(privateKey.getEncoded());
    }

    public static String encodeSignature(byte[] signature) {
        return ENCODER.encodeToString(signature);
    }

    public static byte[] decodeSignature(String signature) {
        return DECODER.decode(signature);
    }

    public static PrivateKey decodePrivateKey(String encodedPrivateKey) {
        try {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(DECODER.decode(encodedPrivateKey));
            return KeyFactory.getInstance("Ed25519").generatePrivate(keySpec);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid private key", exception);
        }
    }

    public static PublicKey decodePublicKey(String encodedPublicKey) {
        try {
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(DECODER.decode(encodedPublicKey));
            return KeyFactory.getInstance("Ed25519").generatePublic(keySpec);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid public key", exception);
        }
    }

    public static byte[] sign(byte[] payload, String encodedPrivateKey) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(decodePrivateKey(encodedPrivateKey));
            signature.update(payload);
            return signature.sign();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to sign transaction payload", exception);
        }
    }

    public static boolean verify(byte[] payload, byte[] signatureBytes, String encodedPublicKey) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(decodePublicKey(encodedPublicKey));
            verifier.update(payload);
            return verifier.verify(signatureBytes);
        } catch (Exception exception) {
            return false;
        }
    }

    public static boolean verify(byte[] payload, String encodedSignature, String encodedPublicKey) {
        return verify(payload, decodeSignature(encodedSignature), encodedPublicKey);
    }

    public static String accountIdFromPublicKey(String encodedPublicKey) {
        return LedgerHashSupport.sha256Hex(DECODER.decode(encodedPublicKey));
    }
}
