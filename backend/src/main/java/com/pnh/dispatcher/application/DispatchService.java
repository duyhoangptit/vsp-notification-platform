package com.pnh.dispatcher.application;

import com.pnh.provider.ChannelRegistry;
import com.pnh.provider.NotificationReadyForDelivery;
import com.pnh.provider.OtpNotificationReadyForDelivery;
import com.pnh.shared.config.ObservabilityConfig.Spans;
import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.shared.logging.MdcKeys;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.StatusChange;
import com.pnh.tracking.TrackingApi;
import com.pnh.tracking.TransitionResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Routing step (T092): RECEIVED → PROCESSED (or FAILED) and hand-off to the provider lane. No rendering here — the
 * content was rendered at acceptance (fix I1). Idempotent: anything not RECEIVED is ignored.
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final TrackingApi tracking;
    private final ChannelRegistry registry;
    private final PreferenceRule preferences;
    private final ApplicationEventPublisher events;
    private final Spans spans;

    public DispatchService(
            TrackingApi tracking,
            ChannelRegistry registry,
            PreferenceRule preferences,
            ApplicationEventPublisher events,
            Spans spans) {
        this.tracking = tracking;
        this.registry = registry;
        this.preferences = preferences;
        this.events = events;
        this.spans = spans;
    }

    @Transactional
    public void dispatch(UUID notificationId) {
        spans.observe("pnh.dispatch", notificationId.toString(), () -> doDispatch(notificationId));
    }

    private void doDispatch(UUID notificationId) {
        NotificationView n = tracking.findById(notificationId).orElse(null);
        if (n == null || n.status() != NotificationStatus.RECEIVED) {
            return; // duplicate/late delivery of the event
        }
        MDC.put(MdcKeys.NOTIFICATION_ID, notificationId.toString());
        MDC.put(MdcKeys.CLIENT_REQUEST_ID, n.clientRequestId());
        MDC.put(MdcKeys.CLIENT_ID, n.clientId());
        if (registry.findActiveProvider(ChannelType.of(n.channelType())).isEmpty()) {
            fail(
                    n,
                    ErrorCode.CHANNEL_NOT_CONFIGURED,
                    "Channel " + n.channelType() + " has no active provider configuration");
            return;
        }
        PreferenceRule.Decision decision = preferences.evaluate(n);
        if (decision == PreferenceRule.Decision.BLOCK) {
            fail(n, ErrorCode.USER_OPTED_OUT, "User disabled channel " + n.channelType());
            return;
        }
        TransitionResult r = tracking.transition(
                StatusChange.of(notificationId, NotificationStatus.RECEIVED, NotificationStatus.PROCESSED)
                        .build());
        if (!r.applied()) {
            return;
        }
        if (decision == PreferenceRule.Decision.OVERRIDE) {
            tracking.markOptOutOverridden(notificationId);
        }
        log.info("Notification routed to channel {}", n.channelType());
        if (n.category() == Category.OTP) {
            events.publishEvent(new OtpNotificationReadyForDelivery(notificationId, n.channelType()));
        } else {
            events.publishEvent(new NotificationReadyForDelivery(
                    notificationId, n.channelType(), n.category().name()));
        }
    }

    private void fail(NotificationView n, ErrorCode code, String message) {
        tracking.transition(StatusChange.of(n.notificationId(), NotificationStatus.RECEIVED, NotificationStatus.FAILED)
                .error(code, message)
                .build());
        log.info("Notification failed at dispatch: {}", code);
    }
}
