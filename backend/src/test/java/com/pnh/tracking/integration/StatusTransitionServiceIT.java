package com.pnh.tracking.integration;

import static com.pnh.shared.domain.NotificationStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationIdGenerator;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.NotificationFixtures;
import com.pnh.tracking.NotificationOutcome;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.StatusChange;
import com.pnh.tracking.TrackingApi;
import com.pnh.tracking.TransitionResult;
import com.pnh.tracking.domain.IllegalStatusTransitionException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/** T021 */
@RecordApplicationEvents
class StatusTransitionServiceIT extends IntegrationTestBase {

    @Autowired
    TrackingApi tracking;

    @Autowired
    NotificationIdGenerator ids;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEvents events;

    private UUID created() {
        return tracking.createReceived(NotificationFixtures.email(ids)).notificationId();
    }

    private TransitionResult move(
            UUID id, com.pnh.shared.domain.NotificationStatus from, com.pnh.shared.domain.NotificationStatus to) {
        return tracking.transition(StatusChange.of(id, from, to).build());
    }

    private int eventCount(UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pnh_notification_events WHERE notification_id = ?", Integer.class, id);
    }

    @Test
    void concurrentTransitionsExactlyOneWins() throws Exception {
        UUID id = created();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<TransitionResult>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit((Callable<TransitionResult>) () -> move(id, RECEIVED, PROCESSED)));
        }
        int applied = 0;
        for (Future<TransitionResult> f : results) {
            applied += f.get().applied() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(applied).isEqualTo(1);
        assertThat(eventCount(id)).isEqualTo(2); // RECEIVED + one PROCESSED
    }

    @Test
    void timelineEventIsAtomicWithTransition() {
        UUID id = created();
        try {
            tx.executeWithoutResult(s -> {
                move(id, RECEIVED, PROCESSED);
                throw new IllegalStateException("rollback");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        assertThat(tracking.findById(id).orElseThrow().status()).isEqualTo(RECEIVED);
        assertThat(eventCount(id)).isEqualTo(1);
    }

    @Test
    void resultSequenceAndOutcomesAndPayloadNulling() {
        UUID id = created();
        move(id, RECEIVED, PROCESSED);
        tracking.transition(
                StatusChange.of(id, PROCESSED, SENDING).markSendingStarted().build());
        TransitionResult sent = tracking.completeSend(id, "SMTP", "msg-1", 0);
        assertThat(sent.applied()).isTrue();
        assertThat(sent.resultSequence()).isEqualTo(1);
        NotificationView afterSent = tracking.findById(id).orElseThrow();
        assertThat(afterSent.payloadEncrypted()).isNull(); // nulled once handed to the provider
        TransitionResult delivered = tracking.transition(
                StatusChange.of(id, SENT, DELIVERED).verifiedCallback().build());
        assertThat(delivered.resultSequence()).isEqualTo(2);
        NotificationView view = tracking.findById(id).orElseThrow();
        assertThat(view.payloadEncrypted()).isNull();
        assertThat(view.providerMessageId()).isEqualTo("msg-1");
        List<NotificationOutcome> outcomes = events.stream(NotificationOutcome.class)
                .filter(o -> o.notificationId().equals(id))
                .toList();
        assertThat(outcomes).extracting(NotificationOutcome::status).containsExactly("SENT", "DELIVERED");
        assertThat(outcomes).extracting(NotificationOutcome::sequence).containsExactly(1, 2);
    }

    @Test
    void callbackMayDeliverFromSendingButInternalMayNot() {
        UUID id = created();
        move(id, RECEIVED, PROCESSED);
        move(id, PROCESSED, SENDING);
        assertThatThrownBy(() -> move(id, SENDING, DELIVERED)).isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(tracking.transition(StatusChange.of(id, SENDING, DELIVERED)
                                .verifiedCallback()
                                .build())
                        .applied())
                .isTrue();

        // late send acknowledgement: only provider ids stored, no SENT outcome
        TransitionResult late = tracking.completeSend(id, "GENERIC_HTTP_SMS", "late-1", 0);
        assertThat(late.applied()).isFalse();
        NotificationView view = tracking.findById(id).orElseThrow();
        assertThat(view.status()).isEqualTo(DELIVERED);
        assertThat(view.providerMessageId()).isEqualTo("late-1");
        assertThat(events.stream(NotificationOutcome.class)
                        .filter(o -> o.notificationId().equals(id)))
                .extracting(NotificationOutcome::status)
                .containsExactly("DELIVERED");
    }

    @Test
    void unknownDeliveryStateCanBecomeDelivered() {
        UUID id = created();
        move(id, RECEIVED, PROCESSED);
        move(id, PROCESSED, SENDING);
        tracking.transition(StatusChange.of(id, SENDING, FAILED)
                .error(ErrorCode.DELIVERY_STATE_UNKNOWN, "swept")
                .build());
        TransitionResult r = tracking.transition(StatusChange.of(id, FAILED, DELIVERED)
                .expectedErrorCode(ErrorCode.DELIVERY_STATE_UNKNOWN)
                .verifiedCallback()
                .build());
        assertThat(r.applied()).isTrue();
        assertThat(r.resultSequence()).isEqualTo(2);
        assertThat(tracking.findById(id).orElseThrow().errorCode()).isNull();
    }
}
