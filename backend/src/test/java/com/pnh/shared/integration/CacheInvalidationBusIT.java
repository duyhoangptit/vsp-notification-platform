package com.pnh.shared.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pnh.shared.infra.CacheInvalidationBus;
import com.pnh.shared.infra.CacheNames;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.SecondaryContext;
import com.pnh.support.TestContainers;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ConfigurableApplicationContext;

/** T023 — two replicas sharing Redis. */
class CacheInvalidationBusIT extends IntegrationTestBase {

    static ConfigurableApplicationContext replica;

    @Autowired
    CacheInvalidationBus bus;

    @Autowired
    CacheManager cacheManager;

    @BeforeAll
    static void startReplica() {
        replica = SecondaryContext.start();
    }

    @AfterAll
    static void stopReplica() {
        replica.close();
    }

    @Test
    void evictionPropagatesToOtherReplica() {
        Cache remote = replica.getBean(CacheManager.class).getCache(CacheNames.TEMPLATE_BY_CODE);
        remote.put("X", "cached");
        cacheManager.getCache(CacheNames.TEMPLATE_BY_CODE).put("X", "cached");
        bus.evict(CacheNames.TEMPLATE_BY_CODE, "X");
        assertThat(cacheManager.getCache(CacheNames.TEMPLATE_BY_CODE).get("X")).isNull();
        await().atMost(Duration.ofSeconds(1)).until(() -> remote.get("X") == null);
    }

    @Test
    void localEvictionWorksWhileRedisIsDown() {
        Cache local = cacheManager.getCache(CacheNames.TEMPLATE_BY_CODE);
        local.put("Y", "cached");
        TestContainers.pauseRedis();
        try {
            bus.evict(CacheNames.TEMPLATE_BY_CODE, "Y");
            assertThat(local.get("Y")).isNull();
        } finally {
            TestContainers.unpauseRedis();
        }
    }
}
