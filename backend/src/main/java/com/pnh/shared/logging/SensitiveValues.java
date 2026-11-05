package com.pnh.shared.logging;

import java.util.LinkedHashSet;
import java.util.Set;

/** Per-thread registry of values (e.g. OTPs) that must never appear in logs (constitution IV). */
public final class SensitiveValues {

    private static final ThreadLocal<Set<String>> VALUES = ThreadLocal.withInitial(LinkedHashSet::new);

    private SensitiveValues() {}

    public static void register(String value) {
        if (value != null && !value.isBlank()) {
            VALUES.get().add(value);
        }
    }

    public static Set<String> snapshot() {
        return Set.copyOf(VALUES.get());
    }

    public static void restore(Set<String> values) {
        Set<String> set = VALUES.get();
        set.clear();
        set.addAll(values);
    }

    public static void clear() {
        VALUES.remove();
    }
}
