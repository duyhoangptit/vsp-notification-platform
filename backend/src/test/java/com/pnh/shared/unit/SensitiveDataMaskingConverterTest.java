package com.pnh.shared.unit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.pnh.shared.logging.SensitiveDataMaskingConverter;
import com.pnh.shared.logging.SensitiveValues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SensitiveDataMaskingConverterTest {

    private final SensitiveDataMaskingConverter converter = new SensitiveDataMaskingConverter();

    @AfterEach
    void clear() {
        SensitiveValues.clear();
    }

    private String render(String message, Object... args) {
        LoggerContext ctx = new LoggerContext();
        LoggingEvent event = new LoggingEvent("x", ctx.getLogger("t"), Level.INFO, message, null, args);
        return converter.convert(event);
    }

    @Test
    void masksEmailAndPhone() {
        String out = render("sending to {} and {}", "user@example.com", "+84901234678");
        assertThat(out).doesNotContain("user@example.com").doesNotContain("+84901234678");
        assertThat(out).contains("u***@example.com").contains("+84*****678");
    }

    @Test
    void masksRegisteredSensitiveValues() {
        SensitiveValues.register("654321");
        assertThat(render("Your code 654321 rendered")).isEqualTo("Your code ****** rendered");
    }

    @Test
    void masksSecretJsonFields() {
        String out = render(
                "config {\"host\":\"smtp\",\"password\":\"p@ss\",\"api_key\": \"AK-1\",\"secret\":\"s\",\"service_account_json\":\"{x}\"}");
        assertThat(out)
                .contains("\"host\":\"smtp\"")
                .doesNotContain("p@ss")
                .doesNotContain("AK-1")
                .doesNotContain("\"s\"")
                .doesNotContain("{x}")
                .contains("\"password\":\"***\"");
    }

    @Test
    void doesNotMangleIdentifiers() {
        String out = render("notification 0192f0c4-7b1e-7c3a-9f00-123456789abc at 1730628000000");
        assertThat(out).isEqualTo("notification 0192f0c4-7b1e-7c3a-9f00-123456789abc at 1730628000000");
    }

    @Test
    void holderClearedRemovesValues() {
        SensitiveValues.register("999888");
        SensitiveValues.clear();
        assertThat(render("code 999888")).isEqualTo("code 999888");
        assertThat(SensitiveValues.snapshot()).isEmpty();
    }
}
