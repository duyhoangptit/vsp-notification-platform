package com.pnh.provider.integration;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.pnh.provider.ChannelCapabilities;
import com.pnh.provider.ChannelConfigChanged;
import com.pnh.provider.RecipientType;
import com.pnh.provider.fake.FakeChannelProvider;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.ResultMessages;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T175 — Zalo readiness (R25): a new channel is just a new {@link com.pnh.provider.ChannelProvider} bean + a channel
 * config; no schema, contract or core-module change. A provider that requires provider-side templates rejects direct
 * content generically.
 */
@Import(ChannelExtensibilityIT.Adapters.class)
class ChannelExtensibilityIT extends StubbedProvidersTestBase {

    /** Zalo-like adapter: provider-side templates only. */
    static class ExternalTemplateProvider extends FakeChannelProvider {
        ExternalTemplateProvider() {
            super(ChannelCapabilities.builder(RecipientType.ZALO_USER)
                    .requiresExternalTemplate(true)
                    .supportsDirectContent(false)
                    .maxContentLength(2000)
                    .build());
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.of("ZFAKE");
        }

        @Override
        public String providerKey() {
            return "ZFAKE_ZNS";
        }
    }

    @TestConfiguration
    static class Adapters {
        @Bean
        FakeChannelProvider fakeChannelProvider() {
            return new FakeChannelProvider();
        }

        @Bean
        ExternalTemplateProvider externalTemplateProvider() {
            return new ExternalTemplateProvider();
        }
    }

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("fakeChannelProvider")
    FakeChannelProvider fake;

    @Test
    void newChannelWorksEndToEndWithoutCoreChanges() throws Exception {
        long id = data.activeChannelConfig("FAKE", "FAKE_PROVIDER", Map.of(), Map.of());
        context.publishEvent(new ChannelConfigChanged("FAKE", "FAKE_PROVIDER", id));
        ClientCreds c = data.client("SEND");
        UUID nid = notificationId(send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "fake-" + UUID.randomUUID(),
                        "user_id",
                        "u-fake",
                        "channel_type",
                        "FAKE",
                        "recipient_target",
                        "+84901234567",
                        "content",
                        "{\"body\":\"hello new channel\"}")));
        awaitStatus(tracking, nid, NotificationStatus.SENT);
        assertThat(fake.sent()).anySatisfy(r -> assertThat(r.notificationId()).isEqualTo(nid));
        assertThat(ResultMessages.await(nid, 1, Duration.ofSeconds(20)).get(0).value())
                .contains("\"channel_type\":\"FAKE\"")
                .contains("\"status\":\"SENT\"");
    }

    @Test
    void externalTemplateChannelRejectsDirectContent() throws Exception {
        long id = data.activeChannelConfig("ZFAKE", "ZFAKE_ZNS", Map.of(), Map.of());
        context.publishEvent(new ChannelConfigChanged("ZFAKE", "ZFAKE_ZNS", id));
        MvcResult r = send(
                mvc,
                data.client("SEND"),
                json(
                        "client_request_id",
                        "z-" + UUID.randomUUID(),
                        "user_id",
                        "u",
                        "channel_type",
                        "ZFAKE",
                        "recipient_target",
                        "+84901234567",
                        "content",
                        "{\"body\":\"free text\"}"));
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("DIRECT_CONTENT_NOT_ALLOWED");
    }
}
