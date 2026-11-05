package com.pnh.shared.domain;

/** Masking rules for OTPs, recipients and tokens (FR-027, FR-030, research R15). */
public final class Masking {

    public static final String OTP_MASK = "******";
    private static final String STARS = "*****";

    private Masking() {}

    public static String otp(String value) {
        return OTP_MASK;
    }

    public static String email(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return email.charAt(0) + "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    public static String phone(String phone) {
        if (phone == null) {
            return null;
        }
        if (phone.length() < 7) {
            return STARS;
        }
        return phone.substring(0, 3) + STARS + phone.substring(phone.length() - 3);
    }

    public static String token(String token) {
        if (token == null) {
            return null;
        }
        if (token.length() <= 12) {
            return STARS;
        }
        return token.substring(0, 8) + "…" + token.substring(token.length() - 4);
    }

    public static String recipient(ChannelType channel, String target) {
        if (target == null) {
            return null;
        }
        if (target.contains("@")) {
            return email(target);
        }
        if (ChannelType.PUSH.equals(channel)) {
            return token(target);
        }
        if (target.startsWith("+") || target.chars().allMatch(Character::isDigit)) {
            return phone(target);
        }
        return token(target);
    }

    /** Replaces every occurrence of {@code secret} in {@code text} with the OTP mask. */
    public static String replace(String text, String secret) {
        if (text == null || secret == null || secret.isEmpty()) {
            return text;
        }
        return text.replace(secret, OTP_MASK);
    }
}
