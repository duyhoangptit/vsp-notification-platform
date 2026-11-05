package com.pnh.provider;

import java.util.Map;

/** Decrypted active configuration handed to an adapter for one call (secrets never leave the provider module). */
public record ProviderSettings(long configId, long version, Map<String, Object> settings, Map<String, String> secrets) {

    public ProviderSettings {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    }

    public String setting(String key) {
        Object v = settings.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public String setting(String key, String fallback) {
        String v = setting(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    public String secret(String key) {
        return secrets.get(key);
    }

    @Override
    public String toString() {
        return "ProviderSettings[configId=" + configId + ", version=" + version + ", settings=" + settings.keySet()
                + ", secrets=" + secrets.keySet() + "]";
    }
}
