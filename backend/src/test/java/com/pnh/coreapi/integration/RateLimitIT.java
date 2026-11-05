package com.pnh.coreapi.integration;

import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestContainers;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** T078 */
class RateLimitIT extends StubbedProvidersTestBase {

    private MvcResult sendOne(ClientCreds c) throws Exception {
        return send(
                mvc,
                c,
                json(
                        "client_request_id",
                        "rl-" + UUID.randomUUID(),
                        "user_id",
                        "u",
                        "channel_type",
                        "SMS",
                        "recipient_target",
                        "+84901234567",
                        "content",
                        "{\"body\":\"x\"}"));
    }

    @Test
    void sixthRequestWithinAMinuteIsLimited() throws Exception {
        ClientCreds c = data.client("SEND", 5, true, "ACTIVE");
        for (int i = 0; i < 5; i++) {
            assertThat(sendOne(c).getResponse().getStatus()).isEqualTo(202);
        }
        MvcResult limited = sendOne(c);
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat((String) JsonPath.read(limited.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("RATE_LIMITED");
        assertThat(Integer.parseInt(limited.getResponse().getHeader("Retry-After")))
                .isPositive();
    }

    @Test
    void localFallbackStillLimitsWhenRedisIsDown() throws Exception {
        ClientCreds c = data.client("SEND", 5, true, "ACTIVE");
        TestContainers.pauseRedis();
        try {
            int accepted = 0;
            int limited = 0;
            for (int i = 0; i < 8; i++) {
                int status = sendOne(c).getResponse().getStatus();
                accepted += status == 202 ? 1 : 0;
                limited += status == 429 ? 1 : 0;
            }
            assertThat(accepted).isEqualTo(5);
            assertThat(limited).isEqualTo(3);
        } finally {
            TestContainers.unpauseRedis();
        }
    }
}
