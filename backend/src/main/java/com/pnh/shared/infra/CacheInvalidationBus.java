package com.pnh.shared.infra;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Evicts local Caffeine entries and broadcasts the eviction on Redisson topic {@code pnh:cache-invalidation} so every
 * replica drops stale config within ~1 s (R21). Redis down → local eviction still happens; others rely on TTLs.
 */
@Component
public class CacheInvalidationBus implements SmartLifecycle, DisposableBean {

    public static final String TOPIC = "pnh:cache-invalidation";
    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationBus.class);
    private static final String ALL = "*";

    /** Payload: {@code cacheName|key} ({@code key = *} clears the cache). */
    public record Invalidation(String cacheName, String key) {}

    private final CacheManager cacheManager;
    private final RedissonClient redisson;
    private final RedisAvailability availability;
    private final String instanceId = UUID.randomUUID().toString();
    private final List<Consumer<Invalidation>> listeners = new CopyOnWriteArrayList<>();
    private volatile Integer listenerId;
    private volatile boolean running;

    public CacheInvalidationBus(CacheManager cacheManager, RedissonClient redisson, RedisAvailability availability) {
        this.cacheManager = cacheManager;
        this.redisson = redisson;
        this.availability = availability;
    }

    /** Additional hook for components holding derived state (e.g. provider clients, the clock). */
    public void addListener(Consumer<Invalidation> listener) {
        listeners.add(listener);
    }

    public void evict(String cacheName, Object key) {
        Invalidation inv = new Invalidation(cacheName, key == null ? ALL : String.valueOf(key));
        applyLocally(inv);
        publish(inv);
    }

    public void clear(String cacheName) {
        evict(cacheName, null);
    }

    private void publish(Invalidation inv) {
        try {
            topic().publish(instanceId + "|" + inv.cacheName() + "|" + inv.key());
            availability.markSuccess();
        } catch (RuntimeException e) {
            availability.markFailure(e);
        }
    }

    void applyLocally(Invalidation inv) {
        Cache cache = cacheManager.getCache(inv.cacheName());
        if (cache != null) {
            if (ALL.equals(inv.key())) {
                cache.clear();
            } else {
                cache.evict(inv.key());
            }
        }
        for (Consumer<Invalidation> l : listeners) {
            try {
                l.accept(inv);
            } catch (RuntimeException e) {
                log.warn("Cache invalidation listener failed for {}", inv, e);
            }
        }
    }

    private RTopic topic() {
        return redisson.getTopic(TOPIC, StringCodec.INSTANCE);
    }

    @Override
    public void start() {
        running = true;
        subscribe();
    }

    /** Subscribes; retried by {@link #ensureSubscribed()} if Redis was down at startup. */
    void subscribe() {
        if (listenerId != null) {
            return;
        }
        try {
            listenerId = topic().addListener(String.class, (channel, msg) -> {
                String[] parts = msg.split("\\|", 3);
                if (parts.length == 3 && !instanceId.equals(parts[0])) {
                    applyLocally(new Invalidation(parts[1], parts[2]));
                }
            });
            availability.markSuccess();
        } catch (RuntimeException e) {
            availability.markFailure(e);
            log.warn("Could not subscribe to {} (Redis unavailable); TTLs bound staleness", TOPIC);
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${pnh.cache.resubscribe-interval:30s}")
    void ensureSubscribed() {
        if (running) {
            subscribe();
        }
    }

    @Override
    public void stop() {
        running = false;
        Integer id = listenerId;
        if (id != null) {
            try {
                topic().removeListener(id);
            } catch (RuntimeException ignored) {
                // shutting down
            }
            listenerId = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        stop();
    }

    public String instanceId() {
        return instanceId;
    }
}
