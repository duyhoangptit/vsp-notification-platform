package com.pnh.template.domain;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code ${identifier}} placeholders only (research R8); any other {@code ${…}} is INVALID_PLACEHOLDER. */
public final class PlaceholderParser {

    public static final Pattern VALID = Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_]{0,63})\\}");
    private static final Pattern ANY = Pattern.compile("\\$\\{[^}]*\\}?");

    private PlaceholderParser() {}

    /** Variables in first-appearance order; throws INVALID_PLACEHOLDER for malformed placeholders. */
    public static List<String> variables(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher any = ANY.matcher(text);
        while (any.find()) {
            Matcher valid = VALID.matcher(any.group());
            if (!valid.matches()) {
                throw PnhException.field(
                        ErrorCode.INVALID_PLACEHOLDER,
                        "placeholder",
                        "Invalid placeholder '" + abbreviate(any.group())
                                + "': use ${name} with letters, digits and _ only");
            }
            names.add(valid.group(1));
        }
        return new ArrayList<>(names);
    }

    public static List<String> variablesOf(String... texts) {
        Set<String> all = new LinkedHashSet<>();
        for (String t : texts) {
            all.addAll(variables(t));
        }
        return new ArrayList<>(all);
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
