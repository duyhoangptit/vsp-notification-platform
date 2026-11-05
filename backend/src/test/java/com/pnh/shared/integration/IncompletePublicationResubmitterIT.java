package com.pnh.shared.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pnh.shared.infra.DistributedLockService;
import com.pnh.shared.infra.IncompletePublicationResubmitter;
import com.pnh.support.IntegrationTestBase;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** T022 */
@Import(FlakyListener.class)
class IncompletePublicationResubmitterIT extends IntegrationTestBase {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    IncompletePublicationResubmitter resubmitter;

    @Autowired
    DistributedLockService locks;

    @Autowired
    FlakyListener listener;

    private int incomplete(String marker) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?",
                Integer.class,
                "%" + marker + "%");
    }

    @Test
    void resubmitsOnlyOldIncompletePublicationsOnce() {
        String marker = UUID.randomUUID().toString();
        listener.failNext(marker);
        tx.executeWithoutResult(s -> events.publishEvent(new FlakyListener.TestEvent(marker)));
        await().atMost(Duration.ofSeconds(10)).until(() -> listener.attempts(marker) >= 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> incomplete(marker) == 1);

        // younger than 2 minutes → untouched
        resubmitter.resubmitNow();
        assertThat(listener.attempts(marker)).isEqualTo(1);

        // two instances: while one holds the lock the other skips its cycle
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = new Thread(() -> locks.tryRunExclusive("event-resubmission", () -> {
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        other.start();
        try {
            held.await(10, TimeUnit.SECONDS);
            clock.setFixed(clock.realInstant().plus(Duration.ofMinutes(3)));
            AtomicBoolean ran = new AtomicBoolean(resubmitter.resubmitNow());
            assertThat(ran).isFalse();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            release.countDown();
        }
        try {
            other.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // older than 2 minutes (Clock moved 3 min ahead) → re-delivered and completed
        assertThat(resubmitter.resubmitNow()).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> incomplete(marker) == 0);
        assertThat(listener.attempts(marker)).isEqualTo(2);
    }
}
