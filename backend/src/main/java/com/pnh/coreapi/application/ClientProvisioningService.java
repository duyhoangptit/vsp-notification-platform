package com.pnh.coreapi.application;

import com.pnh.coreapi.ClientProvisioningApi;
import com.pnh.coreapi.adapter.out.ClientRepository;
import com.pnh.coreapi.domain.Client;
import com.pnh.coreapi.domain.ClientScope;
import com.pnh.shared.audit.AuditContext;
import com.pnh.shared.audit.ConfigChanged;
import com.pnh.shared.crypto.EnvelopeEncryptor;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.infra.CacheInvalidationBus;
import com.pnh.shared.infra.CacheNames;
import com.pnh.shared.web.PnhException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Client provisioning (FR-032/034, fix U3): secrets are generated here, returned ONCE to the caller and stored only as
 * a bcrypt hash (client secret) or AES-GCM ciphertext (Kafka signing key). Every change is audited.
 */
@Service
public class ClientProvisioningService implements ClientProvisioningApi {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final ClientRepository clients;
    private final EnvelopeEncryptor encryptor;
    private final ApplicationEventPublisher events;
    private final CacheInvalidationBus bus;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ClientProvisioningService(
            ClientRepository clients,
            EnvelopeEncryptor encryptor,
            ApplicationEventPublisher events,
            CacheInvalidationBus bus,
            TransactionTemplate tx,
            Clock clock) {
        this.clients = clients;
        this.encryptor = encryptor;
        this.events = events;
        this.bus = bus;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Issued create(
            String actor, String clientId, String name, int ratePerMin, Set<String> scopeNames, boolean allowDirect) {
        Set<ClientScope> scopes = parseScopes(String.join(",", scopeNames));
        if (clientId == null || !Client.CLIENT_ID.matcher(clientId).matches()) {
            throw PnhException.field(
                    ErrorCode.INVALID_FIELD, "client_id", "client_id must match " + Client.CLIENT_ID.pattern());
        }
        String secret = token();
        String signingKey = token();
        return AuditContext.runAs(
                actor,
                () -> tx.execute(s -> {
                    if (clients.existsById(clientId)) {
                        throw PnhException.field(
                                ErrorCode.INVALID_FIELD, "client_id", "Client " + clientId + " already exists");
                    }
                    Client c = new Client(
                            clientId,
                            name == null ? clientId : name,
                            BCRYPT.encode(secret),
                            ratePerMin,
                            scopes,
                            allowDirect);
                    c.setKafkaSigningKeyEncrypted(encryptor.encryptString(signingKey));
                    Client saved = clients.saveAndFlush(c);
                    audit(actor, saved, "CREATE", Map.of(), Set.of("client_secret", "kafka_signing_key"));
                    return new Issued(clientId, secret, signingKey);
                }));
    }

    @Override
    public void update(String actor, String clientId, Integer ratePerMin, Set<String> scopeNames, Boolean allowDirect) {
        Set<ClientScope> scopes = scopeNames == null ? null : parseScopes(String.join(",", scopeNames));
        AuditContext.runAs(
                actor,
                () -> tx.execute(s -> {
                    Client c = load(clientId);
                    Map<String, Object> before = snapshot(c);
                    if (ratePerMin != null) {
                        try {
                            c.setRateLimitPerMin(ratePerMin);
                        } catch (IllegalArgumentException e) {
                            throw PnhException.field(ErrorCode.INVALID_FIELD, "rate_limit_per_min", e.getMessage());
                        }
                    }
                    if (scopes != null) {
                        c.setScopes(scopes);
                    }
                    if (allowDirect != null) {
                        c.setAllowDirectContent(allowDirect);
                    }
                    audit(actor, clients.saveAndFlush(c), "UPDATE", before, Set.of());
                    return null;
                }));
    }

    @Override
    public String rotateSecret(String actor, String clientId) {
        String secret = token();
        AuditContext.runAs(
                actor,
                () -> tx.execute(s -> {
                    Client c = load(clientId);
                    Map<String, Object> before = snapshot(c);
                    c.setClientSecretHash(BCRYPT.encode(secret));
                    audit(actor, clients.saveAndFlush(c), "UPDATE", before, Set.of("client_secret"));
                    return null;
                }));
        return secret;
    }

    @Override
    public String rotateKafkaKey(String actor, String clientId) {
        String key = token();
        AuditContext.runAs(
                actor,
                () -> tx.execute(s -> {
                    Client c = load(clientId);
                    Map<String, Object> before = snapshot(c);
                    c.setKafkaSigningKeyEncrypted(encryptor.encryptString(key));
                    audit(actor, clients.saveAndFlush(c), "UPDATE", before, Set.of("kafka_signing_key"));
                    return null;
                }));
        return key;
    }

    @Override
    public void deactivate(String actor, String clientId) {
        AuditContext.runAs(
                actor,
                () -> tx.execute(s -> {
                    Client c = load(clientId);
                    Map<String, Object> before = snapshot(c);
                    c.deactivate();
                    audit(actor, clients.saveAndFlush(c), "DEACTIVATE", before, Set.of());
                    return null;
                }));
    }

    static Set<ClientScope> parseScopes(String csv) {
        Set<ClientScope> out = EnumSet.noneOf(ClientScope.class);
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                try {
                    out.add(ClientScope.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw PnhException.field(
                            ErrorCode.INVALID_FIELD, "scopes", "Unknown scope " + s + " (SEND, TRACK, USER_DELEGATE)");
                }
            }
        }
        return out;
    }

    private Client load(String clientId) {
        return clients.findById(clientId)
                .orElseThrow(() -> new PnhException(ErrorCode.NOT_FOUND, "Client " + clientId + " not found"));
    }

    private void audit(String actor, Client c, String action, Map<String, Object> before, Set<String> changedSecrets) {
        events.publishEvent(new ConfigChanged(
                "CLIENT",
                c.getClientId(),
                action,
                c.getVersion(),
                before,
                snapshot(c),
                Set.of(),
                changedSecrets,
                actor,
                clock.instant()));
        bus.clear(CacheNames.CLIENT_AUTH);
    }

    private static Map<String, Object> snapshot(Client c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client_name", c.getClientName());
        m.put("rate_limit_per_min", c.getRateLimitPerMin());
        m.put("scopes", c.getScopes().toString());
        m.put("allow_direct_content", c.isAllowDirectContent());
        m.put("status", c.getStatus().name());
        return m;
    }

    private String token() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
