package com.pnh.inbox.application;

import com.pnh.inbox.adapter.out.ReadStateRepository;
import com.pnh.inbox.domain.ReadState;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.TrackingApi;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-app inbox (T160, US6): list with unread count, mark one / many read (≤ 500, first read_at preserved). */
@Service
public class InboxService {

    static final int BULK_MAX = 500;

    public record Item(
            UUID notificationId,
            String title,
            String body,
            Map<String, String> data,
            Instant createdAt,
            boolean isRead,
            Instant readAt) {}

    public record Page(List<Item> items, int page, int size, long total, long unreadCount) {}

    public record ReadResult(UUID notificationId, boolean isRead, Instant readAt) {}

    public record BulkResult(int updatedCount, List<UUID> notFoundIds) {}

    private final ReadStateRepository repository;
    private final TrackingApi tracking;
    private final Clock clock;

    public InboxService(ReadStateRepository repository, TrackingApi tracking, Clock clock) {
        this.repository = repository;
        this.tracking = tracking;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page list(String userId, boolean unreadOnly, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 100);
        List<Item> items = new ArrayList<>();
        for (ReadState r : repository.page(userId, unreadOnly, p, s)) {
            NotificationView n = tracking.findById(r.notificationId()).orElse(null);
            if (n != null) {
                items.add(new Item(
                        n.notificationId(),
                        n.title(),
                        n.compiledContent(),
                        n.pushData(),
                        r.notificationCreatedAt(),
                        r.read(),
                        r.readAt()));
            }
        }
        return new Page(items, p, s, repository.count(userId, unreadOnly), repository.count(userId, true));
    }

    @Transactional
    public ReadResult markRead(String userId, UUID id) {
        ReadState r = repository
                .markRead(userId, id, clock.instant())
                .orElseThrow(() -> new PnhException(ErrorCode.NOT_FOUND, "Inbox notification not found"));
        return new ReadResult(r.notificationId(), r.read(), r.readAt());
    }

    @Transactional
    public BulkResult markRead(String userId, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw PnhException.field(ErrorCode.VALIDATION_ERROR, "notification_ids", "At least one id is required");
        }
        Set<UUID> unique = new LinkedHashSet<>(ids);
        if (unique.size() > BULK_MAX || ids.size() > BULK_MAX) {
            throw new PnhException(ErrorCode.BULK_LIMIT_EXCEEDED, "At most " + BULK_MAX + " ids per request");
        }
        Set<UUID> owned = new LinkedHashSet<>(repository.owned(userId, unique));
        List<UUID> notFound = unique.stream().filter(id -> !owned.contains(id)).toList();
        int updated = repository.markReadMany(userId, owned, clock.instant());
        return new BulkResult(updated, notFound);
    }
}
