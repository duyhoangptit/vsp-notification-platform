package com.pnh.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.provider.ChannelConfigChanged;
import com.pnh.support.CallbackSigner;
import com.pnh.support.ProviderStubs;
import com.pnh.support.StubbedProvidersTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** T103 — callback endpoints vs provider-callbacks.openapi.yaml (SendGrid deferred). */
class ProviderCallbackContractIT extends StubbedProvidersTestBase {

    @Test
    void genericSmsCallback() throws Exception {
        String body = "{\"message_id\":\"x-1\",\"status\":\"DELIVERED\",\"reported_at\":\"2026-11-03T10:00:00Z\"}";
        long ts = clock.instant().getEpochSecond();
        MvcResult ok = mvc.perform(MockMvcRequestBuilders.post("/api/v1/callbacks/sms/GENERIC_HTTP_SMS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-PNH-Timestamp", String.valueOf(ts))
                        .header("X-PNH-Signature", CallbackSigner.generic(ProviderStubs.SMS_CALLBACK_SECRET, ts, body)))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(204);
        ContractSupport.assertConforms(ContractSupport.CALLBACKS_API, ok);
        MvcResult bad = mvc.perform(MockMvcRequestBuilders.post("/api/v1/callbacks/sms/GENERIC_HTTP_SMS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-PNH-Timestamp", String.valueOf(ts))
                        .header("X-PNH-Signature", "sha256=00"))
                .andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(401);
        ContractSupport.assertConforms(ContractSupport.CALLBACKS_API, bad);
    }

    @Test
    void androidGatewayWebhook() throws Exception {
        data.activeChannelConfig(
                "SMS",
                "ANDROID_SMS_GATEWAY",
                Map.of("base_url", ProviderStubs.wiremockUrl(), "mode", "cloud"),
                Map.of("username", "u", "password", "p", "webhook_signing_key", "k"));
        context.publishEvent(new ChannelConfigChanged("SMS", "ANDROID_SMS_GATEWAY", 0));
        String body =
                "{\"event\":\"sms:delivered\",\"deviceId\":\"d\",\"webhookId\":\"w\",\"payload\":{\"messageId\":\"nope\"}}";
        long ts = clock.instant().getEpochSecond();
        MvcResult ok = mvc.perform(MockMvcRequestBuilders.post("/api/v1/callbacks/sms/android-sms-gateway")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Timestamp", String.valueOf(ts))
                        .header("X-Signature", CallbackSigner.androidGateway("k", ts, body)))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(204);
        ContractSupport.assertConforms(ContractSupport.CALLBACKS_API, ok);
        MvcResult bad = mvc.perform(MockMvcRequestBuilders.post("/api/v1/callbacks/sms/android-sms-gateway")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Timestamp", String.valueOf(ts))
                        .header("X-Signature", "00"))
                .andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(401);
        ContractSupport.assertConforms(ContractSupport.CALLBACKS_API, bad);
    }
}
