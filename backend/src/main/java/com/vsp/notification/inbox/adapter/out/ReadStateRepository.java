package com.vsp.notification.inbox.adapter.out;

import com.vsp.notification.inbox.domain.ReadState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReadStateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReadStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertUnread(UUID id, String userId, Instant createdAt) {
        jdbc.update(
                """
                INSERT INTO pnh_notification_read_states (notification_id, user_id, is_read, notification_created_at)
                VALUES (:id, :user, FALSE, :created) ON CONFLICT (notification_id) DO NOTHING""",
                new MapSqlParameterSource("id", id)
                        .addValue("user", userId)
                        .addValue("created", Timestamp.from(createdAt)));
    }

    public List<ReadState> page(String userId, boolean unreadOnly, int page, int size) {
        return jdbc.query(
                "SELECT * FROM pnh_notification_read_states WHERE user_id = :user"
                        + (unreadOnly ? " AND NOT is_read" : "")
                        + " ORDER BY notification_created_at DESC, notification_id DESC LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource("user", userId)
                        .addValue("limit", size)
                        .addValue("offset", (long) page * size),
                (rs, n) -> new ReadState(
                        rs.getObject("notification_id", UUID.class),
                        rs.getString("user_id"),
                        rs.getBoolean("is_read"),
                        rs.getTimestamp("read_at") == null
                                ? null
                                : rs.getTimestamp("read_at").toInstant(),
                        rs.getTimestamp("notification_created_at").toInstant()));
    }

    public long count(String userId, boolean unreadOnly) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM pnh_notification_read_states WHERE user_id = :user"
                        + (unreadOnly ? " AND NOT is_read" : ""),
                new MapSqlParameterSource("user", userId),
                Long.class);
        return n == null ? 0 : n;
    }

    /** Marks read keeping the first read_at; returns the state, empty if not the user's inbox item. */
    public Optional<ReadState> markRead(String userId, UUID id, Instant now) {
        return jdbc
                .query(
                        """
                UPDATE pnh_notification_read_states SET is_read = TRUE, read_at = COALESCE(read_at, :now)
                WHERE notification_id = :id AND user_id = :user
                RETURNING notification_id, user_id, is_read, read_at, notification_created_at""",
                        new MapSqlParameterSource("id", id)
                                .addValue("user", userId)
                                .addValue("now", Timestamp.from(now)),
                        (rs, n) -> new ReadState(
                                rs.getObject(1, UUID.class),
                                rs.getString(2),
                                rs.getBoolean(3),
                                rs.getTimestamp(4).toInstant(),
                                rs.getTimestamp(5).toInstant()))
                .stream()
                .findFirst();
    }

    /** Ids among {@code ids} that belong to the user's inbox. */
    public List<UUID> owned(String userId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT notification_id FROM pnh_notification_read_states WHERE user_id = :user AND notification_id IN (:ids)",
                new MapSqlParameterSource("user", userId).addValue("ids", ids),
                UUID.class);
    }

    public int markReadMany(String userId, Collection<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.update(
                """
                UPDATE pnh_notification_read_states SET is_read = TRUE, read_at = :now
                WHERE user_id = :user AND notification_id IN (:ids) AND NOT is_read""",
                new MapSqlParameterSource("user", userId).addValue("ids", ids).addValue("now", Timestamp.from(now)));
    }
}
