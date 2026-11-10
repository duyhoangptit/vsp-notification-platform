package com.pnh.shared.integration;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.StubbedProvidersTestBase;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** T076 — intake → dispatch → provider.send in one trace; MDC on every stage; no OTP/recipient in spans. */
@Import(EndToEndTracingIT.Exporter.class)
class EndToEndTracingIT extends StubbedProvidersTestBase {

    @TestConfiguration
    static class Exporter {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    InMemorySpanExporter spans;

    @Test
    void oneTraceAcrossStages() throws Exception {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        try {
            String requestId = "trace-" + UUID.randomUUID();
            UUID id = notificationId(send(
                    mvc,
                    data.client("SEND"),
                    json(
                            "client_request_id",
                            requestId,
                            "user_id",
                            "u",
                            "channel_type",
                            "SMS",
                            "recipient_target",
                            "+84901234567",
                            "category",
                            "OTP",
                            "otp_value",
                            "135790",
                            "expires_at",
                            "2099-01-01T00:00:00Z",
                            "content",
                            "{\"body\":\"Code 135790\"}")));
            awaitStatus(tracking, id, NotificationStatus.SENT);
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> names(id).containsAll(Set.of("pnh.intake", "pnh.dispatch", "pnh.provider.send")));

            List<SpanData> ours = spansOf(id);
            assertThat(ours.stream().map(SpanData::getTraceId).distinct()).hasSize(1);
            for (SpanData s : ours) {
                String attrs = s.getAttributes().toString();
                assertThat(attrs).doesNotContain("135790").doesNotContain("+84901234567");
            }
            Set<String> stagesLogged = logs.list.stream()
                    .filter(e -> id.toString().equals(e.getMDCPropertyMap().get("notification_id")))
                    .peek(e -> assertThat(e.getMDCPropertyMap().get("client_request_id"))
                            .isEqualTo(requestId))
                    .map(ILoggingEvent::getLoggerName)
                    .collect(Collectors.toSet());
            assertThat(stagesLogged).anyMatch(n -> n.contains("coreapi"));
            assertThat(stagesLogged).anyMatch(n -> n.contains("dispatcher"));
            assertThat(stagesLogged).anyMatch(n -> n.contains("provider"));
        } finally {
            root.detachAppender(logs);
        }
    }

    /** The id handed to the caller is the real HTTP span's trace id, and the access log line carries it in MDC. */
    @Test
    void traceIdIsReturnedToTheCaller() throws Exception {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger access = (Logger) LoggerFactory.getLogger("pnh.access");
        access.addAppender(logs);
        try {
            org.springframework.mock.web.MockHttpServletResponse res = mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                            "/api/v1/notifications/send")
                                    .contentType("application/json")
                                    .content("{}"))
                    .andReturn()
                    .getResponse();

            assertThat(res.getStatus()).isEqualTo(401); // rejected by Spring Security, before any controller
            String traceId = res.getHeader("X-Trace-Id");
            assertThat(traceId).matches("[0-9a-f]{32}");
            assertThat(res.getContentAsString()).contains("\"trace_id\":\"" + traceId + "\"");
            // batch span processor exports every ~5 s
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> spans.getFinishedSpanItems().stream()
                            .anyMatch(s -> s.getTraceId().equals(traceId)
                                    && s.getParentSpanContext().getSpanId().equals("0000000000000000")));
            assertThat(logs.list)
                    .anySatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry("traceId", traceId));
        } finally {
            access.detachAppender(logs);
        }
    }

    private List<SpanData> spansOf(UUID id) {
        return spans.getFinishedSpanItems().stream()
                .filter(s -> id.toString()
                        .equals(s.getAttributes().asMap().entrySet().stream()
                                .filter(e -> e.getKey().getKey().equals("notification_id"))
                                .map(e -> String.valueOf(e.getValue()))
                                .findFirst()
                                .orElse(null)))
                .toList();
    }

    private Set<String> names(UUID id) {
        return spansOf(id).stream().map(SpanData::getName).collect(Collectors.toSet());
    }
}
