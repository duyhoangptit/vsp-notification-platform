package com.pnh.shared.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pnh.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** The Dockerfile HEALTHCHECK and `docker compose up --wait` (deploy/prod) rely on these probes. */
class HealthProbesIT extends IntegrationTestBase {

    @Test
    void exposesReadinessAndLivenessProbes() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
