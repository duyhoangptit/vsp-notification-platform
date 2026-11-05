package com.pnh.inbox.application;

import com.pnh.inbox.adapter.out.ReadStateRepository;
import com.pnh.tracking.NotificationOutcome;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** A PUSH STANDARD notification that reached SENT becomes an unread inbox item (T159, FR-018). Idempotent. */
@Component
class InboxProjector {

    private final ReadStateRepository repository;

    InboxProjector(ReadStateRepository repository) {
        this.repository = repository;
    }

    @ApplicationModuleListener
    void on(NotificationOutcome outcome) {
        if ("SENT".equals(outcome.status())
                && "PUSH".equals(outcome.channelType())
                && "STANDARD".equals(outcome.category())
                && outcome.userId() != null) {
            repository.insertUnread(outcome.notificationId(), outcome.userId(), outcome.createdAt());
        }
    }
}
