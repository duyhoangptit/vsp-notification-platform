package com.pnh.provider.application;

import com.pnh.provider.ActiveProviderLookup;
import com.pnh.provider.ChannelConfigChanged;
import com.pnh.provider.ProviderSettings;
import com.pnh.provider.adapter.out.ChannelConfigRepository;
import com.pnh.provider.domain.ChannelConfig;
import com.pnh.shared.crypto.CryptoException;
import com.pnh.shared.crypto.EnvelopeEncryptor;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.infra.CacheInvalidationBus;
import com.pnh.shared.infra.CacheNames;
import java.util.Map;
import java.util.Optional;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decrypted active configuration per channel (T060), cached in Caffeine {@code channelConfigs} until a
 * {@link ChannelConfigChanged} (local event or cross-replica invalidation). Secrets that cannot be decrypted fail
 * closed: an error, never partial secrets.
 */
@Component
public class ChannelConfigProvider implements ActiveProviderLookup {

    /** Cached value; {@code Optional.empty()} marks "no active config" so misses are cached too. */
    private record Entry(Optional<ProviderSettings> settings, String providerKey) {}

    private final ChannelConfigRepository repository;
    private final EnvelopeEncryptor encryptor;
    private final JsonMapper json;
    private final Cache cache;
    private final CacheInvalidationBus bus;

    public ChannelConfigProvider(
            ChannelConfigRepository repository,
            EnvelopeEncryptor encryptor,
            JsonMapper json,
            CacheManager cacheManager,
            CacheInvalidationBus bus) {
        this.repository = repository;
        this.encryptor = encryptor;
        this.json = json;
        this.cache = cacheManager.getCache(CacheNames.CHANNEL_CONFIGS);
        this.bus = bus;
    }

    public Optional<ProviderSettings> active(ChannelType channel) {
        return entry(channel).settings();
    }

    @Override
    public Optional<String> activeProviderKey(ChannelType channel) {
        return Optional.ofNullable(entry(channel).providerKey());
    }

    private Entry entry(ChannelType channel) {
        return cache.get(channel.value(), () -> load(channel));
    }

    private Entry load(ChannelType channel) {
        return repository
                .findFirstByChannelTypeAndActiveTrue(channel.value())
                .map(c -> new Entry(Optional.of(toSettings(c)), c.getProviderKey()))
                .orElse(new Entry(Optional.empty(), null));
    }

    /** Configuration of a specific provider (active or not) — used to verify its callbacks. */
    public Optional<ProviderSettings> settingsFor(ChannelType channel, String providerKey) {
        return repository
                .findByChannelTypeAndProviderKey(channel.value(), providerKey)
                .map(this::toSettings);
    }

    public ProviderSettings toSettings(ChannelConfig c) {
        Map<String, Object> settings = json.readValue(c.getSettingsJson(), new TypeReference<Map<String, Object>>() {});
        return new ProviderSettings(c.getId(), c.getVersion(), settings, decryptSecrets(c));
    }

    public Map<String, String> decryptSecrets(ChannelConfig c) {
        if (c.getSecretsEncrypted() == null) {
            return Map.of();
        }
        try {
            return json.readValue(
                    encryptor.decryptString(c.getSecretsEncrypted()), new TypeReference<Map<String, String>>() {});
        } catch (CryptoException e) {
            throw new IllegalStateException(
                    "Secrets of channel config " + c.getId() + " (" + c.getChannelType() + "/" + c.getProviderKey()
                            + ") cannot be decrypted with master key " + encryptor.keyId(),
                    e);
        }
    }

    @EventListener
    public void on(ChannelConfigChanged event) {
        bus.evict(CacheNames.CHANNEL_CONFIGS, event.channelType());
    }
}
