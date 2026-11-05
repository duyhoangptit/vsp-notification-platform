package com.pnh.coreapi.application;

import com.pnh.coreapi.ClientPrincipal;
import com.pnh.coreapi.NotificationAccepted;
import com.pnh.coreapi.OtpNotificationAccepted;
import com.pnh.coreapi.adapter.in.web.dto.SendRequest;
import com.pnh.shared.config.ObservabilityConfig.Spans;
import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.NotificationIdGenerator;
import com.pnh.shared.logging.MdcKeys;
import com.pnh.shared.logging.SensitiveValues;
import com.pnh.shared.security.OtpContentProtector;
import com.pnh.tracking.NewNotification;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.TimelineEntry;
import com.pnh.tracking.TimelineEventType;
import com.pnh.tracking.TrackingApi;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Intake use case shared by REST and Kafka (T090): rate limit → idempotency lookup → validate + render now → in ONE
 * transaction: claim key, OTP protection (masked content + encrypted payload), RECEIVED row, accepted event.
 */
@Service
public class SubmitNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubmitNotificationUseCase.class);

    private final ClientRateLimiter rateLimiter;
    private final IdempotencyService idempotency;
    private final IntakeValidator validator;
    private final OtpContentProtector otp;
    private final TrackingApi tracking;
    private final NotificationIdGenerator ids;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Spans spans;

    public SubmitNotificationUseCase(
            ClientRateLimiter rateLimiter,
            IdempotencyService idempotency,
            IntakeValidator validator,
            OtpContentProtector otp,
            TrackingApi tracking,
            NotificationIdGenerator ids,
            ApplicationEventPublisher events,
            TransactionTemplate tx,
            Spans spans) {
        this.rateLimiter = rateLimiter;
        this.idempotency = idempotency;
        this.validator = validator;
        this.otp = otp;
        this.tracking = tracking;
        this.ids = ids;
        this.events = events;
        this.tx = tx;
        this.spans = spans;
    }

    public SubmitResult submit(SendRequest request, ClientPrincipal client) {
        rateLimiter.acquire(client);
        return accept(request, client);
    }

    /** Without rate limiting (Kafka ingest is bounded by consumer concurrency). */
    public SubmitResult accept(SendRequest request, ClientPrincipal client) {
        MDC.put(MdcKeys.CLIENT_ID, client.clientId());
        MDC.put(MdcKeys.CLIENT_REQUEST_ID, request.clientRequestId());
        if (request.otpValue() != null) {
            SensitiveValues.register(request.otpValue());
        }
        Optional<SubmitResult> replay = replay(client.clientId(), request.clientRequestId());
        if (replay.isPresent()) {
            return replay.get();
        }
        UUID id = ids.next();
        MDC.put(MdcKeys.NOTIFICATION_ID, id.toString());
        return spans.observe("pnh.intake", id.toString(), () -> {
            ValidatedNotification v = validator.validate(request, client);
            SubmitResult result = tx.execute(status -> persist(id, v));
            if (!result.replay()) {
                log.info(
                        "Notification accepted: channel={} category={} mode={}",
                        v.channelType(),
                        v.category(),
                        v.contentMode());
            }
            return result;
        });
    }

    private SubmitResult persist(UUID id, ValidatedNotification v) {
        if (!idempotency.claim(v.clientId(), v.clientRequestId(), id)) {
            UUID existing =
                    idempotency.authoritative(v.clientId(), v.clientRequestId()).orElseThrow();
            return new SubmitResult(tracking.findById(existing).orElseThrow(), true);
        }
        String subject = v.subject();
        String title = v.title();
        String body = v.body();
        byte[] payload = null;
        if (v.category() == Category.OTP
                && v.otpValue() != null
                && !v.otpValue().isBlank()) {
            OtpContentProtector.Protected p = otp.protect(v.otpValue(), subject, title, body);
            subject = p.maskedSubject();
            title = p.maskedTitle();
            body = p.maskedBody();
            payload = p.payload();
        }
        NotificationView created = tracking.createReceived(new NewNotification(
                id,
                v.clientId(),
                v.clientRequestId(),
                v.userId(),
                v.recipient(),
                v.channelType(),
                v.category(),
                v.contentMode(),
                v.templateCode(),
                v.templateVersion(),
                subject,
                title,
                body,
                payload,
                v.pushData(),
                v.expiresAt()));
        if (v.sanitized()) {
            tracking.record(TimelineEntry.of(id, TimelineEventType.CONTENT_SANITIZED));
        }
        if (v.category() == Category.OTP) {
            events.publishEvent(new OtpNotificationAccepted(id, v.userId(), v.channelType()));
        } else {
            events.publishEvent(new NotificationAccepted(
                    id, v.userId(), v.channelType(), v.category().name()));
        }
        return new SubmitResult(created, false);
    }

    private Optional<SubmitResult> replay(String clientId, String clientRequestId) {
        return idempotency
                .existing(clientId, clientRequestId)
                .flatMap(tracking::findById)
                .map(view -> new SubmitResult(view, true));
    }
}
