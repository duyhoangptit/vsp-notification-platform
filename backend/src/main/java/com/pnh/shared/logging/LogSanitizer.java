package com.pnh.shared.logging;

import com.pnh.shared.domain.Masking;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks e-mail addresses, phone numbers, registered sensitive values and secret JSON fields in log text. */
public final class LogSanitizer {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w-])\\+\\d{8,15}(?!\\w)");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "\"(password|secret|api_key|api_secret|client_secret|service_account_json|callback_hmac_secret|signing_key)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"",
            Pattern.CASE_INSENSITIVE);

    private LogSanitizer() {}

    public static String sanitize(String message, Set<String> sensitive) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String out = message;
        for (String value : sensitive) {
            out = Masking.replace(out, value);
        }
        out = SECRET_FIELD.matcher(out).replaceAll(m -> Matcher.quoteReplacement("\"" + m.group(1) + "\":\"***\""));
        out = EMAIL.matcher(out).replaceAll(m -> Matcher.quoteReplacement(Masking.email(m.group())));
        out = PHONE.matcher(out).replaceAll(m -> Matcher.quoteReplacement(Masking.phone(m.group())));
        return out;
    }
}
