package com.pnh.support;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class CallbackSigner {

    private CallbackSigner() {}

    public static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Generic contract: {@code X-PNH-Signature: sha256=<hex HMAC(secret, timestamp + "." + body)>}. */
    public static String generic(String secret, long timestamp, String body) {
        return "sha256=" + hmacHex(secret, timestamp + "." + body);
    }

    /** SMS Gateway for Android: {@code X-Signature: hex HMAC(key, body + timestamp)}. */
    public static String androidGateway(String key, long timestamp, String body) {
        return hmacHex(key, body + timestamp);
    }
}
