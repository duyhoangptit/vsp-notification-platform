package com.pnh.provider.application;

import com.pnh.provider.ChannelConfigChanged;
import com.pnh.provider.adapter.out.ChannelConfigRepository;
import com.pnh.provider.domain.ChannelConfig;
import com.pnh.shared.audit.AuditContext;
import com.pnh.shared.crypto.EnvelopeEncryptor;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * LOCAL PROFILE ONLY: fills in encrypted dev secrets for the seeded channel configs (R__local_seed.sql cannot encrypt):
 * GENERIC_HTTP_SMS → WireMock api key + callback secret {@code local-callback-secret} (used by
 * deploy/local/scripts/sms-callback.sh); FCM → a throw-away service account whose token URI is the WireMock stub.
 */
@Component
@Profile("local")
class LocalChannelSecretsSeeder {

    private static final Logger log = LoggerFactory.getLogger(LocalChannelSecretsSeeder.class);

    private final ChannelConfigRepository repository;
    private final EnvelopeEncryptor encryptor;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    LocalChannelSecretsSeeder(
            ChannelConfigRepository repository,
            EnvelopeEncryptor encryptor,
            JsonMapper json,
            TransactionTemplate tx,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.encryptor = encryptor;
        this.json = json;
        this.tx = tx;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        AuditContext.runAs("system", () -> {
            tx.executeWithoutResult(s -> {
                seed(
                        "SMS",
                        "GENERIC_HTTP_SMS",
                        Map.of("api_key", "local-api-key", "callback_hmac_secret", "local-callback-secret"));
                seed("PUSH", "FCM", Map.of("service_account_json", serviceAccount()));
            });
            return null;
        });
    }

    private void seed(String channel, String providerKey, Map<String, String> secrets) {
        repository
                .findByChannelTypeAndProviderKey(channel, providerKey)
                .filter(c -> c.getSecretsEncrypted() == null)
                .ifPresent(c -> {
                    c.setSecrets(encryptor.encryptString(json.writeValueAsString(secrets)), encryptor.keyId());
                    ChannelConfig saved = repository.saveAndFlush(c);
                    events.publishEvent(new ChannelConfigChanged(channel, providerKey, saved.getId()));
                    log.info("Local dev secrets seeded for {}/{}", channel, providerKey);
                });
    }

    private static String serviceAccount() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes())
                            .encodeToString(gen.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            return "{\"type\":\"service_account\",\"project_id\":\"pnh-local\",\"private_key_id\":\"local\",\"private_key\":"
                    + quote(pem) + ",\"client_email\":\"pnh@pnh-local.iam.gserviceaccount.com\",\"client_id\":\"1\","
                    + "\"token_uri\":\"http://localhost:8089/token\"}";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
