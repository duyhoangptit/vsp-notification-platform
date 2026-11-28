package com.vsp.notification.provider.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.vsp.notification.provider.NotificationReadyForDelivery;
import com.vsp.notification.shared.domain.Category;
import com.vsp.notification.shared.domain.ContentMode;
import com.vsp.notification.shared.domain.NotificationIdGenerator;
import com.vsp.notification.shared.domain.NotificationStatus;
import com.vsp.notification.support.ProviderStubs;
import com.vsp.notification.support.StubbedProvidersTestBase;
import com.vsp.notification.tracking.NewNotification;
import com.vsp.notification.tracking.StatusChange;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** T077 — FR-039: a resubmitted delivery event that finds SENDING never calls the provider again. */
class NoResendAfterCrashIT extends StubbedProvidersTestBase {

    @Autowired
    NotificationIdGenerator ids;

    @Autowired
    TransactionTemplate tx;

    @Test
    void resubmittedEventForSendingNotificationIsSkipped() {
        UUID id = ids.next();
        tracking.createReceived(new NewNotification(
                id,
                "crash-client",
                "crash-" + id,
                "u",
                "+84901234567",
                "SMS",
                Category.STANDARD,
                ContentMode.DIRECT,
                null,
                null,
                null,
                null,
                "hello",
                null,
                null,
                null));
        tracking.transition(StatusChange.of(id, NotificationStatus.RECEIVED, NotificationStatus.PROCESSED)
                .build());
        tracking.transition(StatusChange.of(id, NotificationStatus.PROCESSED, NotificationStatus.SENDING)
                .markSendingStarted()
                .build());

        // what the resubmitter would re-deliver after a crash between SENDING commit and provider response
        tx.executeWithoutResult(s -> context.publishEvent(new NotificationReadyForDelivery(id, "SMS", "STANDARD")));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.queryForObject(
                                "SELECT count(*) FROM pnh_notification_events WHERE notification_id=? AND event_type='RESUBMISSION_SKIPPED'",
                                Integer.class,
                                id)
                        == 1);
        assertThat(ProviderStubs.WIREMOCK.findAll(postRequestedFor(urlPathEqualTo("/sms/send"))))
                .isEmpty();
        assertThat(tracking.findById(id).orElseThrow().status()).isEqualTo(NotificationStatus.SENDING);
    }
}
