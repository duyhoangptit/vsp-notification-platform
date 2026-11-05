package com.pnh.tracking.application;

import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.Masking;
import com.pnh.shared.web.PnhException;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.adapter.out.NotificationJdbcRepository;
import com.pnh.tracking.domain.NotificationEvent;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivery Audit (T142, US8): partition-pruned search (created_at range bound as a parameter, ≤ 31 days unless the
 * client_request_id is given), newest first, masked recipients in lists; detail with full recipient and timeline.
 */
@Service
public class NotificationSearchService {

    static final Duration MAX_RANGE = Duration.ofDays(31);
    static final Duration DEFAULT_RANGE = Duration.ofDays(7);

    public record Criteria(
            String clientRequestId,
            String userId,
            String recipientTarget,
            String channelType,
            String status,
            String clientId,
            Instant from,
            Instant to,
            int page,
            int size) {}

    public record Summary(
            UUID notificationId,
            String clientId,
            String clientRequestId,
            String userId,
            String recipientTargetMasked,
            String channelType,
            String category,
            String templateCode,
            String status,
            String errorCode,
            Instant createdAt,
            Instant updatedAt) {}

    public record TimelineItem(
            Instant at,
            String eventType,
            String fromStatus,
            String toStatus,
            Integer attempt,
            String providerKey,
            String errorCode,
            String errorMessage,
            Map<String, Object> providerResponse) {}

    public record Detail(
            UUID notificationId,
            String clientId,
            String clientRequestId,
            String userId,
            String recipientTargetMasked,
            String channelType,
            String category,
            String templateCode,
            String status,
            String errorCode,
            Instant createdAt,
            Instant updatedAt,
            String recipientTarget,
            String contentMode,
            Long templateVersion,
            String subject,
            String title,
            String content,
            Instant expiresAt,
            boolean optOutOverridden,
            int retryCount,
            String errorMessage,
            List<TimelineItem> timeline) {}

    public record Page(List<Summary> items, long total) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationJdbcRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    public NotificationSearchService(
            NamedParameterJdbcTemplate jdbc, NotificationJdbcRepository repository, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page search(Criteria c) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        boolean byRequest = notBlank(c.clientRequestId());
        Instant to = c.to() != null ? c.to() : clock.instant().plusSeconds(60);
        Instant from = c.from() != null ? c.from() : to.minus(DEFAULT_RANGE);
        if (!to.isAfter(from)) {
            throw PnhException.field(ErrorCode.VALIDATION_ERROR, "from", "from must be before to");
        }
        if (!byRequest || c.from() != null || c.to() != null) {
            if (!byRequest && Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
                throw PnhException.field(ErrorCode.VALIDATION_ERROR, "from", "Date range must not exceed 31 days");
            }
            if (!byRequest || c.from() != null) {
                where.append(" AND created_at >= :from");
                p.addValue("from", Timestamp.from(from));
            }
            if (!byRequest || c.to() != null) {
                where.append(" AND created_at < :to");
                p.addValue("to", Timestamp.from(to));
            }
        }
        if (byRequest) {
            where.append(" AND client_request_id = :crid");
            p.addValue("crid", c.clientRequestId().trim());
        }
        if (notBlank(c.userId())) {
            where.append(" AND user_id = :uid");
            p.addValue("uid", c.userId().trim());
        }
        if (notBlank(c.recipientTarget())) {
            where.append(" AND recipient_target IN (:rt, :rtLower)");
            p.addValue("rt", c.recipientTarget().trim());
            p.addValue("rtLower", c.recipientTarget().trim().toLowerCase(Locale.ROOT));
        }
        if (notBlank(c.channelType())) {
            where.append(" AND channel_type = :ch");
            p.addValue("ch", c.channelType().trim().toUpperCase(Locale.ROOT));
        }
        if (notBlank(c.status())) {
            where.append(" AND status = :st");
            p.addValue("st", c.status().trim().toUpperCase(Locale.ROOT));
        }
        if (notBlank(c.clientId())) {
            where.append(" AND client_id = :cid");
            p.addValue("cid", c.clientId().trim());
        }
        int size = Math.min(Math.max(1, c.size()), 100);
        int page = Math.max(0, c.page());
        p.addValue("limit", size).addValue("offset", (long) page * size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM pnh_notifications_history" + where, p, Long.class);
        List<NotificationView> rows = jdbc.query(
                "SELECT " + columns() + " FROM pnh_notifications_history" + where
                        + " ORDER BY created_at DESC, notification_id DESC LIMIT :limit OFFSET :offset",
                p,
                repository.rowMapper());
        return new Page(rows.stream().map(NotificationSearchService::summary).toList(), total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        NotificationView n = repository
                .findById(id)
                .orElseThrow(() -> new PnhException(ErrorCode.NOT_FOUND, "Notification " + id + " not found"));
        List<TimelineItem> timeline = new ArrayList<>();
        for (NotificationEvent e : repository.events(id)) {
            Map<String, Object> response = null;
            if (e.providerResponseJson() != null) {
                try {
                    response = json.readValue(
                            e.providerResponseJson(), new TypeReference<LinkedHashMap<String, Object>>() {});
                } catch (RuntimeException ignored) {
                    response = Map.of("raw", e.providerResponseJson());
                }
            }
            timeline.add(new TimelineItem(
                    e.createdAt(),
                    e.eventType().name(),
                    name(e.fromStatus()),
                    name(e.toStatus()),
                    e.attempt(),
                    e.providerKey(),
                    e.errorCode() == null ? null : e.errorCode().name(),
                    e.errorMessage(),
                    response));
        }
        Summary s = summary(n);
        return new Detail(
                s.notificationId(),
                s.clientId(),
                s.clientRequestId(),
                s.userId(),
                s.recipientTargetMasked(),
                s.channelType(),
                s.category(),
                s.templateCode(),
                s.status(),
                s.errorCode(),
                s.createdAt(),
                s.updatedAt(),
                n.recipientTarget(),
                n.contentMode().name(),
                n.templateVersion(),
                n.subject(),
                n.title(),
                n.compiledContent(),
                n.expiresAt(),
                n.optOutOverridden(),
                n.retryCount(),
                n.errorMessage(),
                timeline);
    }

    private static Summary summary(NotificationView n) {
        ChannelType ch = ChannelType.parseOrNull(n.channelType());
        return new Summary(
                n.notificationId(),
                n.clientId(),
                n.clientRequestId(),
                n.userId(),
                Masking.recipient(ch, n.recipientTarget()),
                n.channelType(),
                n.category().name(),
                n.templateCode(),
                n.status().name(),
                n.errorCode() == null ? null : n.errorCode().name(),
                n.createdAt(),
                n.updatedAt());
    }

    private static String columns() {
        return """
                notification_id, client_id, client_request_id, user_id, recipient_target, channel_type, category, content_mode,
                template_code, template_version, subject, title, compiled_content, NULL::bytea AS payload_encrypted, push_data,
                expires_at, opt_out_overridden, status, retry_count, provider_key, provider_message_id, error_code, error_message,
                result_sequence, sending_started_at, created_at, updated_at""";
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
