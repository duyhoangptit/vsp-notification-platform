package com.pnh.coreapi.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.pnh.provider.RecipientType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalizes recipient addresses per recipient type (research R16). */
public final class RecipientValidator {

    private static final PhoneNumberUtil PHONES = PhoneNumberUtil.getInstance();
    private static final String DEFAULT_REGION = "VN";
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    private RecipientValidator() {}

    public static String normalize(RecipientType type, String target) {
        if (target == null || target.isBlank()) {
            throw invalid("Recipient is required");
        }
        String t = target.trim();
        return switch (type) {
            case PHONE, ZALO_USER -> phone(t);
            case EMAIL -> email(t);
            case DEVICE_TOKEN -> {
                if (t.length() > 4096) {
                    throw invalid("Device token must be at most 4096 characters");
                }
                yield t;
            }
        };
    }

    private static String phone(String raw) {
        try {
            Phonenumber.PhoneNumber n = PHONES.parse(raw, DEFAULT_REGION);
            if (!PHONES.isValidNumber(n)) {
                throw invalid("Phone number is not valid");
            }
            return PHONES.format(n, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            throw invalid("Phone number is not valid");
        }
    }

    private static String email(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.length() > 254 || !EMAIL.matcher(lower).matches()) {
            throw invalid("Email address is not valid");
        }
        return lower;
    }

    private static PnhException invalid(String message) {
        return PnhException.field(ErrorCode.INVALID_RECIPIENT, "recipient_target", message);
    }
}
