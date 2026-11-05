package com.pnh.shared.infra;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis via Redisson — non-authoritative (constitution v1.1.0). Lazy initialization and short timeouts so the hub
 * starts and keeps working while Redis is down (R21/R22 fallbacks).
 */
@Configuration(proxyBeanMethods = false)
class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(
            @Value("${pnh.redis.address}") String address, @Value("${pnh.redis.timeout-ms:1000}") int timeoutMs) {
        Config config = new Config();
        config.setLazyInitialization(true);
        config.setCodec(StringCodec.INSTANCE);
        config.useSingleServer()
                .setAddress(address)
                .setTimeout(timeoutMs)
                .setConnectTimeout(timeoutMs)
                .setRetryAttempts(1)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(32);
        return Redisson.create(config);
    }
}
