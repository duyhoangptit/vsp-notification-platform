package com.pnh.shared.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Computes {@code {field: {old, new}}}; secret fields appear as {@code "***changed***"} (R17). */
public final class ChangeDiff {

    public static final String SECRET_CHANGED = "***changed***";

    private ChangeDiff() {}

    public static Map<String, Object> diff(ConfigChanged event) {
        Map<String, Object> out = new LinkedHashMap<>();
        TreeSet<String> keys = new TreeSet<>(event.before().keySet());
        keys.addAll(event.after().keySet());
        for (String key : keys) {
            if (event.secretFields().contains(key)) {
                continue;
            }
            Object oldV = event.before().get(key);
            Object newV = event.after().get(key);
            if (!Objects.equals(oldV, newV)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("old", oldV);
                change.put("new", newV);
                out.put(key, change);
            }
        }
        for (String secret : new TreeSet<>(event.changedSecrets())) {
            out.put(secret, SECRET_CHANGED);
        }
        return out;
    }
}
