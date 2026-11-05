package com.pnh.coreapi;

import java.util.Set;

/**
 * Published API for client provisioning (used by the admin CLI). Generated secrets are returned once and stored only
 * hashed (client secret) or encrypted (Kafka signing key).
 */
public interface ClientProvisioningApi {

    /** Credentials issued at creation; shown to the operator exactly once. */
    record Issued(String clientId, String clientSecret, String kafkaSigningKey) {
        @Override
        public String toString() {
            return "Issued[" + clientId + "]";
        }
    }

    /** @param scopes scope names (SEND, TRACK, USER_DELEGATE) */
    Issued create(String actor, String clientId, String name, int ratePerMin, Set<String> scopes, boolean allowDirect);

    void update(String actor, String clientId, Integer ratePerMin, Set<String> scopes, Boolean allowDirect);

    String rotateSecret(String actor, String clientId);

    String rotateKafkaKey(String actor, String clientId);

    void deactivate(String actor, String clientId);
}
