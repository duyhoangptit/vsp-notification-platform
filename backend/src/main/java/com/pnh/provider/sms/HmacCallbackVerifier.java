package com.pnh.provider.sms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** HMAC-SHA256 callback verification with a ±5 min timestamp window (constant-time compare). */
@Component
public class HmacCallbackVerifier {

    static final Duration MAX_SKEW = Duration.ofMinutes(5);
    private final Clock clock;

    public HmacCallbackVerifier(Clock clock) {
        this.clock = clock;
    }

    /** Generic contract: {@code sha256=<hex HMAC(secret, timestamp + "." + raw_body)>}. */
    public boolean verifyGeneric(String secret, String timestamp, String signature, byte[] rawBody) {
        if (signature == null || !signature.startsWith("sha256=")) {
            return false;
        }
        return fresh(timestamp) && equal(signature.substring(7), hmac(secret, concat(timestamp + ".", rawBody)));
    }

    /** SMS Gateway for Android: {@code X-Signature = hex HMAC(key, raw_body + X-Timestamp)} (verified against docs). */
    public boolean verifyAndroidGateway(String key, String timestamp, String signature, byte[] rawBody) {
        if (signature == null) {
            return false;
        }
        byte[] ts = timestamp == null ? new byte[0] : timestamp.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[rawBody.length + ts.length];
        System.arraycopy(rawBody, 0, data, 0, rawBody.length);
        System.arraycopy(ts, 0, data, rawBody.length, ts.length);
        return fresh(timestamp) && equal(signature, hmac(key, data));
    }

    private boolean fresh(String timestamp) {
        if (timestamp == null) {
            return false;
        }
        try {
            Instant ts = Instant.ofEpochSecond(Long.parseLong(timestamp.trim()));
            return Duration.between(ts, clock.instant()).abs().compareTo(MAX_SKEW) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static byte[] concat(String prefix, byte[] body) {
        byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[p.length + body.length];
        System.arraycopy(p, 0, out, 0, p.length);
        System.arraycopy(body, 0, out, p.length, body.length);
        return out;
    }

    static String hmac(String secret, byte[] data) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean equal(String a, String b) {
        if (b.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                a.toLowerCase().getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
