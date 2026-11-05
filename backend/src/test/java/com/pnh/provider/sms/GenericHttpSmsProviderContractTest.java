package com.pnh.provider.sms;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ChannelProviderContractTest;
import com.pnh.provider.ProviderSettings;
import java.util.Map;
import org.junit.jupiter.api.extension.RegisterExtension;

/** T095 */
class GenericHttpSmsProviderContractTest extends ChannelProviderContractTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final GenericHttpSmsProvider provider = new GenericHttpSmsProvider();

    @Override
    protected ChannelProvider provider() {
        return provider;
    }

    @Override
    protected ProviderSettings settings() {
        return new ProviderSettings(
                1, 0, Map.of("endpoint", wm.baseUrl() + "/sms/send", "sender", "PNH"), Map.of("api_key", "k"));
    }

    @Override
    protected String validRecipient() {
        return "+84901234567";
    }

    @Override
    protected String invalidRecipient() {
        return "0901234567";
    }

    @Override
    protected void stubSuccess() {
        wm.resetAll();
        wm.stubFor(post(urlPathEqualTo("/sms/send"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message_id\":\"m-1\"}")));
        wm.stubFor(any(urlPathEqualTo("/sms/send"))
                .atPriority(10)
                .willReturn(aResponse().withStatus(204)));
    }

    @Override
    protected void stubTransientFailure() {
        wm.resetAll();
        wm.stubFor(post(urlPathEqualTo("/sms/send")).willReturn(aResponse().withStatus(503)));
    }

    @Override
    protected void stubPermanentFailure() {
        wm.resetAll();
        wm.stubFor(post(urlPathEqualTo("/sms/send"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"INVALID_NUMBER\"}")));
    }
}
