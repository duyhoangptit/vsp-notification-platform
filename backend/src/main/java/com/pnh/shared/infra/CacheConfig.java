package com.pnh.shared.infra;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** In-process L1 caches with the TTLs of research R21; cross-replica eviction via {@link CacheInvalidationBus}. */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig {

    @Bean
    CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                cache(CacheNames.TEMPLATES, Duration.ofHours(1), 2_000),
                cache(CacheNames.TEMPLATE_BY_CODE, Duration.ofMinutes(5), 2_000),
                cache(CacheNames.CLIENT_AUTH, Duration.ofMinutes(5), 10_000),
                cache(CacheNames.CLIENTS, Duration.ofMinutes(5), 10_000),
                cache(CacheNames.CHANNEL_CONFIGS, null, 100),
                cache(CacheNames.USER_PREFERENCES, Duration.ofSeconds(60), 100_000),
                cache(CacheNames.ALERT_SETTINGS, Duration.ofMinutes(5), 10)));
        return manager;
    }

    private static CaffeineCache cache(String name, Duration ttl, long max) {
        Caffeine<Object, Object> builder =
                Caffeine.newBuilder().maximumSize(max).recordStats();
        if (ttl != null) {
            builder.expireAfterWrite(ttl);
        }
        return new CaffeineCache(name, builder.build());
    }
}
