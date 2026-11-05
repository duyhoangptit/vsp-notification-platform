package com.pnh.provider.application;

import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ChannelRegistry;
import com.pnh.provider.DeliveryRequest;
import com.pnh.provider.DeliveryResult;
import com.pnh.provider.PermanentProviderException;
import com.pnh.provider.ProviderSettings;
import com.pnh.provider.TransientProviderException;
import com.pnh.shared.config.ObservabilityConfig.Spans;
import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.Masking;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.logging.MdcKeys;
import com.pnh.shared.logging.SensitiveValues;
import com.pnh.shared.security.OtpContentProtector;
import com.pnh.shared.web.PnhException;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.StatusChange;
import com.pnh.tracking.TimelineEntry;
import com.pnh.tracking.TimelineEventType;
import com.pnh.tracking.TrackingApi;
import com.pnh.tracking.TransitionResult;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.retry.Retry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Delivery of one notification (T093, T117; research R4/R7): at-most-once after a crash — SENDING is committed BEFORE
 * the provider call, and a re-delivered event that finds SENDING is skipped (FR-039). Each attempt runs through
 * Bulkhead → CircuitBreaker → TimeLimiter; transient failures are retried with exponential backoff; the OTP expiry
 * is checked before every attempt.
 */
@Service
public class DeliveryOrchestrator implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOrchestrator.class);

    private final TrackingApi tracking;
    private final ChannelRegistry registry;
    private final ChannelConfigProvider configs;
    private final ProviderResilienceFactory resilience;
    private final OtpContentProtector otp;
    private final DeliveryMetrics metrics;
    private final Spans spans;
    private final Clock clock;
    private final ExecutorService callExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pnh-provider-call");
        t.setDaemon(true);
        return t;
    });

    public DeliveryOrchestrator(
            TrackingApi tracking,
            ChannelRegistry registry,
            ChannelConfigProvider configs,
            ProviderResilienceFactory resilience,
            OtpContentProtector otp,
            DeliveryMetrics metrics,
            Spans spans,
            Clock clock) {
        this.tracking = tracking;
        this.registry = registry;
        this.configs = configs;
        this.resilience = resilience;
        this.otp = otp;
        this.metrics = metrics;
        this.spans = spans;
        this.clock = clock;
    }

    public void deliver(UUID notificationId) {
        spans.observe("pnh.provider.send", notificationId.toString(), () -> doDeliver(notificationId));
    }

    private void doDeliver(UUID id) {
        NotificationView n = tracking.findById(id).orElse(null);
        if (n == null) {
            return;
        }
        MDC.put(MdcKeys.NOTIFICATION_ID, id.toString());
        MDC.put(MdcKeys.CLIENT_REQUEST_ID, n.clientRequestId());
        MDC.put(MdcKeys.CLIENT_ID, n.clientId());
        if (n.status() == NotificationStatus.SENDING) {
            tracking.record(TimelineEntry.of(id, TimelineEventType.RESUBMISSION_SKIPPED));
            log.warn("Delivery event re-delivered while SENDING; provider NOT called again (FR-039)");
            return;
        }
        if (n.status() != NotificationStatus.PROCESSED) {
            return;
        }
        if (expired(n)) {
            fail(
                    n.notificationId(),
                    NotificationStatus.PROCESSED,
                    ErrorCode.OTP_EXPIRED,
                    "OTP expired before delivery",
                    null,
                    0,
                    null);
            return;
        }
        ChannelProvider provider;
        ProviderSettings settings;
        try {
            ChannelType channel = ChannelType.of(n.channelType());
            provider = registry.activeProvider(channel);
            settings = configs.active(channel).orElseThrow(() -> PnhException.of(ErrorCode.CHANNEL_NOT_CONFIGURED));
        } catch (PnhException e) {
            fail(id, NotificationStatus.PROCESSED, e.code(), e.getMessage(), null, 0, null);
            return;
        }
        TransitionResult sending =
                tracking.transition(StatusChange.of(id, NotificationStatus.PROCESSED, NotificationStatus.SENDING)
                        .markSendingStarted()
                        .provider(provider.providerKey(), null)
                        .attempt(1)
                        .build());
        if (!sending.applied()) {
            return;
        }
        metrics.sendingStarted(n);
        DeliveryRequest request = request(n, settings);
        execute(n, provider, request);
    }

    private void execute(NotificationView n, ChannelProvider provider, DeliveryRequest request) {
        ProviderResilience r = resilience.forProvider(provider.channelType().value(), provider.providerKey());
        Retry.Context<DeliveryResult> retry = r.retry().context();
        int attempt = 1;
        while (true) {
            if (attempt > 1 && expired(n)) {
                fail(
                        n.notificationId(),
                        NotificationStatus.SENDING,
                        ErrorCode.OTP_EXPIRED,
                        "OTP expired before retry",
                        provider.providerKey(),
                        attempt - 1,
                        attempt);
                return;
            }
            long start = System.nanoTime();
            DeliveryResult result;
            try {
                result = attempt(r, n.category(), provider, request);
            } catch (PermanentProviderException e) {
                metrics.attempt(provider.providerKey(), "permanent", start);
                fail(
                        n.notificationId(),
                        NotificationStatus.SENDING,
                        e.code(),
                        e.getMessage(),
                        provider.providerKey(),
                        attempt - 1,
                        attempt);
                return;
            } catch (CallNotPermittedException e) {
                metrics.attempt(provider.providerKey(), "circuit_open", start);
                fail(
                        n.notificationId(),
                        NotificationStatus.SENDING,
                        ErrorCode.PROVIDER_UNAVAILABLE,
                        "Provider " + provider.providerKey() + " unavailable (circuit open)",
                        provider.providerKey(),
                        attempt - 1,
                        attempt);
                return;
            } catch (TransientProviderException e) {
                metrics.attempt(provider.providerKey(), "transient", start);
                tracking.record(new TimelineEntry(
                        n.notificationId(),
                        TimelineEventType.ATTEMPT_FAILED,
                        attempt,
                        provider.providerKey(),
                        e.code(),
                        sanitize(e.getMessage()),
                        null));
                try {
                    retry.onError(e); // waits the backoff interval, or rethrows when attempts are exhausted
                } catch (Exception exhausted) {
                    fail(
                            n.notificationId(),
                            NotificationStatus.SENDING,
                            e.code(),
                            "Provider failed after " + attempt + " attempts: " + sanitize(e.getMessage()),
                            provider.providerKey(),
                            attempt - 1,
                            attempt);
                    return;
                }
                attempt++;
                tracking.recordRetry(n.notificationId(), attempt - 1);
                continue;
            } catch (RuntimeException e) {
                // an unexpected exception thrown by the adapter itself (bug / unmapped error) — the call outcome is
                // unknown
                metrics.attempt(provider.providerKey(), "error", start);
                log.error("Unexpected provider error", e);
                fail(
                        n.notificationId(),
                        NotificationStatus.SENDING,
                        ErrorCode.PROVIDER_REJECTED,
                        "Unexpected provider error",
                        provider.providerKey(),
                        attempt - 1,
                        attempt);
                return;
            }
            // Provider accepted the message. Recording it is NOT inside the provider error handling: a database failure
            // here propagates (publication stays incomplete; the SENDING marker + sweeper resolve it, never a resend).
            metrics.attempt(provider.providerKey(), "success", start);
            retry.onComplete();
            tracking.completeSend(n.notificationId(), provider.providerKey(), result.providerMessageId(), attempt - 1);
            log.info("Notification sent via {} after {} attempt(s)", provider.providerKey(), attempt);
            return;
        }
    }

    /** One attempt: Bulkhead (per category) → CircuitBreaker → TimeLimiter (timeouts count as transient failures). */
    private DeliveryResult attempt(
            ProviderResilience r, Category category, ChannelProvider provider, DeliveryRequest request) {
        try {
            return r.bulkhead(category)
                    .executeSupplier(() -> r.circuitBreaker().executeSupplier(() -> timed(r, provider, request)));
        } catch (BulkheadFullException e) {
            throw new TransientProviderException(ErrorCode.PROVIDER_UNAVAILABLE, "Provider busy (bulkhead full)", e);
        }
    }

    private DeliveryResult timed(ProviderResilience r, ChannelProvider provider, DeliveryRequest request) {
        java.util.Map<String, String> mdc = MDC.getCopyOfContextMap();
        java.util.Set<String> sensitive = SensitiveValues.snapshot();
        try {
            return r.timeLimiter()
                    .executeFutureSupplier(() -> CompletableFuture.supplyAsync(
                            () -> {
                                if (mdc != null) {
                                    MDC.setContextMap(mdc);
                                }
                                SensitiveValues.restore(sensitive);
                                try {
                                    return provider.send(request);
                                } finally {
                                    MDC.clear();
                                    SensitiveValues.clear();
                                }
                            },
                            callExecutor));
        } catch (TimeoutException e) {
            throw new TransientProviderException(ErrorCode.PROVIDER_TIMEOUT, "Provider call timed out", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new TransientProviderException(ErrorCode.PROVIDER_UNAVAILABLE, String.valueOf(cause), cause);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof RuntimeException re
                    && (re instanceof TransientProviderException || re instanceof PermanentProviderException)) {
                throw re;
            }
            throw e;
        } catch (Exception e) {
            throw new TransientProviderException(ErrorCode.PROVIDER_UNAVAILABLE, e.toString(), e);
        }
    }

    private DeliveryRequest request(NotificationView n, ProviderSettings settings) {
        String subject = n.subject();
        String title = n.title();
        String body = n.compiledContent();
        if (n.category() == Category.OTP && n.payloadEncrypted() != null) {
            OtpContentProtector.Content real = otp.reveal(n.payloadEncrypted());
            subject = real.subject();
            title = real.title();
            body = real.body();
        }
        return new DeliveryRequest(
                n.notificationId(),
                ChannelType.of(n.channelType()),
                n.recipientTarget(),
                subject,
                title,
                body,
                n.pushData(),
                settings);
    }

    private boolean expired(NotificationView n) {
        Instant expiresAt = n.expiresAt();
        return expiresAt != null && !clock.instant().isBefore(expiresAt);
    }

    private void fail(
            UUID id,
            NotificationStatus from,
            ErrorCode code,
            String message,
            String providerKey,
            int retries,
            Integer attempt) {
        StatusChange.Builder b = StatusChange.of(id, from, NotificationStatus.FAILED)
                .error(code, sanitize(message))
                .attempt(attempt);
        if (providerKey != null) {
            b.provider(providerKey, null);
        }
        if (from == NotificationStatus.SENDING) {
            b.retryCount(retries);
        }
        tracking.transition(b.build());
        log.info("Notification failed: {}", code);
    }

    /** Provider messages may echo content; never store OTPs/recipients in error messages. */
    private static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        String m = message;
        for (String s : SensitiveValues.snapshot()) {
            m = Masking.replace(m, s);
        }
        return m.length() > 1000 ? m.substring(0, 1000) : m;
    }

    @Override
    public void destroy() {
        callExecutor.shutdownNow();
    }
}
