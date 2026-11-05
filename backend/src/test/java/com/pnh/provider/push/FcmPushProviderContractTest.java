package com.pnh.provider.push;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ChannelProviderContractTest;
import com.pnh.provider.ProviderSettings;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.extension.RegisterExtension;

/** T096 */
class FcmPushProviderContractTest extends ChannelProviderContractTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static String serviceAccount;
    private final FcmPushProvider provider = new FcmPushProvider();

    static synchronized String serviceAccount(String tokenUri) throws Exception {
        if (serviceAccount == null) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            String pem = "-----BEGIN PRIVATE KEY-----\\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes())
                            .encodeToString(gen.generateKeyPair().getPrivate().getEncoded())
                            .replace("\r", "")
                            .replace("\n", "\\n")
                    + "\\n-----END PRIVATE KEY-----\\n";
            serviceAccount =
                    "{\"type\":\"service_account\",\"project_id\":\"p\",\"private_key_id\":\"k\",\"private_key\":\""
                            + pem
                            + "\",\"client_email\":\"x@p.iam.gserviceaccount.com\",\"client_id\":\"1\",\"token_uri\":\""
                            + tokenUri + "\"}";
        }
        return serviceAccount;
    }

    @Override
    protected ChannelProvider provider() {
        return provider;
    }

    @Override
    protected ProviderSettings settings() {
        try {
            return new ProviderSettings(
                    1,
                    0,
                    Map.of("project_id", "p", "base_url", wm.baseUrl(), "token_uri", wm.baseUrl() + "/token"),
                    Map.of("service_account_json", serviceAccount(wm.baseUrl() + "/token")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected String validRecipient() {
        return "device-token-abc";
    }

    @Override
    protected String invalidRecipient() {
        return " ";
    }

    private void token() {
        wm.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    }

    @Override
    protected void stubSuccess() {
        wm.resetAll();
        token();
        wm.stubFor(post(urlPathMatching("/v1/projects/p/messages:send"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"projects/p/messages/1\"}")));
    }

    @Override
    protected void stubTransientFailure() {
        wm.resetAll();
        token();
        wm.stubFor(post(urlPathMatching("/v1/projects/p/messages:send"))
                .willReturn(aResponse().withStatus(503)));
    }

    @Override
    protected void stubPermanentFailure() {
        wm.resetAll();
        token();
        wm.stubFor(
                post(urlPathMatching("/v1/projects/p/messages:send"))
                        .willReturn(
                                aResponse()
                                        .withStatus(404)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}")));
    }
}
