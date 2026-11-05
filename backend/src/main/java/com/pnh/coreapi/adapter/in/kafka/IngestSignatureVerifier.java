package com.pnh.coreapi.adapter.in.kafka;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** {@code pnh-signature: v1=hex(HMAC-SHA256(key, client_id.client_request_id.hex(sha256(value))))} — research R5 / fix U2. */
@Component
public class IngestSignatureVerifier {

    public boolean verify(String signingKey, String clientId, String clientRequestId, byte[] value, String header) {
        if (signingKey == null || header == null || !header.startsWith("v1=") || clientRequestId == null) {
            return false;
        }
        String expected = sign(signingKey, clientId, clientRequestId, value);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                header.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    static String sign(String key, String clientId, String clientRequestId, byte[] value) {
        try {
            String valueHash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig =
                    mac.doFinal((clientId + "." + clientRequestId + "." + valueHash).getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(sig);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
