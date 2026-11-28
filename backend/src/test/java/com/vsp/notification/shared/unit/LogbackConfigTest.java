package com.vsp.notification.shared.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.vsp.notification.shared.logging.SensitiveValues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

/** logback-spring.xml per devops/standards: {@code container} → masked logstash JSON; otherwise masked text. */
@ExtendWith(OutputCaptureExtension.class)
class LogbackConfigTest {

    private static final String PROBE = "com.vsp.notification.Probe";

    private void initWithProfiles(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        env.setProperty(
                "logging.structured.json.customizer",
                "com.vsp.notification.shared.logging.MaskingJsonMembersCustomizer");
        LoggingSystem system = LoggingSystem.get(getClass().getClassLoader());
        system.cleanUp();
        system.beforeInitialize();
        system.initialize(new LoggingInitializationContext(env), "classpath:logback-spring.xml", null);
    }

    @AfterEach
    void restore() {
        SensitiveValues.clear();
        MDC.clear();
        initWithProfiles("test"); // back to text for later tests in the same JVM
    }

    private static String line(CapturedOutput output, String marker) {
        return output.getOut()
                .lines()
                .filter(l -> l.contains(marker))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void textWithoutContainerProfile(CapturedOutput output) {
        initWithProfiles();
        MDC.put("notification_id", "n-1");
        LoggerFactory.getLogger(PROBE).info("text-probe");
        initWithProfiles("local");
        LoggerFactory.getLogger(PROBE).info("local-probe");

        assertThat(line(output, "text-probe"))
                .doesNotStartWith("{")
                .contains("INFO")
                .contains("nid=n-1");
        assertThat(line(output, "local-probe")).doesNotStartWith("{");
    }

    @Test
    void logstashJsonWithContainerProfile(CapturedOutput output) {
        initWithProfiles("container");
        MDC.put("notification_id", "n-1");
        LoggerFactory.getLogger(PROBE).info("json-probe");

        assertThat(line(output, "json-probe"))
                .startsWith("{")
                .contains("\"level\":\"INFO\"")
                .contains("\"logger_name\":\"" + PROBE + "\"")
                .contains("\"message\":\"json-probe\"")
                .contains("\"notification_id\":\"n-1\"")
                .contains("\"@timestamp\"");
    }

    @Test
    void localPlusContainerIsJson(CapturedOutput output) {
        initWithProfiles("local", "container");
        LoggerFactory.getLogger(PROBE).info("both-probe");

        assertThat(line(output, "both-probe")).startsWith("{");
    }

    @Test
    void jsonMessageIsMasked(CapturedOutput output) {
        initWithProfiles("container");
        SensitiveValues.register("654321");
        LoggerFactory.getLogger(PROBE).info("json-mask-probe {} {} code 654321", "user@example.com", "+84901234678");

        assertThat(line(output, "json-mask-probe"))
                .doesNotContain("user@example.com")
                .doesNotContain("+84901234678")
                .doesNotContain("654321")
                .contains("u***@example.com")
                .contains("+84*****678");
    }

    @Test
    void textMessageIsMasked(CapturedOutput output) {
        initWithProfiles();
        SensitiveValues.register("654321");
        LoggerFactory.getLogger(PROBE).info("text-mask-probe {} code 654321", "user@example.com");

        assertThat(line(output, "text-mask-probe"))
                .doesNotContain("user@example.com")
                .doesNotContain("654321")
                .contains("u***@example.com");
    }

    @Test
    void noisyLibrariesHiddenBelowWarn(CapturedOutput output) {
        initWithProfiles("container");
        LoggerFactory.getLogger("org.apache.kafka.clients.Probe").info("kafka-info-probe");
        LoggerFactory.getLogger("org.redisson.Probe").info("redisson-info-probe");
        LoggerFactory.getLogger("org.apache.kafka.clients.Probe").warn("kafka-warn-probe");

        assertThat(output.getOut())
                .doesNotContain("kafka-info-probe")
                .doesNotContain("redisson-info-probe")
                .contains("kafka-warn-probe");
    }
}
