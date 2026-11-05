package com.pnh.support;

import com.pnh.shared.crypto.EnvelopeEncryptor;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

/** Inserts configuration rows directly (tests only); unique ids avoid cross-test interference. */
@TestComponent
public class TestDataFactory {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(4);
    private final JdbcTemplate jdbc;
    private final EnvelopeEncryptor encryptor;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public TestDataFactory(JdbcTemplate jdbc, EnvelopeEncryptor encryptor, Clock clock) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.clock = clock;
    }

    public static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    public record ClientCreds(String clientId, String secret, String signingKey) {
        public String basic() {
            return "Basic "
                    + java.util.Base64.getEncoder()
                            .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        }
    }

    public ClientCreds client(String scopes) {
        return client(scopes, 1000, true, "ACTIVE");
    }

    public ClientCreds client(String scopes, int ratePerMin, boolean allowDirect, String status) {
        String id = unique("client").toLowerCase();
        String secret = "secret-" + UUID.randomUUID();
        String signingKey = "sign-" + UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO pnh_clients (client_id, client_name, client_secret_hash, rate_limit_per_min, scopes,
                  allow_direct_content, kafka_signing_key_encrypted, status, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'test', ?, 'test')""",
                id,
                "Test " + id,
                BCRYPT.encode(secret),
                ratePerMin,
                scopes,
                allowDirect,
                encryptor.encryptString(signingKey),
                status,
                now(),
                now());
        return new ClientCreds(id, secret, signingKey);
    }

    public String operator(String role, String password) {
        String username = unique(role.toLowerCase());
        jdbc.update("""
                INSERT INTO pnh_operators (username, display_name, password_hash, role, status, created_at, created_by,
                  updated_at, updated_by) VALUES (?, ?, ?, ?, 'ACTIVE', ?, 'test', ?, 'test')""", username, "Op " + username, BCRYPT.encode(password), role, now(), now());
        return username;
    }

    public String template(String channel, String category, String subject, String title, String body, String... vars) {
        return template(
                unique("T").toUpperCase().replace('-', '_'),
                channel,
                category,
                false,
                null,
                null,
                subject,
                title,
                body,
                vars);
    }

    public String template(
            String code,
            String channel,
            String category,
            boolean critical,
            String otpVariable,
            String expiryVariable,
            String subject,
            String title,
            String body,
            String... vars) {
        jdbc.update(
                """
                INSERT INTO pnh_templates (template_code, template_name, channel_type, category, is_critical, otp_variable,
                  expiry_variable, subject_template, title_template, body_template, required_variables, status,
                  created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, 'test', ?, 'test')""",
                code,
                "Template " + code,
                channel,
                category,
                critical,
                otpVariable,
                expiryVariable,
                subject,
                title,
                body,
                vars,
                now(),
                now());
        return code;
    }

    /** Upserts the single active config of a channel. */
    public long activeChannelConfig(
            String channel, String providerKey, Map<String, Object> settings, Map<String, String> secrets) {
        jdbc.update(
                "UPDATE pnh_channel_configs SET is_active = FALSE, updated_at = ?, version = version + 1 WHERE channel_type = ? AND is_active",
                now(),
                channel);
        byte[] enc =
                secrets == null || secrets.isEmpty() ? null : encryptor.encryptString(json.writeValueAsString(secrets));
        jdbc.update(
                """
                INSERT INTO pnh_channel_configs (channel_type, provider_key, is_active, settings, secrets_encrypted,
                  secrets_key_id, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, TRUE, ?::jsonb, ?, ?, ?, 'test', ?, 'test')
                ON CONFLICT (channel_type, provider_key) DO UPDATE SET is_active = TRUE, settings = EXCLUDED.settings,
                  secrets_encrypted = EXCLUDED.secrets_encrypted, secrets_key_id = EXCLUDED.secrets_key_id,
                  updated_at = EXCLUDED.updated_at, version = pnh_channel_configs.version + 1""",
                channel,
                providerKey,
                json.writeValueAsString(settings),
                enc,
                enc == null ? null : encryptor.keyId(),
                now(),
                now());
        return jdbc.queryForObject(
                "SELECT id FROM pnh_channel_configs WHERE channel_type = ? AND provider_key = ?",
                Long.class,
                channel,
                providerKey);
    }
}
