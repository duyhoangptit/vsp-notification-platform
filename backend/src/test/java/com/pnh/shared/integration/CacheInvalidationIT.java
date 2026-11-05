package com.pnh.shared.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.pnh.shared.domain.NotificationStatus;
import com.pnh.support.ConsoleSession;
import com.pnh.support.MailSupport;
import com.pnh.support.SecondaryContext;
import com.pnh.support.SendSupport;
import com.pnh.support.StubbedProvidersTestBase;
import com.pnh.support.TestDataFactory;
import com.pnh.support.TestDataFactory.ClientCreds;
import com.pnh.tracking.TrackingApi;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** T179 / quickstart Q15 — a template edited on replica A is used immediately by replica B. */
class CacheInvalidationIT extends StubbedProvidersTestBase {

    static ConfigurableApplicationContext replica;

    @BeforeAll
    static void start() {
        replica = SecondaryContext.start();
    }

    @AfterAll
    static void stop() {
        replica.close();
    }

    @Test
    void editOnOneReplicaIsUsedByTheOtherAtOnce() throws Exception {
        MockMvc b = MockMvcBuilders.webAppContextSetup((WebApplicationContext) replica)
                .apply(springSecurity())
                .build();
        String code = TestDataFactory.unique("CACHE").toUpperCase().replace('-', '_');
        data.template(code, "EMAIL", "STANDARD", false, null, null, "Old subject", null, "<p>Old ${name}</p>", "name");
        ClientCreds client = data.client("SEND");
        String warm = MailSupport.uniqueAddress();
        // warm replica B's template cache
        UUID first = SendSupport.notificationId(SendSupport.send(
                b,
                client,
                SendSupport.json(
                        "client_request_id",
                        "w-" + UUID.randomUUID(),
                        "user_id",
                        "u",
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        warm,
                        "template_code",
                        code,
                        "variables",
                        "{\"name\":\"A\"}")));
        SendSupport.awaitStatus(replica.getBean(TrackingApi.class), first, NotificationStatus.SENT);

        ConsoleSession editor = ConsoleSession.login(mvc, data, "TEMPLATE_EDITOR");
        String update = "{\"template_code\":\"" + code
                + "\",\"template_name\":\"T\",\"channel_type\":\"EMAIL\",\"category\":\"STANDARD\","
                + "\"subject_template\":\"New subject\",\"body_template\":\"<p>New ${name}</p>\",\"version\":0}";
        assertThat(editor.json(put("/console/api/templates/" + code), update)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        String to = MailSupport.uniqueAddress();
        UUID second = SendSupport.notificationId(SendSupport.send(
                b,
                client,
                SendSupport.json(
                        "client_request_id",
                        "n-" + UUID.randomUUID(),
                        "user_id",
                        "u",
                        "channel_type",
                        "EMAIL",
                        "recipient_target",
                        to,
                        "template_code",
                        code,
                        "variables",
                        "{\"name\":\"B\"}")));
        SendSupport.awaitStatus(replica.getBean(TrackingApi.class), second, NotificationStatus.SENT);
        assertThat(MailSupport.awaitOne(to).getSubject()).isEqualTo("New subject");
    }
}
