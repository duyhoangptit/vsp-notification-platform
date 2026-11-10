package com.pnh.shared.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.logging.CorrelationFilter;
import com.pnh.shared.logging.TraceIds;
import com.pnh.shared.web.GlobalProblemHandler;
import com.pnh.shared.web.PnhException;
import com.pnh.shared.web.ProblemFactory;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The trace id reaches the caller (header on every response, {@code trace_id} in problem bodies). */
class TraceIdResponseTest {

    private static final String TRACE = "09751c214f483f66a8cfe4be09d97ba9";

    @RestController
    static class DummyController {
        final AtomicReference<String> mdcSeen = new AtomicReference<>();

        @GetMapping("/api/v1/ok")
        String ok() {
            mdcSeen.set(MDC.get(TraceIds.MDC_KEY));
            return "ok";
        }

        @GetMapping("/api/v1/business")
        void business() {
            throw new PnhException(ErrorCode.TEMPLATE_NOT_FOUND, "Template X not found");
        }

        @GetMapping("/api/v1/boom")
        void boom() {
            throw new IllegalStateException("kaboom");
        }
    }

    private final DummyController controller = new DummyController();

    private MockMvc mvc(String spanTraceId) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalProblemHandler())
                .addFilters(new CorrelationFilter(() -> spanTraceId))
                .build();
    }

    @Test
    void successResponseCarriesTraceHeader() throws Exception {
        mvc(TRACE)
                .perform(get("/api/v1/ok"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", TRACE));
    }

    @Test
    void problemBodiesCarryTraceId() throws Exception {
        MockMvc mvc = mvc(TRACE);
        mvc.perform(get("/api/v1/business"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("X-Trace-Id", TRACE))
                .andExpect(jsonPath("$.trace_id").value(TRACE));
        mvc.perform(get("/api/v1/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.trace_id").value(TRACE));
    }

    @Test
    void withoutSpanAnIdIsGeneratedAndPutInMdcForTheRequestOnly() throws Exception {
        MvcResult result = mvc(null).perform(get("/api/v1/ok")).andReturn();

        String generated = result.getResponse().getHeader("X-Trace-Id");
        assertThat(generated).matches("[0-9a-f]{32}");
        assertThat(controller.mdcSeen.get()).isEqualTo(generated);
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
    }

    @Test
    void filterLevelJsonIncludesTraceIdWhenPresent() {
        assertThat(ProblemFactory.toJson(ErrorCode.UNAUTHORIZED, 401, "Sign in required", TRACE))
                .endsWith(",\"trace_id\":\"" + TRACE + "\"}");
        assertThat(ProblemFactory.toJson(ErrorCode.UNAUTHORIZED, 401, "Sign in required", null))
                .doesNotContain("trace_id");
    }
}
