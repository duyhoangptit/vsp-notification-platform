package com.pnh.shared.integration;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.modulith.events.ApplicationModuleListener;

/** Test listener that fails the first delivery of selected events. */
@TestComponent
public class FlakyListener {

    public record TestEvent(String marker) {}

    private final Set<String> failOnce = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public void failNext(String marker) {
        failOnce.add(marker);
    }

    public int attempts(String marker) {
        return attempts.getOrDefault(marker, new AtomicInteger()).get();
    }

    @ApplicationModuleListener
    void on(TestEvent event) {
        attempts.computeIfAbsent(event.marker(), m -> new AtomicInteger()).incrementAndGet();
        if (failOnce.remove(event.marker())) {
            throw new IllegalStateException("simulated listener failure");
        }
    }
}
