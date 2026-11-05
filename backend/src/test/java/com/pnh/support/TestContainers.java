package com.pnh.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** JVM-wide singleton containers (started once, reused by every integration test class). */
public final class TestContainers {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("pnh")
            .withUsername("pnh")
            .withPassword("pnh")
            .withCommand("postgres", "-c", "max_connections=600", "-c", "fsync=off");

    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    @SuppressWarnings("resource")
    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        Startables.deepStart(POSTGRES, KAFKA, REDIS).join();
    }

    private TestContainers() {}

    public static String redisAddress() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    /** Simulates a Redis outage without losing the container (other test classes reuse it). */
    public static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    public static void unpauseRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
}
