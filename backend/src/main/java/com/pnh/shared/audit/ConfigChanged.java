package com.pnh.shared.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Published by every configuration change; recorded append-only by the admin module (R17).
 * Snapshots map field name → value; fields named in {@code secretFields} are never stored, only flagged.
 */
public record ConfigChanged(
        String entityType,
        String entityId,
        String action,
        long version,
        Map<String, Object> before,
        Map<String, Object> after,
        Set<String> secretFields,
        Set<String> changedSecrets,
        String actor,
        Instant occurredAt) {

    public ConfigChanged {
        before = before == null ? Map.of() : before;
        after = after == null ? Map.of() : after;
        secretFields = secretFields == null ? Set.of() : Set.copyOf(secretFields);
        changedSecrets = changedSecrets == null ? Set.of() : Set.copyOf(changedSecrets);
    }
}
