package com.pnh.support;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.TrackingApi;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

public final class SendSupport {

    private SendSupport() {}

    public static MvcResult send(MockMvc mvc, TestDataFactory.ClientCreds client, String json) throws Exception {
        return mvc.perform(post("/api/v1/notifications/send")
                        .header("Authorization", client.basic())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    public static UUID notificationId(MvcResult r) throws Exception {
        return UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.notification_id"));
    }

    public static NotificationView awaitStatus(TrackingApi tracking, UUID id, NotificationStatus status) {
        return awaitStatus(tracking, id, status, Duration.ofSeconds(20));
    }

    public static NotificationView awaitStatus(
            TrackingApi tracking, UUID id, NotificationStatus status, Duration timeout) {
        await().atMost(timeout)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> tracking.findById(id).map(NotificationView::status).orElse(null) == status);
        return tracking.findById(id).orElseThrow();
    }

    public static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(kv[i]).append("\":");
            String v = kv[i + 1];
            sb.append(v.startsWith("{") || v.startsWith("[") ? v : '"' + v.replace("\"", "\\\"") + '"');
        }
        return sb.append('}').toString();
    }
}
