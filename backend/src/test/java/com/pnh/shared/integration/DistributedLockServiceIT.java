package com.pnh.shared.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.infra.DistributedLockService;
import com.pnh.shared.infra.RedisAvailability;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.TestContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;

/** T017 */
class DistributedLockServiceIT extends IntegrationTestBase {

    @Autowired
    DataSource dataSource;

    private DistributedLockService newClient(String address) {
        Config config = new Config();
        config.setLazyInitialization(true);
        config.useSingleServer()
                .setAddress(address)
                .setTimeout(500)
                .setConnectTimeout(500)
                .setRetryAttempts(0);
        RedissonClient redisson = Redisson.create(config);
        return new DistributedLockService(redisson, dataSource, new RedisAvailability(new SimpleMeterRegistry()));
    }

    @Test
    void onlyOneRunnerAcrossTwoClients() throws Exception {
        DistributedLockService a = newClient(TestContainers.redisAddress());
        DistributedLockService b = newClient(TestContainers.redisAddress());
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Thread t = new Thread(() -> a.tryRunExclusive("it-job", () -> {
            runs.incrementAndGet();
            inside.countDown();
            await(release);
        }));
        t.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        boolean ranB = b.tryRunExclusive("it-job", runs::incrementAndGet);
        release.countDown();
        t.join(10_000);
        assertThat(ranB).isFalse();
        assertThat(runs).hasValue(1);
        assertThat(b.tryRunExclusive("it-job", runs::incrementAndGet)).isTrue(); // free again
    }

    @Test
    void fallsBackToAdvisoryLockAfterThreeRedisFailures() {
        DistributedLockService down = newClient("redis://127.0.0.1:1"); // nothing listens there
        AtomicBoolean ran = new AtomicBoolean();
        assertThat(down.tryRunExclusive("it-fallback", () -> ran.set(true))).isFalse();
        assertThat(down.tryRunExclusive("it-fallback", () -> ran.set(true))).isFalse();
        assertThat(ran).isFalse();
        assertThat(down.tryRunExclusive("it-fallback", () -> ran.set(true))).isTrue(); // 3rd failure → Postgres lock
        assertThat(ran).isTrue();

        // advisory lock is exclusive: while held by one fallback runner, another is refused
        DistributedLockService down2 = newClient("redis://127.0.0.1:1");
        for (int i = 0; i < 2; i++) {
            down2.tryRunExclusive("it-fallback-2", () -> {});
        }
        AtomicBoolean nested = new AtomicBoolean(true);
        down.tryRunExclusive("it-fallback-2", () -> nested.set(down2.tryRunExclusive("it-fallback-2", () -> {})));
        assertThat(nested).isFalse();
    }

    private static void await(CountDownLatch l) {
        try {
            l.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
