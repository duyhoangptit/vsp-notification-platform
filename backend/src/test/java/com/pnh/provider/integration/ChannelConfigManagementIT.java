package com.pnh.provider.integration;

import static com.pnh.support.SendSupport.awaitStatus;
import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.notificationId;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import com.pnh.provider.application.ChannelConfigProvider;
import com.pnh.provider.application.ProviderResilienceFactory;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.ConsoleSession;
import com.pnh.support.ProviderStubs;
import com.pnh.support.SecondaryContext;
import com.pnh.support.StubbedProvidersTestBase;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MvcResult;

/** T167 — channel provider configuration without restart (US10). */
class ChannelConfigManagementIT extends StubbedProvidersTestBase {

    static ConfigurableApplicationContext replica;

    @BeforeAll
    static void start() {
        replica = SecondaryContext.start();
    }

    @AfterAll
    static void stop() {
        replica.close();
    }

    private long smsId() {
        return jdbc.queryForObject(
                "SELECT id FROM pnh_channel_configs WHERE channel_type='SMS' AND provider_key='GENERIC_HTTP_SMS'",
                Long.class);
    }

    private long version(long id) {
        return jdbc.queryForObject("SELECT version FROM pnh_channel_configs WHERE id=?", Long.class, id);
    }

    private String smsUpdate(String endpoint, String secrets, long version) {
        return "{\"channel_type\":\"SMS\",\"provider_key\":\"GENERIC_HTTP_SMS\",\"is_active\":true,\"settings\":{\"endpoint\":\""
                + endpoint + "\",\"sender\":\"PNH2\"}" + (secrets == null ? "" : ",\"secrets\":" + secrets)
                + ",\"version\":" + version + "}";
    }

    @Test
    void updateTakesEffectWithoutRestartAndKeepsSecretsSafe() throws Exception {
        ConsoleSession admin = ConsoleSession.login(mvc, data, "ADMIN");
        long id = smsId();
        ChannelConfigProvider remote = replica.getBean(ChannelConfigProvider.class);
        ProviderResilienceFactory remoteResilience = replica.getBean(ProviderResilienceFactory.class);
        remote.active(ChannelType.SMS); // cached on the replica
        remoteResilience.forProvider("SMS", "GENERIC_HTTP_SMS").circuitBreaker().transitionToOpenState();

        ProviderStubs.WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/sms/v2"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message_id\":\"v2\"}")));
        MvcResult r = admin.json(
                put("/console/api/channel-configs/" + id),
                smsUpdate(ProviderStubs.wiremockUrl() + "/sms/v2", "{\"api_key\":\"new-api-key\"}", version(id)));
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);

        UUID nid = notificationId(send(
                mvc,
                data.client("SEND"),
                json(
                        "client_request_id",
                        "cfg-" + UUID.randomUUID(),
                        "user_id",
                        "u",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "content",
                        "{\"body\":\"via v2\"}")));
        assertThat(awaitStatus(tracking, nid, NotificationStatus.SENT).providerMessageId())
                .isEqualTo("v2");
        ProviderStubs.WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/sms/v2"))
                .withHeader("X-Api-Key", WireMock.equalTo("new-api-key")));

        await().atMost(Duration.ofSeconds(1))
                .until(() -> remote.active(ChannelType.SMS)
                        .orElseThrow()
                        .setting("endpoint")
                        .endsWith("/sms/v2"));
        await().atMost(Duration.ofSeconds(1))
                .until(() -> remoteResilience
                        .forProvider("SMS", "GENERIC_HTTP_SMS")
                        .circuitBreaker()
                        .getState()
                        .name()
                        .equals("CLOSED"));
        // omitted secret keeps its old value
        assertThat(remote.active(ChannelType.SMS).orElseThrow().secret("callback_hmac_secret"))
                .isEqualTo(ProviderStubs.SMS_CALLBACK_SECRET);
        byte[] raw =
                jdbc.queryForObject("SELECT secrets_encrypted FROM pnh_channel_configs WHERE id=?", byte[].class, id);
        assertThat(new String(raw, StandardCharsets.ISO_8859_1)).doesNotContain("new-api-key");

        String audit = jdbc.queryForObject(
                "SELECT changed_fields::text FROM pnh_config_audit_log WHERE entity_type='CHANNEL_CONFIG' "
                        + "AND entity_id=? ORDER BY id DESC LIMIT 1",
                String.class,
                Long.toString(id));
        assertThat(audit)
                .contains("\"api_key\": \"***changed***\"")
                .doesNotContain("new-api-key")
                .doesNotContain("callback_hmac_secret");
    }

    @Test
    void validationSingleActiveConnectionTestAndRoles() throws Exception {
        ConsoleSession admin = ConsoleSession.login(mvc, data, "ADMIN");
        long id = smsId();
        MvcResult badUrl =
                admin.json(put("/console/api/channel-configs/" + id), smsUpdate("ftp//nope", null, version(id)));
        assertThat(badUrl.getResponse().getStatus()).isEqualTo(422);
        assertThat(badUrl.getResponse().getContentAsString()).contains("settings.endpoint");

        long pushId = jdbc.queryForObject(
                "SELECT id FROM pnh_channel_configs WHERE channel_type='PUSH' AND is_active", Long.class);
        MvcResult badJson = admin.json(
                put("/console/api/channel-configs/" + pushId),
                "{\"channel_type\":\"PUSH\",\"provider_key\":\"FCM\","
                        + "\"is_active\":true,\"settings\":{\"project_id\":\"p\"},\"secrets\":{\"service_account_json\":\"{not json\"},\"version\":"
                        + version(pushId) + "}");
        assertThat(badJson.getResponse().getStatus()).isEqualTo(422);
        assertThat(badJson.getResponse().getContentAsString()).contains("secrets.service_account_json");

        jdbc.update("DELETE FROM pnh_channel_configs WHERE provider_key = 'ANDROID_SMS_GATEWAY'");
        MvcResult created = admin.json(
                post("/console/api/channel-configs"),
                "{\"channel_type\":\"SMS\",\"provider_key\":"
                        + "\"ANDROID_SMS_GATEWAY\",\"is_active\":true,\"settings\":{\"base_url\":\""
                        + ProviderStubs.wiremockUrl()
                        + "\",\"mode\":\"local\"},\"secrets\":{\"username\":\"u\",\"password\":\"p\"}}");
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pnh_channel_configs WHERE channel_type='SMS' AND is_active",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT provider_key FROM pnh_channel_configs WHERE channel_type='SMS' AND is_active",
                        String.class))
                .isEqualTo("ANDROID_SMS_GATEWAY");

        long emailId = jdbc.queryForObject(
                "SELECT id FROM pnh_channel_configs WHERE channel_type='EMAIL' AND is_active", Long.class);
        long versionBefore =
                jdbc.queryForObject("SELECT version FROM pnh_channel_configs WHERE id=?", Long.class, emailId);
        MvcResult tested = admin.perform(post("/console/api/channel-configs/" + emailId + "/test"));
        // a connection test is not an edit: no version bump (no false VERSION_CONFLICT for editors)
        assertThat(jdbc.queryForObject("SELECT version FROM pnh_channel_configs WHERE id=?", Long.class, emailId))
                .isEqualTo(versionBefore);
        assertThat((Boolean) JsonPath.read(tested.getResponse().getContentAsString(), "$.success"))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT last_test_result FROM pnh_channel_configs WHERE id=?", String.class, emailId))
                .isEqualTo("OK");
        MvcResult fcmTest = admin.perform(post("/console/api/channel-configs/" + pushId + "/test"));
        assertThat((Boolean) JsonPath.read(fcmTest.getResponse().getContentAsString(), "$.success"))
                .isTrue();

        ConsoleSession support = ConsoleSession.login(mvc, data, "SUPPORT_VIEWER");
        assertThat(support.perform(post("/console/api/channel-configs/" + emailId + "/test"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }
}
