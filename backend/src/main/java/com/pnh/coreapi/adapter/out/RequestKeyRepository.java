package com.pnh.coreapi.adapter.out;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Authoritative idempotency keys (research R3): PK (client_id, client_request_id). */
@Repository
public class RequestKeyRepository {

    private final JdbcClient jdbc;

    public RequestKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> find(String clientId, String clientRequestId) {
        return jdbc.sql("SELECT notification_id FROM pnh_request_keys WHERE client_id = :c AND client_request_id = :r")
                .param("c", clientId)
                .param("r", clientRequestId)
                .query(UUID.class)
                .optional();
    }

    /** @return true if inserted; false when the key already exists (a concurrent insert waits for the other tx). */
    public boolean insertIfAbsent(String clientId, String clientRequestId, UUID notificationId, Instant createdAt) {
        return jdbc.sql("""
                        INSERT INTO pnh_request_keys (client_id, client_request_id, notification_id, created_at)
                        VALUES (:c, :r, :n, :t) ON CONFLICT DO NOTHING RETURNING notification_id""")
                .param("c", clientId)
                .param("r", clientRequestId)
                .param("n", notificationId)
                .param("t", Timestamp.from(createdAt))
                .query(UUID.class)
                .optional()
                .isPresent();
    }
}
