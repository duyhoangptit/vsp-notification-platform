package com.pnh.tracking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.domain.NotificationIdGenerator;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.NotificationFixtures;
import com.pnh.support.ResultMessages;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestContainers;
import com.pnh.tracking.StatusChange;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T100 */
class ResultStreamIT extends StubbedProvidersTestBase {

    @Autowired
    NotificationIdGenerator ids;

    private UUID sending() {
        UUID id = tracking.createReceived(NotificationFixtures.email(ids)).notificationId();
        tracking.transition(StatusChange.of(id, NotificationStatus.RECEIVED, NotificationStatus.PROCESSED)
                .build());
        tracking.transition(StatusChange.of(id, NotificationStatus.PROCESSED, NotificationStatus.SENDING)
                .build());
        return id;
    }

    @Test
    void sentThenDeliveredInOrderAndDuplicatesProduceNothing() {
        UUID id = sending();
        tracking.completeSend(id, "SMTP", "m-1", 0);
        tracking.transition(StatusChange.of(id, NotificationStatus.SENT, NotificationStatus.DELIVERED)
                .verifiedCallback()
                .build());
        // duplicate callback → not applied → no extra result
        tracking.transition(StatusChange.of(id, NotificationStatus.SENT, NotificationStatus.DELIVERED)
                .verifiedCallback()
                .build());
        var records = ResultMessages.await(id, 2, Duration.ofSeconds(20));
        assertThat(records).hasSize(2);
        assertThat(records.get(0).value()).contains("\"status\":\"SENT\"").contains("\"sequence\":1");
        assertThat(records.get(1).value()).contains("\"status\":\"DELIVERED\"").contains("\"sequence\":2");
        assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
    }

    @Test
    void failedProducesOneResult() {
        UUID id = sending();
        tracking.transition(StatusChange.of(id, NotificationStatus.SENDING, NotificationStatus.FAILED)
                .error(com.pnh.shared.domain.ErrorCode.PROVIDER_REJECTED, "nope")
                .build());
        var records = ResultMessages.await(id, 1, Duration.ofSeconds(20));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).value()).contains("\"error_code\":\"PROVIDER_REJECTED\"");
    }

    @Test
    void brokerPausedThenResumedStillPublishes() {
        UUID id = sending();
        TestContainers.KAFKA
                .getDockerClient()
                .pauseContainerCmd(TestContainers.KAFKA.getContainerId())
                .exec();
        try {
            tracking.completeSend(id, "SMTP", "m-2", 0);
        } finally {
            TestContainers.KAFKA
                    .getDockerClient()
                    .unpauseContainerCmd(TestContainers.KAFKA.getContainerId())
                    .exec();
        }
        assertThat(ResultMessages.await(id, 1, Duration.ofSeconds(60))).hasSize(1);
    }
}
