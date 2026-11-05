package com.pnh.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM envelope encryption (research R13): a fresh data key per record, wrapped with the master key.
 * Blob layout: {@code [1 version][1 keyIdLen][keyId][12 wrapIv][48 wrappedDek][12 iv][ciphertext+tag]}.
 */
public final class EnvelopeEncryptor {

    private static final byte VERSION = 1;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int WRAPPED_DEK_LEN = 32 + 16;

    private final SecretKey masterKey;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeEncryptor(String base64MasterKey, String keyId) {
        if (base64MasterKey == null || base64MasterKey.isBlank()) {
            throw new CryptoException("pnh.crypto.master-key is not configured");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64MasterKey.trim());
        } catch (IllegalArgumentException e) {
            throw new CryptoException("pnh.crypto.master-key is not valid base64", e);
        }
        if (raw.length != 32) {
            throw new CryptoException("pnh.crypto.master-key must be 32 bytes (AES-256)");
        }
        if (keyId == null || keyId.isBlank() || keyId.length() > 100) {
            throw new CryptoException("invalid master key id");
        }
        this.masterKey = new SecretKeySpec(raw, "AES");
        this.keyId = keyId;
    }

    public String keyId() {
        return keyId;
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] dek = new byte[32];
            random.nextBytes(dek);
            byte[] wrapIv = iv();
            byte[] wrapped = gcm(Cipher.ENCRYPT_MODE, masterKey, wrapIv, dek);
            byte[] iv = iv();
            byte[] ct = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), iv, plaintext);
            byte[] kid = keyId.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(2 + kid.length + IV_LEN + wrapped.length + IV_LEN + ct.length);
            buf.put(VERSION)
                    .put((byte) kid.length)
                    .put(kid)
                    .put(wrapIv)
                    .put(wrapped)
                    .put(iv)
                    .put(ct);
            return buf.array();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] blob) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(blob);
            if (buf.get() != VERSION) {
                throw new CryptoException("unsupported ciphertext version");
            }
            byte[] kid = new byte[buf.get() & 0xFF];
            buf.get(kid);
            byte[] wrapIv = new byte[IV_LEN];
            buf.get(wrapIv);
            byte[] wrapped = new byte[WRAPPED_DEK_LEN];
            buf.get(wrapped);
            byte[] iv = new byte[IV_LEN];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            byte[] dek = gcm(Cipher.DECRYPT_MODE, masterKey, wrapIv, wrapped);
            return gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), iv, ct);
        } catch (GeneralSecurityException | RuntimeException e) {
            if (e instanceof CryptoException ce) {
                throw ce;
            }
            throw new CryptoException("decryption failed (tampered data or wrong master key)", e);
        }
    }

    public byte[] encryptString(String plaintext) {
        return encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public String decryptString(byte[] blob) {
        return new String(decrypt(blob), StandardCharsets.UTF_8);
    }

    public static String keyIdOf(byte[] blob) {
        int len = blob[1] & 0xFF;
        return new String(blob, 2, len, StandardCharsets.UTF_8);
    }

    private byte[] iv() {
        byte[] iv = new byte[IV_LEN];
        random.nextBytes(iv);
        return iv;
    }

    private static byte[] gcm(int mode, SecretKey key, byte[] iv, byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(data);
    }
}
