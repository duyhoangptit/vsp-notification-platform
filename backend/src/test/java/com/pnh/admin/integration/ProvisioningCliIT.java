package com.pnh.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.pnh.admin.adapter.in.cli.ProvisioningCommands;
import com.pnh.shared.crypto.EnvelopeEncryptor;
import com.pnh.support.IntegrationTestBase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** T186 — provisioning CLI (FR-032/034, fix U3). */
class ProvisioningCliIT extends IntegrationTestBase {

    static final String ACTOR = "cli:it-operator";

    @Autowired
    ProvisioningCommands cli;

    @Autowired
    EnvelopeEncryptor encryptor;

    private record Run(int code, String out) {
        String value(String key) {
            return out.lines()
                    .filter(l -> l.startsWith(key + "="))
                    .map(l -> l.substring(key.length() + 1))
                    .findFirst()
                    .orElse(null);
        }
    }

    private Run run(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = cli.execute(List.of(args), new PrintStream(buf, true, StandardCharsets.UTF_8), ACTOR);
        return new Run(code, buf.toString(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> audit(String type, String id) {
        return jdbc.queryForList(
                "SELECT action, version, actor FROM pnh_config_audit_log WHERE entity_type=? AND entity_id=? ORDER BY id",
                type,
                id);
    }

    private int basicStatus(String clientId, String secret) throws Exception {
        return mvc.perform(get("/api/v1/notifications/track/none")
                        .header(
                                "Authorization",
                                "Basic "
                                        + Base64.getEncoder()
                                                .encodeToString(
                                                        (clientId + ":" + secret).getBytes(StandardCharsets.UTF_8))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void clientLifecycle() throws Exception {
        String id = "cli-" + UUID.randomUUID().toString().substring(0, 8);
        Run created = run(
                "create-client", "--client-id=" + id, "--name=CLI client", "--scopes=SEND,TRACK", "--rate-limit=50");
        assertThat(created.code()).isZero();
        String secret = created.value("client_secret");
        String signingKey = created.value("kafka_signing_key");
        assertThat(secret).isNotBlank();
        assertThat(signingKey).isNotBlank();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT client_secret_hash, kafka_signing_key_encrypted FROM pnh_clients WHERE client_id=?", id);
        assertThat((String) row.get("client_secret_hash")).startsWith("$2").doesNotContain(secret);
        assertThat(encryptor.decryptString((byte[]) row.get("kafka_signing_key_encrypted")))
                .isEqualTo(signingKey);
        assertThat(basicStatus(id, secret)).isEqualTo(404); // authenticated (unknown request id)

        assertThat(run("update-client", "--client-id=" + id, "--rate-limit=99", "--allow-direct-content=false")
                        .code())
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT rate_limit_per_min FROM pnh_clients WHERE client_id=?", Integer.class, id))
                .isEqualTo(99);

        Run rotated = run("rotate-client-secret", "--client-id=" + id);
        assertThat(rotated.value("client_secret")).isNotEqualTo(secret);
        assertThat(basicStatus(id, secret)).isEqualTo(401);
        assertThat(basicStatus(id, rotated.value("client_secret"))).isEqualTo(404);
        assertThat(run("rotate-kafka-key", "--client-id=" + id).value("kafka_signing_key"))
                .isNotEqualTo(signingKey);
        assertThat(run("deactivate-client", "--client-id=" + id).code()).isZero();
        assertThat(basicStatus(id, rotated.value("client_secret"))).isEqualTo(403);

        List<Map<String, Object>> entries = audit("CLIENT", id);
        assertThat(entries)
                .extracting(e -> e.get("action"))
                .containsExactly("CREATE", "UPDATE", "UPDATE", "UPDATE", "DEACTIVATE");
        assertThat(entries).extracting(e -> e.get("actor")).containsOnly(ACTOR);
        assertThat(entries)
                .extracting(e -> ((Number) e.get("version")).intValue())
                .containsExactly(0, 1, 2, 3, 4);
        String fields = jdbc.queryForObject(
                "SELECT changed_fields::text FROM pnh_config_audit_log WHERE entity_type='CLIENT' AND entity_id=? "
                        + "ORDER BY id LIMIT 1",
                String.class,
                id);
        assertThat(fields).contains("***changed***").doesNotContain(secret);
    }

    @Test
    void operatorLifecycle() throws Exception {
        String user = "op-" + UUID.randomUUID().toString().substring(0, 8);
        Run created = run("create-operator", "--username=" + user, "--role=SUPPORT_VIEWER", "--display-name=Ops");
        assertThat(created.code()).isZero();
        String pw = created.value("password");
        assertThat(login(user, pw)).isEqualTo(200);
        String reset = run("reset-operator-password", "--username=" + user).value("password");
        assertThat(login(user, pw)).isEqualTo(401);
        assertThat(login(user, reset)).isEqualTo(200);
        assertThat(run("deactivate-operator", "--username=" + user).code()).isZero();
        assertThat(login(user, reset)).isEqualTo(401);
        assertThat(audit("OPERATOR", user))
                .extracting(e -> e.get("action"))
                .containsExactly("CREATE", "UPDATE", "DEACTIVATE");
    }

    @Test
    void invalidInputGivesNonZeroExit() {
        assertThat(run("create-client", "--client-id=Bad ID!").code()).isNotZero();
        assertThat(run("create-operator", "--username=x-" + UUID.randomUUID(), "--role=ROOT")
                        .code())
                .isNotZero();
        assertThat(run("rotate-client-secret", "--client-id=does-not-exist").code())
                .isNotZero();
        assertThat(run("no-such-command").code()).isNotZero();
        assertThat(run().code()).isNotZero();
    }

    private int login(String user, String password) throws Exception {
        return mvc.perform(post("/console/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }
}
