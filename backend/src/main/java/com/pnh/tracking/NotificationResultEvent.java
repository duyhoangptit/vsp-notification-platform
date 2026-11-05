package com.pnh.tracking;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * Result message on {@code pnh.notification.result} (contracts/kafka/result.schema.json), externalized through the
 * event publication registry (at-least-once, R26). Key = user_id, or {@code client_id:client_request_id} when the
 * user id is unknown (rejected ingest). {@code event_id} is stable across re-publication.
 */
@Externalized("pnh.notification.result::#{routingKey()}")
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record NotificationResultEvent(
        UUID eventId,
        UUID notificationId,
        String clientId,
        String clientRequestId,
        String userId,
        String channelType,
        String status,
        String errorCode,
        String errorMessage,
        int sequence,
        Instant timestamp) {

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String routingKey() {
        return userId != null ? userId : clientId + ":" + clientRequestId;
    }
}
