package com.pnh.support;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.awaitility.Awaitility.await;

import com.pnh.shared.domain.NotificationStatus;
import com.pnh.tracking.TrackingApi;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

public final class InboxSupport {

    private InboxSupport() {}

    /** Sends a PUSH STANDARD notification and waits until it is in the user's inbox. */
    public static UUID push(
            MockMvc mvc,
            TestDataFactory.ClientCreds c,
            TrackingApi tracking,
            JdbcTemplate jdbc,
            String user,
            String title)
            throws Exception {
        UUID id = notificationId(send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "ib-" + UUID.randomUUID(),
                        "user_id",
                        user,
                        "channel_type",
                        "PUSH",
                        "recipient_target",
                        "device-token-" + UUID.randomUUID(),
                        "content",
                        "{\"title\":\"" + title + "\",\"body\":\"Body of " + title
                                + "\",\"data\":{\"order\":\"42\"}}")));
        awaitStatus(tracking, id, NotificationStatus.SENT);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.queryForObject(
                                "SELECT count(*) FROM pnh_notification_read_states WHERE notification_id = ?",
                                Integer.class,
                                id)
                        == 1);
        return id;
    }
}
