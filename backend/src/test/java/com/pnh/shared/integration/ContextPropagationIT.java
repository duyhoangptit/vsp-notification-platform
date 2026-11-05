package com.pnh.shared.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pnh.shared.logging.MdcKeys;
import com.pnh.support.IntegrationTestBase;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

/** T026 — MDC and trace context survive the async hop on both executors. */
@Import(ContextPropagationIT.Capture.class)
class ContextPropagationIT extends IntegrationTestBase {

    record Probe(String id, String executor) {}

    record Seen(String clientRequestId, String traceId, String parentTraceId, String thread) {}

    @TestComponent
    static class Capture {
        private final Map<String, Seen> seen = new ConcurrentHashMap<>();

        public Map<String, Seen> seen() {
            return seen;
        }

        private final Tracer tracer;

        Capture(Tracer tracer) {
            this.tracer = tracer;
        }

        @Async("otpExecutor")
        @EventListener(condition = "#p.executor() == 'otp'")
        public void onOtp(Probe p) {
            record(p);
        }

        @Async("standardExecutor")
        @EventListener(condition = "#p.executor() == 'standard'")
        public void onStandard(Probe p) {
            record(p);
        }

        private void record(Probe p) {
            Span span = tracer.nextSpan().name("probe-child").start();
            try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
                Span parent = tracer.currentSpan();
                seen.put(
                        p.id(),
                        new Seen(
                                MDC.get(MdcKeys.CLIENT_REQUEST_ID),
                                span.context().traceId(),
                                parent == null ? null : parent.context().traceId(),
                                Thread.currentThread().getName()));
            } finally {
                span.end();
            }
        }
    }

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    Tracer tracer;

    @Autowired
    ObservationRegistry observations;

    @Autowired
    Capture capture;

    @Test
    void mdcAndTraceReachBothExecutors() {
        Observation root = Observation.start("pnh.intake", observations);
        String traceId;
        try (Observation.Scope scope = root.openScope()) {
            traceId = tracer.currentSpan().context().traceId();
            MDC.put(MdcKeys.CLIENT_REQUEST_ID, "crid-42");
            events.publishEvent(new Probe("a", "otp"));
            events.publishEvent(new Probe("b", "standard"));
        } finally {
            MDC.remove(MdcKeys.CLIENT_REQUEST_ID);
            root.stop();
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> capture.seen().size() == 2);
        Seen otp = capture.seen().get("a");
        Seen std = capture.seen().get("b");
        assertThat(otp.thread()).startsWith("pnh-otp-");
        assertThat(std.thread()).startsWith("pnh-std-");
        assertThat(otp.clientRequestId()).isEqualTo("crid-42");
        assertThat(std.clientRequestId()).isEqualTo("crid-42");
        assertThat(otp.traceId()).isEqualTo(traceId);
        assertThat(std.traceId()).isEqualTo(traceId);
    }
}
