package com.vsp.notification.tracking.adapter.out;

import com.vsp.notification.shared.domain.Category;
import com.vsp.notification.shared.domain.ContentMode;
import com.vsp.notification.shared.domain.ErrorCode;
import com.vsp.notification.shared.domain.NotificationStatus;
import com.vsp.notification.tracking.NewNotification;
import com.vsp.notification.tracking.NotificationView;
import com.vsp.notification.tracking.StatusChange;
import com.vsp.notification.tracking.TimelineEventType;
import com.vsp.notification.tracking.domain.NotificationEvent;
import com.vsp.notification.tracking.domain.NotificationTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JDBC access to the partitioned history/events tables; every write is conditional (R22). */
@Repository
public class NotificationJdbcRepository {

    static final String COLUMNS = """
            notification_id, client_id, client_request_id, user_id, recipient_target, channel_type, category, content_mode,
            template_code, template_version, subject, title, compiled_content, payload_encrypted, push_data, expires_at,
            opt_out_overridden, status, retry_count, provider_key, provider_message_id, error_code, error_message,
            result_sequence, sending_started_at, created_at, updated_at""";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RowMapper<NotificationView> mapper;

    public NotificationJdbcRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = this::map;
    }

    public void insert(
            NewNotification n,
            NotificationStatus status,
            ErrorCode errorCode,
            String errorMessage,
            int resultSequence,
            Instant now) {
        jdbc.sql("""
                INSERT INTO pnh_notifications_history (notification_id, client_id, client_request_id, user_id,
                  recipient_target, channel_type, category, content_mode, template_code, template_version, subject, title,
                  compiled_content, payload_encrypted, push_data, expires_at, status, error_code, error_message,
                  result_sequence, created_at, updated_at)
                VALUES (:id, :clientId, :requestId, :userId, :recipient, :channel, :category, :mode, :templateCode,
                  :templateVersion, :subject, :title, :content, :payload, CAST(:pushData AS jsonb), :expiresAt, :status,
                  :errorCode, :errorMessage, :seq, :createdAt, :now)""")
                .param("id", n.notificationId())
                .param("clientId", n.clientId())
                .param("requestId", n.clientRequestId())
                .param("userId", n.userId())
                .param("recipient", n.recipientTarget())
                .param("channel", n.channelType())
                .param("category", (n.category() == null ? Category.STANDARD : n.category()).name())
                .param("mode", (n.contentMode() == null ? ContentMode.TEMPLATE : n.contentMode()).name())
                .param("templateCode", n.templateCode())
                .param("templateVersion", n.templateVersion())
                .param("subject", n.subject())
                .param("title", n.title())
                .param("content", n.compiledContent())
                .param("payload", n.payloadEncrypted())
                .param(
                        "pushData",
                        n.pushData() == null || n.pushData().isEmpty() ? null : json.writeValueAsString(n.pushData()))
                .param("expiresAt", ts(n.expiresAt()))
                .param("status", status.name())
                .param("errorCode", errorCode == null ? null : errorCode.name())
                .param("errorMessage", errorMessage)
                .param("seq", resultSequence)
                .param("createdAt", ts(NotificationTimes.createdAt(n.notificationId())))
                .param("now", ts(now))
                .update();
    }

    /** Conditional transition; returns the updated row or empty when the expected status no longer holds. */
    public Optional<NotificationView> applyTransition(StatusChange c, Instant now) {
        boolean outcome = c.to().isOutcome();
        boolean terminal = c.to().isTerminal();
        String sql = "UPDATE pnh_notifications_history SET status = :to, updated_at = :now"
                + (c.errorCode() != null ? ", error_code = :errorCode, error_message = :errorMessage" : "")
                + (c.to() == NotificationStatus.DELIVERED && c.errorCode() == null
                        ? ", error_code = NULL, error_message = NULL"
                        : "")
                + (c.providerKey() != null ? ", provider_key = :providerKey" : "")
                + (c.providerMessageId() != null ? ", provider_message_id = :providerMessageId" : "")
                + (c.retryCount() != null ? ", retry_count = :retryCount" : "")
                + (c.markSendingStarted() ? ", sending_started_at = :now" : "")
                + (outcome ? ", result_sequence = result_sequence + 1" : "")
                + (outcome ? ", payload_encrypted = NULL" : "") // OTP plaintext no longer needed once handed over
                + " WHERE notification_id = :id AND created_at = :createdAt AND status = :expected"
                + (c.expectedErrorCode() != null ? " AND error_code = :expectedErrorCode" : "")
                + " RETURNING " + COLUMNS;
        JdbcClient.StatementSpec spec = jdbc.sql(sql)
                .param("to", c.to().name())
                .param("now", ts(now))
                .param("id", c.notificationId())
                .param("createdAt", ts(NotificationTimes.createdAt(c.notificationId())))
                .param("expected", c.expected().name());
        if (c.errorCode() != null) {
            spec = spec.param("errorCode", c.errorCode().name()).param("errorMessage", c.errorMessage());
        }
        if (c.providerKey() != null) {
            spec = spec.param("providerKey", c.providerKey());
        }
        if (c.providerMessageId() != null) {
            spec = spec.param("providerMessageId", c.providerMessageId());
        }
        if (c.retryCount() != null) {
            spec = spec.param("retryCount", c.retryCount());
        }
        if (c.expectedErrorCode() != null) {
            spec = spec.param("expectedErrorCode", c.expectedErrorCode().name());
        }
        return spec.query(mapper).optional();
    }

    /** Late send acknowledgement after a callback already finished the notification (fix I4). */
    public int storeProviderIdsIfFinished(UUID id, String providerKey, String providerMessageId, Instant now) {
        return jdbc.sql("""
                UPDATE pnh_notifications_history SET provider_key = :providerKey,
                  provider_message_id = COALESCE(provider_message_id, :providerMessageId), updated_at = :now
                WHERE notification_id = :id AND created_at = :createdAt AND status IN ('DELIVERED', 'FAILED')""")
                .param("providerKey", providerKey)
                .param("providerMessageId", providerMessageId)
                .param("now", ts(now))
                .param("id", id)
                .param("createdAt", ts(NotificationTimes.createdAt(id)))
                .update();
    }

    public void updateRetryCount(UUID id, int retryCount, Instant now) {
        jdbc.sql("""
                UPDATE pnh_notifications_history SET retry_count = :retryCount, sending_started_at = :now, updated_at = :now
                WHERE notification_id = :id AND created_at = :createdAt AND status = 'SENDING'""")
                .param("retryCount", retryCount)
                .param("now", ts(now))
                .param("id", id)
                .param("createdAt", ts(NotificationTimes.createdAt(id)))
                .update();
    }

    public void markOptOutOverridden(UUID id) {
        jdbc.sql(
                        "UPDATE pnh_notifications_history SET opt_out_overridden = TRUE WHERE notification_id = :id AND created_at = :createdAt")
                .param("id", id)
                .param("createdAt", ts(NotificationTimes.createdAt(id)))
                .update();
    }

    public void insertEvent(NotificationEvent e) {
        jdbc.sql("""
                INSERT INTO pnh_notification_events (notification_id, created_at, event_type, from_status, to_status,
                  attempt, provider_key, error_code, error_message, provider_response)
                VALUES (:id, :createdAt, :type, :from, :to, :attempt, :providerKey, :errorCode, :errorMessage,
                  CAST(:response AS jsonb))""")
                .param("id", e.notificationId())
                .param("createdAt", ts(e.createdAt()))
                .param("type", e.eventType().name())
                .param("from", e.fromStatus() == null ? null : e.fromStatus().name())
                .param("to", e.toStatus() == null ? null : e.toStatus().name())
                .param("attempt", e.attempt())
                .param("providerKey", e.providerKey())
                .param("errorCode", e.errorCode() == null ? null : e.errorCode().name())
                .param("errorMessage", e.errorMessage())
                .param("response", e.providerResponseJson())
                .update();
    }

    public Optional<NotificationView> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM pnh_notifications_history WHERE notification_id = :id AND created_at = :createdAt")
                .param("id", id)
                .param("createdAt", ts(NotificationTimes.createdAt(id)))
                .query(mapper)
                .optional();
    }

    public Optional<NotificationView> findByClientRequest(String clientId, String clientRequestId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM pnh_notifications_history WHERE client_id = :c AND client_request_id = :r"
                        + " ORDER BY created_at DESC LIMIT 1")
                .param("c", clientId)
                .param("r", clientRequestId)
                .query(mapper)
                .optional();
    }

    public Optional<NotificationView> findByProviderMessage(String providerKey, String providerMessageId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM pnh_notifications_history WHERE provider_key = :k AND provider_message_id = :m"
                        + " ORDER BY created_at DESC LIMIT 1")
                .param("k", providerKey)
                .param("m", providerMessageId)
                .query(mapper)
                .optional();
    }

    public List<com.vsp.notification.tracking.ChannelOutcomeCounts> outcomeCounts(Instant since, Instant until) {
        return jdbc.sql("""
                SELECT channel_type,
                  count(*) FILTER (WHERE status IN ('SENT','DELIVERED')) AS ok,
                  count(*) FILTER (WHERE status = 'FAILED') AS failed,
                  count(*) FILTER (WHERE status = 'FAILED' AND error_code IN ('DELIVERY_STATE_UNKNOWN','PROCESSING_TIMEOUT')) AS stuck
                FROM pnh_notifications_history
                WHERE updated_at >= :since AND updated_at <= :until AND channel_type IS NOT NULL AND created_at >= :createdFloor
                GROUP BY channel_type""")
                .param("since", ts(since))
                .param("until", ts(until))
                .param("createdFloor", ts(since.minus(java.time.Duration.ofDays(2))))
                .query((rs, n) -> new com.vsp.notification.tracking.ChannelOutcomeCounts(
                        rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)))
                .list();
    }

    public List<NotificationEvent> events(UUID id) {
        return jdbc.sql("""
                SELECT id, notification_id, created_at, event_type, from_status, to_status, attempt, provider_key,
                  error_code, error_message, provider_response::text AS provider_response
                FROM pnh_notification_events WHERE notification_id = :id AND created_at >= :createdAt
                ORDER BY created_at, id""")
                .param("id", id)
                .param("createdAt", ts(NotificationTimes.createdAt(id)))
                .query((rs, n) -> new NotificationEvent(
                        rs.getLong("id"),
                        rs.getObject("notification_id", UUID.class),
                        instant(rs, "created_at"),
                        TimelineEventType.valueOf(rs.getString("event_type")),
                        status(rs.getString("from_status")),
                        status(rs.getString("to_status")),
                        (Integer) rs.getObject("attempt"),
                        rs.getString("provider_key"),
                        code(rs.getString("error_code")),
                        rs.getString("error_message"),
                        rs.getString("provider_response")))
                .list();
    }

    NotificationView map(ResultSet rs, int rowNum) throws SQLException {
        String pushData = rs.getString("push_data");
        Map<String, String> data =
                pushData == null ? null : json.readValue(pushData, new TypeReference<Map<String, String>>() {});
        Object tv = rs.getObject("template_version");
        return new NotificationView(
                rs.getObject("notification_id", UUID.class),
                rs.getString("client_id"),
                rs.getString("client_request_id"),
                rs.getString("user_id"),
                rs.getString("recipient_target"),
                rs.getString("channel_type"),
                Category.valueOf(rs.getString("category")),
                ContentMode.valueOf(rs.getString("content_mode")),
                rs.getString("template_code"),
                tv == null ? null : ((Number) tv).longValue(),
                rs.getString("subject"),
                rs.getString("title"),
                rs.getString("compiled_content"),
                rs.getBytes("payload_encrypted"),
                data,
                instant(rs, "expires_at"),
                rs.getBoolean("opt_out_overridden"),
                NotificationStatus.valueOf(rs.getString("status")),
                rs.getInt("retry_count"),
                rs.getString("provider_key"),
                rs.getString("provider_message_id"),
                code(rs.getString("error_code")),
                rs.getString("error_message"),
                rs.getInt("result_sequence"),
                instant(rs, "sending_started_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    public RowMapper<NotificationView> rowMapper() {
        return mapper;
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    static NotificationStatus status(String s) {
        return s == null ? null : NotificationStatus.valueOf(s);
    }

    static ErrorCode code(String s) {
        if (s == null) {
            return null;
        }
        try {
            return ErrorCode.valueOf(s);
        } catch (IllegalArgumentException e) {
            return ErrorCode.PROVIDER_REJECTED;
        }
    }
}
