package com.pnh.provider.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.pnh.provider.ChannelConfigChanged;
import com.pnh.provider.ProviderSettings;
import com.pnh.provider.application.ChannelConfigProvider;
import com.pnh.shared.domain.ChannelType;
import com.pnh.support.IntegrationTestBase;
import com.pnh.support.SecondaryContext;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;

/** T034 — uses private channels so other tests are unaffected. */
class ChannelConfigProviderIT extends IntegrationTestBase {

    static ConfigurableApplicationContext replica;

    @Autowired
    ChannelConfigProvider provider;

    @Autowired
    ApplicationEventPublisher events;

    @BeforeAll
    static void startReplica() {
        replica = SecondaryContext.start();
    }

    @AfterAll
    static void stopReplica() {
        replica.close();
    }

    @Test
    void activeConfigDecryptedCachedAndEvicted() {
        ChannelType ch = ChannelType.of("CFGTEST");
        assertThat(provider.active(ch)).isEmpty();

        long id = data.activeChannelConfig("CFGTEST", "PROV_A", Map.of("host", "h1"), Map.of("password", "s3cret"));
        events.publishEvent(new ChannelConfigChanged("CFGTEST", "PROV_A", id));

        ProviderSettings s = provider.active(ch).orElseThrow();
        assertThat(s.setting("host")).isEqualTo("h1");
        assertThat(s.secret("password")).isEqualTo("s3cret");
        assertThat(provider.activeProviderKey(ch)).contains("PROV_A");
        int reads = 0;
        for (int i = 0; i < 5; i++) {
            reads += provider.active(ch).isPresent() ? 1 : 0;
        }
        assertThat(reads).isEqualTo(5);

        // cached: a DB change is invisible until ChannelConfigChanged
        jdbc.update("UPDATE pnh_channel_configs SET settings = '{\"host\":\"h9\"}' WHERE id = ?", id);
        assertThat(provider.active(ch).orElseThrow().setting("host")).isEqualTo("h1");
        ChannelConfigProvider remote = replica.getBean(ChannelConfigProvider.class);
        assertThat(remote.active(ch).orElseThrow().setting("host")).isEqualTo("h9"); // replica loads now, then caches
        jdbc.update("UPDATE pnh_channel_configs SET settings = '{\"host\":\"h10\"}' WHERE id = ?", id);
        events.publishEvent(new ChannelConfigChanged("CFGTEST", "PROV_A", id));
        assertThat(provider.active(ch).orElseThrow().setting("host")).isEqualTo("h10");
        await().atMost(Duration.ofSeconds(1))
                .until(() -> "h10".equals(remote.active(ch).orElseThrow().setting("host")));

        // inactive configs are ignored
        jdbc.update("UPDATE pnh_channel_configs SET is_active = FALSE WHERE id = ?", id);
        events.publishEvent(new ChannelConfigChanged("CFGTEST", "PROV_A", id));
        assertThat(provider.active(ch)).isEmpty();
    }

    @Test
    void tamperedSecretsFailClosed() {
        ChannelType ch = ChannelType.of("CFGTAMPER");
        long id = data.activeChannelConfig("CFGTAMPER", "PROV_T", Map.of("host", "h"), Map.of("password", "p"));
        jdbc.update(
                "UPDATE pnh_channel_configs SET secrets_encrypted = "
                        + "set_byte(secrets_encrypted, length(secrets_encrypted) - 1, get_byte(secrets_encrypted, length(secrets_encrypted) - 1) # 1) WHERE id = ?",
                id);
        events.publishEvent(new ChannelConfigChanged("CFGTAMPER", "PROV_T", id));
        assertThatThrownBy(() -> provider.active(ch)).hasStackTraceContaining("cannot be decrypted");
    }
}
