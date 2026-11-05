package com.pnh.provider.application;

import com.pnh.provider.ChannelConfigChanged;
import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ChannelRegistry;
import com.pnh.provider.ConnectionTestResult;
import com.pnh.provider.adapter.out.ChannelConfigRepository;
import com.pnh.provider.domain.ChannelConfig;
import com.pnh.shared.audit.ConfigChanged;
import com.pnh.shared.audit.PnhAuditorAware;
import com.pnh.shared.crypto.EnvelopeEncryptor;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.FieldError;
import com.pnh.shared.web.PnhException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Channel provider configuration (T169, T171, US10): per-provider validation, write-only secrets merged and AES-GCM
 * encrypted, exactly one active config per channel, optimistic locking, audit with {@code ***changed***}, hot reload
 * through {@link ChannelConfigChanged}.
 */
@Service
public class ChannelConfigCommandService {

    public record SecretView(boolean hasValue, String masked) {}

    public record ConfigView(
            Long id,
            String channelType,
            String providerKey,
            boolean isActive,
            Map<String, Object> settings,
            Map<String, SecretView> secrets,
            Instant lastTestAt,
            String lastTestResult,
            Instant createdAt,
            String createdBy,
            Instant updatedAt,
            String updatedBy,
            long version) {}

    public record Input(
            String channelType,
            String providerKey,
            Boolean isActive,
            Map<String, Object> settings,
            Map<String, String> secrets,
            Long version) {}

    public record TestOutcome(boolean success, String providerError, Instant testedAt) {}

    private final ChannelConfigRepository repository;
    private final ChannelConfigProvider configs;
    private final ChannelRegistry registry;
    private final EnvelopeEncryptor encryptor;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    public ChannelConfigCommandService(
            ChannelConfigRepository repository,
            ChannelConfigProvider configs,
            ChannelRegistry registry,
            EnvelopeEncryptor encryptor,
            ApplicationEventPublisher events,
            JsonMapper json,
            Clock clock) {
        this.repository = repository;
        this.configs = configs;
        this.registry = registry;
        this.encryptor = encryptor;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ConfigView> list() {
        return repository.findAllByOrderByChannelTypeAscProviderKeyAsc().stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public ConfigView create(Input in) {
        ChannelProvider provider = provider(in);
        if (repository
                .findByChannelTypeAndProviderKey(provider.channelType().value(), in.providerKey())
                .isPresent()) {
            throw PnhException.field(
                    ErrorCode.INVALID_FIELD,
                    "provider_key",
                    "A configuration for " + in.providerKey() + " on " + provider.channelType() + " already exists");
        }
        ChannelConfig c = new ChannelConfig(provider.channelType().value(), in.providerKey());
        Map<String, String> secrets = merge(Map.of(), in.secrets());
        validate(provider, in.settings(), secrets);
        apply(c, in, secrets);
        ChannelConfig saved = save(c, Boolean.TRUE.equals(in.isActive()));
        publish(saved, "CREATE", Map.of(), snapshot(saved), new TreeSet<>(secrets.keySet()));
        return view(saved);
    }

    @Transactional
    public ConfigView update(long id, Input in) {
        ChannelConfig c = load(id);
        if (in.version() == null) {
            throw PnhException.field(ErrorCode.MISSING_FIELD, "version", "version is required");
        }
        if (c.isStale(in.version())) {
            throw PnhException.versionConflict(view(c));
        }
        if (!c.getProviderKey().equals(in.providerKey())
                || !c.getChannelType().equals(ChannelType.of(in.channelType()).value())) {
            throw PnhException.field(
                    ErrorCode.INVALID_FIELD, "provider_key", "channel_type and provider_key cannot be changed");
        }
        ChannelProvider provider = provider(in);
        Map<String, String> existing = configs.decryptSecrets(c);
        Map<String, String> secrets = merge(existing, in.secrets());
        validate(provider, in.settings(), secrets);
        Map<String, Object> before = snapshot(c);
        Set<String> changedSecrets = new TreeSet<>();
        if (in.secrets() != null) {
            in.secrets().forEach((k, v) -> {
                if (v != null && !Objects.equals(existing.get(k), v)) {
                    changedSecrets.add(k);
                }
            });
        }
        apply(c, in, secrets);
        ChannelConfig saved = save(c, in.isActive() == null ? c.isActive() : in.isActive());
        publish(saved, "UPDATE", before, snapshot(saved), changedSecrets);
        return view(saved);
    }

    /**
     * Not transactional: the provider call can take seconds and must not hold a DB connection. The result is stored
     * without changing the config version (a test is not an edit and must not cause VERSION_CONFLICT for editors).
     */
    public TestOutcome test(long id) {
        ChannelConfig c = load(id);
        ChannelProvider provider = registry.provider(ChannelType.of(c.getChannelType()), c.getProviderKey())
                .orElseThrow(() -> PnhException.field(
                        ErrorCode.INVALID_FIELD, "provider_key", "No adapter for " + c.getProviderKey()));
        var settings = configs.toSettings(c);
        ConnectionTestResult r;
        try {
            r = provider.testConnection(settings.settings(), settings.secrets());
        } catch (RuntimeException e) {
            r = ConnectionTestResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        Instant at = clock.instant();
        String result = r.success() ? "OK" : r.providerError();
        repository.recordTest(id, at, result == null ? null : result.substring(0, Math.min(500, result.length())));
        return new TestOutcome(r.success(), r.success() ? null : truncate(r.providerError()), at);
    }

    private ChannelConfig save(ChannelConfig c, boolean active) {
        if (active) {
            for (ChannelConfig other : repository.findAll()) {
                if (other.getChannelType().equals(c.getChannelType())
                        && other.isActive()
                        && !Objects.equals(other.getId(), c.getId())) {
                    Map<String, Object> before = snapshot(other);
                    other.setActive(false);
                    repository.saveAndFlush(other);
                    publish(other, "DEACTIVATE", before, snapshot(other), Set.of());
                }
            }
        }
        c.setActive(active);
        return repository.saveAndFlush(c);
    }

    private ChannelProvider provider(Input in) {
        ChannelType channel = ChannelType.parseOrNull(in.channelType());
        if (channel == null || !registry.isRegistered(channel)) {
            throw PnhException.field(
                    ErrorCode.UNSUPPORTED_CHANNEL, "channel_type", "Channel " + in.channelType() + " is not supported");
        }
        return registry.provider(channel, in.providerKey())
                .orElseThrow(() -> PnhException.field(
                        ErrorCode.INVALID_FIELD,
                        "provider_key",
                        "Provider " + in.providerKey() + " is not available for " + channel + " (registered: "
                                + registry.providerKeys(channel) + ")"));
    }

    private void validate(ChannelProvider provider, Map<String, Object> settings, Map<String, String> secrets) {
        Map<String, String> errors = provider.validateSettings(settings == null ? Map.of() : settings, secrets);
        if (!errors.isEmpty()) {
            List<FieldError> fe = errors.entrySet().stream()
                    .map(e -> new FieldError(e.getKey(), e.getValue()))
                    .toList();
            throw new PnhException(ErrorCode.VALIDATION_ERROR, 422, "Invalid provider settings", fe, Map.of());
        }
    }

    private static Map<String, String> merge(Map<String, String> existing, Map<String, String> incoming) {
        Map<String, String> m = new TreeMap<>(existing);
        if (incoming != null) {
            incoming.forEach((k, v) -> {
                if (v == null || v.isEmpty()) {
                    return; // omitted / blank → keep current value (write-only secrets)
                }
                m.put(k, v);
            });
        }
        return m;
    }

    private void apply(ChannelConfig c, Input in, Map<String, String> secrets) {
        c.setSettingsJson(json.writeValueAsString(in.settings() == null ? Map.of() : in.settings()));
        c.setSecrets(
                secrets.isEmpty() ? null : encryptor.encryptString(json.writeValueAsString(secrets)),
                secrets.isEmpty() ? null : encryptor.keyId());
    }

    private void publish(
            ChannelConfig c,
            String action,
            Map<String, Object> before,
            Map<String, Object> after,
            Set<String> changedSecrets) {
        events.publishEvent(new ConfigChanged(
                "CHANNEL_CONFIG",
                c.getId().toString(),
                action,
                c.getVersion(),
                before,
                after,
                Set.of(),
                changedSecrets,
                PnhAuditorAware.currentActor(),
                clock.instant()));
        events.publishEvent(new ChannelConfigChanged(c.getChannelType(), c.getProviderKey(), c.getId()));
    }

    private Map<String, Object> snapshot(ChannelConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("is_active", c.isActive());
        m.put("settings", json.readValue(c.getSettingsJson(), new TypeReference<Map<String, Object>>() {}));
        return m;
    }

    private ChannelConfig load(long id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new PnhException(ErrorCode.NOT_FOUND, "Channel config " + id + " not found"));
    }

    ConfigView view(ChannelConfig c) {
        Map<String, SecretView> secrets = new TreeMap<>();
        registry.provider(ChannelType.of(c.getChannelType()), c.getProviderKey())
                .ifPresent(p -> p.secretKeys().forEach(k -> secrets.put(k, new SecretView(false, null))));
        Map<String, String> stored;
        try {
            stored = configs.decryptSecrets(c);
        } catch (RuntimeException e) {
            stored = Map.of();
        }
        stored.keySet().forEach(k -> secrets.put(k, new SecretView(true, "********")));
        Map<String, Object> settings = json.readValue(c.getSettingsJson(), new TypeReference<Map<String, Object>>() {});
        return new ConfigView(
                c.getId(),
                c.getChannelType(),
                c.getProviderKey(),
                c.isActive(),
                settings,
                secrets,
                c.getLastTestAt(),
                c.getLastTestResult(),
                c.getCreatedAt(),
                c.getCreatedBy(),
                c.getUpdatedAt(),
                c.getUpdatedBy(),
                c.getVersion());
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(500, s.length()));
    }
}
