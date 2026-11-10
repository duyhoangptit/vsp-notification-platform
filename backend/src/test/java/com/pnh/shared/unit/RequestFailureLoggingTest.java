package com.pnh.shared.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.logging.CorrelationFilter;
import com.pnh.shared.web.GlobalProblemHandler;
import com.pnh.shared.web.PnhException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Every failed request leaves a trace: handler log line + access log line. */
class RequestFailureLoggingTest {

    record Body(@NotBlank String name) {}

    @RestController
    static class DummyController {
        @GetMapping("/api/v1/ok")
        String ok() {
            return "ok";
        }

        @GetMapping("/api/v1/business")
        void business() {
            throw new PnhException(ErrorCode.TEMPLATE_NOT_FOUND, "Template X not found");
        }

        @GetMapping("/api/v1/provider-down")
        void providerDown() {
            throw new PnhException(ErrorCode.PROVIDER_UNAVAILABLE, "SMTP unreachable");
        }

        @GetMapping("/api/v1/boom")
        void boom() {
            throw new IllegalStateException("kaboom");
        }

        @PostMapping("/api/v1/valid")
        void valid(@Valid @RequestBody Body body) {}

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DummyController())
            .setControllerAdvice(new GlobalProblemHandler())
            .addFilters(new CorrelationFilter(() -> null))
            .build();

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger handlerLog = (Logger) LoggerFactory.getLogger(GlobalProblemHandler.class);
    private final Logger accessLog = (Logger) LoggerFactory.getLogger("pnh.access");

    @BeforeEach
    void attach() {
        logs.start();
        handlerLog.addAppender(logs);
        accessLog.addAppender(logs);
    }

    @AfterEach
    void detach() {
        handlerLog.detachAppender(logs);
        accessLog.detachAppender(logs);
    }

    @Test
    void businessRejectionIsLoggedAsWarnWithCodeAndPath() throws Exception {
        mvc.perform(get("/api/v1/business")).andExpect(status().isUnprocessableEntity());

        assertThat(lines(GlobalProblemHandler.class.getName(), Level.WARN))
                .singleElement()
                .asString()
                .contains("422", "TEMPLATE_NOT_FOUND", "GET /api/v1/business", "Template X not found");
        assertThat(lines("pnh.access", Level.WARN)).singleElement().asString().contains("GET /api/v1/business -> 422");
    }

    @Test
    void serverSidePnhExceptionIsLoggedAsErrorWithStackTrace() throws Exception {
        mvc.perform(get("/api/v1/provider-down")).andExpect(status().isServiceUnavailable());

        ILoggingEvent event = logs.list.stream()
                .filter(e -> e.getLoggerName().equals(GlobalProblemHandler.class.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("503", "PROVIDER_UNAVAILABLE", "SMTP unreachable");
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(lines("pnh.access", Level.ERROR)).singleElement().asString().contains("-> 503");
    }

    @Test
    void unexpectedExceptionIsLoggedAsError() throws Exception {
        mvc.perform(get("/api/v1/boom")).andExpect(status().isInternalServerError());

        assertThat(lines(GlobalProblemHandler.class.getName(), Level.ERROR))
                .singleElement()
                .asString()
                .contains("IllegalStateException", "GET /api/v1/boom");
    }

    @Test
    void validationFailureLogsFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(lines(GlobalProblemHandler.class.getName(), Level.WARN))
                .singleElement()
                .asString()
                .contains("VALIDATION_ERROR", "name");
    }

    @Test
    void successIsAccessLoggedAtInfoAndNonApiPathsAreSkipped() throws Exception {
        mvc.perform(get("/api/v1/ok")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        assertThat(lines("pnh.access", Level.INFO)).singleElement().asString().contains("GET /api/v1/ok -> 200");
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("/actuator"));
    }

    private List<String> lines(String logger, Level level) {
        return logs.list.stream()
                .filter(e -> e.getLoggerName().equals(logger) && e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
