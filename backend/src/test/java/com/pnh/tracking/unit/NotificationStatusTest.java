package com.pnh.tracking.unit;

import static com.pnh.shared.domain.NotificationStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.tracking.domain.IllegalStatusTransitionException;
import com.pnh.tracking.domain.NotificationStatusMachine;
import com.pnh.tracking.domain.TransitionTrigger;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NotificationStatusTest {

    private static boolean allowed(NotificationStatus from, NotificationStatus to, TransitionTrigger trigger) {
        return NotificationStatusMachine.canTransition(from, null, to, trigger);
    }

    @Test
    void forwardTransitionsAreAllowed() {
        assertThat(allowed(RECEIVED, PROCESSED, TransitionTrigger.INTERNAL)).isTrue();
        assertThat(allowed(RECEIVED, FAILED, TransitionTrigger.INTERNAL)).isTrue();
        assertThat(allowed(PROCESSED, SENDING, TransitionTrigger.INTERNAL)).isTrue();
        assertThat(allowed(PROCESSED, FAILED, TransitionTrigger.INTERNAL)).isTrue();
        assertThat(allowed(SENDING, SENT, TransitionTrigger.INTERNAL)).isTrue();
        assertThat(allowed(SENDING, FAILED, TransitionTrigger.INTERNAL)).isTrue();
        assertThat(allowed(SENT, DELIVERED, TransitionTrigger.VERIFIED_CALLBACK))
                .isTrue();
        assertThat(allowed(SENT, FAILED, TransitionTrigger.VERIFIED_CALLBACK)).isTrue();
    }

    @Test
    void sendingToDeliveredOnlyByVerifiedCallback() {
        assertThat(allowed(SENDING, DELIVERED, TransitionTrigger.VERIFIED_CALLBACK))
                .isTrue();
        assertThat(allowed(SENDING, FAILED, TransitionTrigger.VERIFIED_CALLBACK))
                .isTrue();
        assertThat(allowed(SENDING, DELIVERED, TransitionTrigger.INTERNAL)).isFalse();
    }

    @Test
    void failedWithUnknownDeliveryStateMayBecomeDelivered() {
        assertThat(NotificationStatusMachine.canTransition(
                        FAILED, ErrorCode.DELIVERY_STATE_UNKNOWN, DELIVERED, TransitionTrigger.VERIFIED_CALLBACK))
                .isTrue();
        assertThat(NotificationStatusMachine.canTransition(
                        FAILED, ErrorCode.DELIVERY_STATE_UNKNOWN, DELIVERED, TransitionTrigger.INTERNAL))
                .isFalse();
        assertThat(NotificationStatusMachine.canTransition(
                        FAILED, ErrorCode.PROVIDER_REJECTED, DELIVERED, TransitionTrigger.VERIFIED_CALLBACK))
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(NotificationStatus.class)
    void everyOtherTransitionIsRejected(NotificationStatus from) {
        Set<NotificationStatus> allowedTargets = switch (from) {
            case RECEIVED -> EnumSet.of(PROCESSED, FAILED);
            case PROCESSED -> EnumSet.of(SENDING, FAILED);
            case SENDING -> EnumSet.of(SENT, FAILED, DELIVERED);
            case SENT -> EnumSet.of(DELIVERED, FAILED);
            case DELIVERED, FAILED -> EnumSet.noneOf(NotificationStatus.class);
        };
        for (NotificationStatus to : NotificationStatus.values()) {
            boolean any = allowed(from, to, TransitionTrigger.INTERNAL)
                    || allowed(from, to, TransitionTrigger.VERIFIED_CALLBACK);
            assertThat(any).as("%s -> %s", from, to).isEqualTo(allowedTargets.contains(to));
        }
    }

    @Test
    void terminalStatesAreTerminal() {
        assertThat(DELIVERED.isTerminal()).isTrue();
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(SENT.isTerminal()).isFalse();
        assertThat(NotificationStatus.RECEIVED.isTerminal()).isFalse();
    }

    @Test
    void requireThrowsOnIllegalTransition() {
        assertThatThrownBy(() -> NotificationStatusMachine.require(SENT, null, PROCESSED, TransitionTrigger.INTERNAL))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessageContaining("SENT")
                .hasMessageContaining("PROCESSED");
    }
}
