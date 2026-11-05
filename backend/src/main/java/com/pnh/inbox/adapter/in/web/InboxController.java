package com.pnh.inbox.adapter.in.web;

import com.pnh.inbox.application.InboxService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Inbox endpoints (scope USER_DELEGATE). */
@RestController
class InboxController {

    record BulkRequest(List<UUID> notificationIds) {}

    private final InboxService service;

    InboxController(InboxService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/users/{userId}/inbox")
    InboxService.Page list(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "unread_only", defaultValue = "false") boolean unreadOnly) {
        return service.list(userId, unreadOnly, page, size);
    }

    @PatchMapping("/api/v1/notifications/{id}/read")
    InboxService.ReadResult read(@PathVariable UUID id, @RequestHeader("X-User-Id") String userId) {
        return service.markRead(userId, id);
    }

    @PostMapping("/api/v1/notifications/bulk-read")
    InboxService.BulkResult bulk(@RequestHeader("X-User-Id") String userId, @RequestBody BulkRequest body) {
        return service.markRead(userId, body.notificationIds());
    }
}
