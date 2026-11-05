package com.pnh.support;

import com.pnh.PnhApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** A second application instance (replica) sharing the same Postgres/Kafka/Redis — for multi-replica tests. */
public final class SecondaryContext {

    private SecondaryContext() {}

    public static ConfigurableApplicationContext start(String... extraProperties) {
        String[] base = {
            "spring.datasource.url=" + TestContainers.POSTGRES.getJdbcUrl(),
            "spring.datasource.username=" + TestContainers.POSTGRES.getUsername(),
            "spring.datasource.password=" + TestContainers.POSTGRES.getPassword(),
            "spring.kafka.bootstrap-servers=" + TestContainers.KAFKA.getBootstrapServers(),
            "pnh.redis.address=" + TestContainers.redisAddress(),
            "server.port=0",
            "spring.kafka.consumer.group-id=pnh-replica-" + System.nanoTime(),
            "pnh.kafka.listeners-enabled=false"
        };
        // command-line args: highest precedence, so they win over application.yml
        String[] args = java.util.stream.Stream.concat(
                        java.util.Arrays.stream(base), java.util.Arrays.stream(extraProperties))
                .map(p -> "--" + p)
                .toArray(String[]::new);
        return new SpringApplicationBuilder(PnhApplication.class)
                .profiles("test")
                .run(args);
    }
}
