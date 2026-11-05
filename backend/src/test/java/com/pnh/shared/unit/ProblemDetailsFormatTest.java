package com.pnh.shared.unit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.GlobalProblemHandler;
import com.pnh.shared.web.PnhException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ProblemDetailsFormatTest {

    record Body(@NotBlank String name, @Size(max = 3) String code) {}

    @RestController
    static class DummyController {
        @GetMapping("/pnh")
        void pnh() {
            throw new PnhException(ErrorCode.TEMPLATE_NOT_FOUND, "Template X not found");
        }

        @GetMapping("/missing")
        void missing() {
            throw PnhException.missingVariables(List.of("name", "otp_code"));
        }

        @GetMapping("/conflict")
        void conflict() {
            throw PnhException.versionConflict(Map.of("template_code", "X", "version", 4));
        }

        @GetMapping("/optimistic")
        void optimistic() {
            throw new ObjectOptimisticLockingFailureException(Object.class, "id");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("db password=hunter2 leaked");
        }

        @PostMapping("/valid")
        void valid(@Valid @RequestBody Body body) {}
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DummyController())
            .setControllerAdvice(new GlobalProblemHandler())
            .build();

    @Test
    void pnhExceptionRendersProblemJson() throws Exception {
        mvc.perform(get("/pnh"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://pnh.example.com/problems/template-not-found"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Template X not found"));
    }

    @Test
    void beanValidationGives422WithFieldErrors() throws Exception {
        mvc.perform(post("/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"code\":\"toolong\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].message").exists());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/valid").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void missingVariablesListed() throws Exception {
        mvc.perform(get("/missing"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MISSING_VARIABLES"))
                .andExpect(jsonPath("$.missing_variables[0]").value("name"))
                .andExpect(jsonPath("$.missing_variables[1]").value("otp_code"));
    }

    @Test
    void versionConflictCarriesCurrent() throws Exception {
        mvc.perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.current.version").value(4));
        mvc.perform(get("/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void unexpectedExceptionHidesDetails() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("IllegalStateException"))));
    }
}
