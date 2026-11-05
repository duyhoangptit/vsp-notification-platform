package com.pnh.coreapi.integration;

import static com.pnh.support.SendSupport.json;
import static com.pnh.support.SendSupport.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory.ClientCreds;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** T074 */
class IntakeValidationIT extends StubbedProvidersTestBase {

    private ClientCreds client;

    private MvcResult sendWith(String channel, String recipient, String code, String vars) throws Exception {
        if (client == null) {
            client = data.client("SEND");
        }
        return send(
                mvc,
                client,
                json(
                        "client_request_id",
                        "v-" + UUID.randomUUID(),
                        "user_id",
                        "u",
                        "channel_type",
                        channel,
                        "recipient_target",
                        recipient,
                        "template_code",
                        code,
                        "variables",
                        vars));
    }

    private static String code(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(422);
        return JsonPath.read(r.getResponse().getContentAsString(), "$.code");
    }

    @Test
    void templateErrors() throws Exception {
        assertThat(code(sendWith("EMAIL", "a@b.co", "UNKNOWN_TEMPLATE_X", "{}")))
                .isEqualTo("TEMPLATE_NOT_FOUND");
        String inactive = data.template("EMAIL", "STANDARD", "s", null, "<p>x</p>");
        jdbc.update("UPDATE pnh_templates SET status='INACTIVE' WHERE template_code=?", inactive);
        assertThat(code(sendWith("EMAIL", "a@b.co", inactive, "{}"))).isEqualTo("TEMPLATE_INACTIVE");
        String smsTemplate = data.template("SMS", "STANDARD", null, null, "hi ${a}", "a");
        assertThat(code(sendWith("EMAIL", "a@b.co", smsTemplate, "{\"a\":\"1\"}")))
                .isEqualTo("TEMPLATE_CHANNEL_MISMATCH");
        String two =
                data.template("EMAIL", "STANDARD", "s ${name}", null, "<p>${name} ${otp_code}</p>", "name", "otp_code");
        MvcResult missing = sendWith("EMAIL", "a@b.co", two, "{}");
        assertThat(code(missing)).isEqualTo("MISSING_VARIABLES");
        List<String> names = JsonPath.read(missing.getResponse().getContentAsString(), "$.missing_variables");
        assertThat(names).containsExactlyInAnyOrder("name", "otp_code");
    }

    @Test
    void recipientAndLengthErrors() throws Exception {
        String sms = data.template("SMS", "STANDARD", null, null, "hi ${a}", "a");
        String email = data.template("EMAIL", "STANDARD", "s", null, "<p>x</p>");
        String push = data.template("PUSH", "STANDARD", null, "t", "b");
        assertThat(code(sendWith("SMS", "12ab", sms, "{\"a\":\"1\"}"))).isEqualTo("INVALID_RECIPIENT");
        assertThat(code(sendWith("EMAIL", "not-an-email", email, "{}"))).isEqualTo("INVALID_RECIPIENT");
        assertThat(code(sendWith("PUSH", " ", push, "{}"))).isIn("INVALID_RECIPIENT", "VALIDATION_ERROR");
        assertThat(code(sendWith("SMS", "+84901234567", sms, "{\"a\":\"" + "x".repeat(1600) + "\"}")))
                .isEqualTo("CONTENT_TOO_LONG");
        assertThat(code(sendWith("ZALO", "+84901234567", sms, "{}"))).isEqualTo("UNSUPPORTED_CHANNEL");
    }

    @Test
    void credentialErrors() throws Exception {
        ClientCreds c = data.client("SEND");
        ClientCreds wrong = new ClientCreds(c.clientId(), "wrong-secret", null);
        assertThat(send(mvc, wrong, "{}").getResponse().getStatus()).isEqualTo(401);
        ClientCreds inactive = data.client("SEND", 100, true, "INACTIVE");
        MvcResult r = send(mvc, inactive, "{}");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("CLIENT_INACTIVE");
    }
}
