package com.pnh.shared.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Open channel identifier (SMS, EMAIL, PUSH, later ZALO…); validity is decided by the provider registry. */
public record ChannelType(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[A-Z][A-Z0-9_]{1,19}$");

    public static final ChannelType SMS = new ChannelType("SMS");
    public static final ChannelType EMAIL = new ChannelType("EMAIL");
    public static final ChannelType PUSH = new ChannelType("PUSH");

    public ChannelType {
        Objects.requireNonNull(value, "channel type");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid channel type: " + value);
        }
    }

    public static ChannelType of(String raw) {
        return new ChannelType(
                Objects.requireNonNull(raw, "channel type").trim().toUpperCase(Locale.ROOT));
    }

    /** Returns null instead of throwing for malformed values. */
    public static ChannelType parseOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return of(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
