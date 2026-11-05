package com.pnh.shared.security;

import com.pnh.shared.crypto.EnvelopeEncryptor;
import com.pnh.shared.domain.Masking;
import com.pnh.shared.logging.SensitiveValues;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * OTP protection (research R15, fix C5): the stored content is masked ({@code ******}); the real content is
 * AES-GCM encrypted into {@code payload_encrypted} and only decrypted for the provider call.
 */
@Component
public class OtpContentProtector {

    public record Content(String subject, String title, String body) {
        @Override
        public String toString() {
            return "Content[***]";
        }
    }

    public record Protected(String maskedSubject, String maskedTitle, String maskedBody, byte[] payload) {}

    private final EnvelopeEncryptor encryptor;
    private final JsonMapper json;

    public OtpContentProtector(EnvelopeEncryptor encryptor, JsonMapper json) {
        this.encryptor = encryptor;
        this.json = json;
    }

    public Protected protect(String otpValue, String subject, String title, String body) {
        SensitiveValues.register(otpValue);
        byte[] payload = encryptor.encryptString(json.writeValueAsString(new Content(subject, title, body)));
        return new Protected(
                Masking.replace(subject, otpValue),
                Masking.replace(title, otpValue),
                Masking.replace(body, otpValue),
                payload);
    }

    public Content reveal(byte[] payload) {
        return json.readValue(encryptor.decryptString(payload), Content.class);
    }
}
