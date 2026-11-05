package com.pnh.provider.application;

import com.pnh.provider.NotificationReadyForDelivery;
import com.pnh.provider.OtpNotificationReadyForDelivery;
import com.pnh.shared.config.AsyncConfig;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Registry-backed async listeners; the orchestrator commits each step in its own transaction. */
@Component
class DeliveryListener {

    private final DeliveryOrchestrator orchestrator;

    DeliveryListener(DeliveryOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Async(AsyncConfig.STANDARD_EXECUTOR)
    @TransactionalEventListener(fallbackExecution = true)
    void on(NotificationReadyForDelivery event) {
        orchestrator.deliver(event.notificationId());
    }

    @Async(AsyncConfig.OTP_EXECUTOR)
    @TransactionalEventListener(fallbackExecution = true)
    void on(OtpNotificationReadyForDelivery event) {
        orchestrator.deliver(event.notificationId());
    }
}
