package com.pnh.support;

import com.pnh.PnhApplication;
import com.pnh.shared.time.PnhClock;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for integration tests against real PostgreSQL 16, Kafka and Redis 7 (constitution II).
 * All subclasses share one Spring context (same configuration) and one set of containers.
 */
@SpringBootTest(classes = PnhApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(TestDataFactory.class)
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PnhClock clock;

    @Autowired
    protected TestDataFactory data;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", TestContainers.KAFKA::getBootstrapServers);
        registry.add("pnh.redis.address", TestContainers::redisAddress);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }
}
