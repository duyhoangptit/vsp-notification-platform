package com.pnh.dispatcher.application;

import com.pnh.coreapi.NotificationAccepted;
import com.pnh.coreapi.OtpNotificationAccepted;
import com.pnh.shared.config.AsyncConfig;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Asynchronous, registry-backed listeners: STANDARD on {@code standardExecutor}, OTP on {@code otpExecutor} (R6). */
@Component
class DispatchListener {

    private final DispatchService dispatch;

    DispatchListener(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @Async(AsyncConfig.STANDARD_EXECUTOR)
    @TransactionalEventListener
    void on(NotificationAccepted event) {
        dispatch.dispatch(event.notificationId());
    }

    @Async(AsyncConfig.OTP_EXECUTOR)
    @TransactionalEventListener
    void on(OtpNotificationAccepted event) {
        dispatch.dispatch(event.notificationId());
    }
}
