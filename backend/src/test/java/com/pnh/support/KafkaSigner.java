package com.pnh.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Ingest signature (contracts/kafka/README.md): v1=hex(HMAC(key, client_id.client_request_id.hex(sha256(value)))). */
public final class KafkaSigner {

    private KafkaSigner() {}

    public static String sign(String clientId, String signingKey, String clientRequestId, byte[] value) {
        try {
            String valueHash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
            return "v1=" + CallbackSigner.hmacHex(signingKey, clientId + "." + clientRequestId + "." + valueHash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(String clientId, String signingKey, String clientRequestId, String value) {
        return sign(clientId, signingKey, clientRequestId, value.getBytes(StandardCharsets.UTF_8));
    }
}
