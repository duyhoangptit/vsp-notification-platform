package com.pnh.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.pnh.provider.ChannelConfigChanged;
import com.pnh.provider.application.ProviderResilienceFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import org.springframework.context.ApplicationContext;

/** JVM-wide provider doubles: GreenMail (SMTP), WireMock (generic SMS gateway, FCM HTTP v1 + OAuth token). */
public final class ProviderStubs {

    public static final GreenMail GREENMAIL = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
    public static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());
    public static final String SMS_API_KEY = "sms-api-key";
    public static final String SMS_CALLBACK_SECRET = "sms-callback-secret";
    public static final String FCM_PROJECT = "pnh-it";

    static {
        GREENMAIL.start();
        WIREMOCK.start();
    }

    private ProviderStubs() {}

    public static String wiremockUrl() {
        return "http://localhost:" + WIREMOCK.port();
    }

    /** Makes the stubs the active providers of EMAIL, SMS and PUSH and resets their state. */
    public static void activate(TestDataFactory data, ApplicationContext ctx) {
        data.activeChannelConfig(
                "EMAIL",
                "SMTP",
                Map.of(
                        "host",
                        "localhost",
                        "port",
                        GREENMAIL.getSmtp().getPort(),
                        "tls",
                        false,
                        "from",
                        "PNH <no-reply@pnh.test>"),
                Map.of());
        data.activeChannelConfig(
                "SMS",
                "GENERIC_HTTP_SMS",
                Map.of("endpoint", wiremockUrl() + "/sms/send", "sender", "PNH"),
                Map.of("api_key", SMS_API_KEY, "callback_hmac_secret", SMS_CALLBACK_SECRET));
        data.activeChannelConfig(
                "PUSH",
                "FCM",
                Map.of("project_id", FCM_PROJECT, "base_url", wiremockUrl()),
                Map.of("service_account_json", serviceAccountJson()));
        for (String[] c : new String[][] {{"EMAIL", "SMTP"}, {"SMS", "GENERIC_HTTP_SMS"}, {"PUSH", "FCM"}}) {
            ctx.publishEvent(new ChannelConfigChanged(c[0], c[1], 0));
            ctx.getBean(ProviderResilienceFactory.class).reset(c[0], c[1]);
        }
        reset();
    }

    public static void reset() {
        try {
            GREENMAIL.purgeEmailFromAllMailboxes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(post(urlPathEqualTo("/sms/send"))
                .atPriority(10)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message_id\":\"wm-{{randomValue length=10 type='ALPHANUMERIC'}}\"}")
                        .withTransformers("response-template")));
        WIREMOCK.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        WIREMOCK.stubFor(post(urlPathMatching("/v1/projects/.*/messages:send"))
                .atPriority(10)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"projects/" + FCM_PROJECT + "/messages/123\"}")));
    }

    private static String serviceAccountJson;

    public static synchronized String serviceAccountJson() {
        if (serviceAccountJson == null) {
            try {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(2048);
                KeyPair kp = gen.generateKeyPair();
                String pem = "-----BEGIN PRIVATE KEY-----\\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes())
                                .encodeToString(kp.getPrivate().getEncoded())
                                .replace("\r", "")
                                .replace("\n", "\\n")
                        + "\\n-----END PRIVATE KEY-----\\n";
                serviceAccountJson = "{\"type\":\"service_account\",\"project_id\":\"" + FCM_PROJECT + "\","
                        + "\"private_key_id\":\"k1\",\"private_key\":\"" + pem + "\","
                        + "\"client_email\":\"pnh@" + FCM_PROJECT + ".iam.gserviceaccount.com\",\"client_id\":\"1\","
                        + "\"token_uri\":\"" + wiremockUrl() + "/token\"}";
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return serviceAccountJson;
    }
}
