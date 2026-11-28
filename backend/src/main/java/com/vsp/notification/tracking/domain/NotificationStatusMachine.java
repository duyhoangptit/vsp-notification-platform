package com.vsp.notification.tracking.domain;

import static com.vsp.notification.shared.domain.NotificationStatus.DELIVERED;
import static com.vsp.notification.shared.domain.NotificationStatus.FAILED;
import static com.vsp.notification.shared.domain.NotificationStatus.PROCESSED;
import static com.vsp.notification.shared.domain.NotificationStatus.SENDING;
import static com.vsp.notification.shared.domain.NotificationStatus.SENT;

import com.vsp.notification.shared.domain.ErrorCode;
import com.vsp.notification.shared.domain.NotificationStatus;

/** Forward-only lifecycle (FR-013, data-model state machine) with the documented FR-041 exception. */
public final class NotificationStatusMachine {

    private NotificationStatusMachine() {}

    public static boolean canTransition(
            NotificationStatus from, ErrorCode currentError, NotificationStatus to, TransitionTrigger trigger) {
        return switch (from) {
            case RECEIVED -> to == PROCESSED || to == FAILED;
            case PROCESSED -> to == SENDING || to == FAILED;
            case SENDING ->
                to == SENT || to == FAILED || (to == DELIVERED && trigger == TransitionTrigger.VERIFIED_CALLBACK);
            case SENT -> to == DELIVERED || to == FAILED;
            case DELIVERED -> false;
            case FAILED ->
                to == DELIVERED
                        && currentError == ErrorCode.DELIVERY_STATE_UNKNOWN
                        && trigger == TransitionTrigger.VERIFIED_CALLBACK;
        };
    }

    public static void require(
            NotificationStatus from, ErrorCode currentError, NotificationStatus to, TransitionTrigger trigger) {
        if (!canTransition(from, currentError, to, trigger)) {
            throw new IllegalStatusTransitionException(from, to, trigger);
        }
    }
}
