package com.pnh.shared.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pnh.shared.crypto.CryptoException;
import com.pnh.shared.crypto.EnvelopeEncryptor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class EnvelopeEncryptorTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private final EnvelopeEncryptor encryptor = new EnvelopeEncryptor(KEY, "k1");

    @Test
    void roundTrip() {
        byte[] blob = encryptor.encrypt("secret-payload".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(blob, StandardCharsets.ISO_8859_1)).doesNotContain("secret-payload");
        assertThat(new String(encryptor.decrypt(blob), StandardCharsets.UTF_8)).isEqualTo("secret-payload");
        assertThat(encryptor.encryptString("abc")).isNotEqualTo(encryptor.encryptString("abc")); // fresh data key + IV
        assertThat(encryptor.decryptString(encryptor.encryptString("xin chào"))).isEqualTo("xin chào");
    }

    @Test
    void keyIdRecorded() {
        byte[] blob = encryptor.encrypt(new byte[] {1, 2, 3});
        assertThat(EnvelopeEncryptor.keyIdOf(blob)).isEqualTo("k1");
        assertThat(encryptor.keyId()).isEqualTo("k1");
    }

    @Test
    void tamperDetected() {
        byte[] blob = encryptor.encrypt("secret".getBytes(StandardCharsets.UTF_8));
        blob[blob.length - 1] ^= 0x01;
        assertThatThrownBy(() -> encryptor.decrypt(blob)).isInstanceOf(CryptoException.class);
    }

    @Test
    void wrongMasterKeyFails() {
        byte[] blob = encryptor.encrypt("secret".getBytes(StandardCharsets.UTF_8));
        String otherKey =
                Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new EnvelopeEncryptor(otherKey, "k1").decrypt(blob))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    void invalidMasterKeyRejected() {
        assertThatThrownBy(() -> new EnvelopeEncryptor("", "k1")).isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> new EnvelopeEncryptor(Base64.getEncoder().encodeToString(new byte[16]), "k1"))
                .isInstanceOf(CryptoException.class);
    }
}
