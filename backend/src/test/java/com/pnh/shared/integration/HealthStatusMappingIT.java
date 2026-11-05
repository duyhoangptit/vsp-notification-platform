package com.pnh.shared.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.contributor.Status;

/** DEGRADED stays 200 while DOWN / OUT_OF_SERVICE still fail load-balancer and readiness checks. */
class HealthStatusMappingIT extends IntegrationTestBase {

    @Autowired
    HttpCodeStatusMapper mapper;

    @Test
    void onlyHealthyOrDegradedStatusesReturn200() {
        assertThat(mapper.getStatusCode(Status.UP)).isEqualTo(200);
        assertThat(mapper.getStatusCode(new Status("DEGRADED"))).isEqualTo(200);
        assertThat(mapper.getStatusCode(Status.DOWN)).isEqualTo(503);
        assertThat(mapper.getStatusCode(Status.OUT_OF_SERVICE)).isEqualTo(503);
    }
}
