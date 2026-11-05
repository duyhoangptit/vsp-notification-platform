package com.pnh.provider;

import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Registered channels and their adapters, discovered from {@link ChannelProvider} beans (research R25). */
public class ChannelRegistry {

    private final Map<ChannelType, Map<String, ChannelProvider>> providers = new LinkedHashMap<>();
    private final ActiveProviderLookup lookup;

    public ChannelRegistry(List<ChannelProvider> beans, ActiveProviderLookup lookup) {
        this.lookup = lookup;
        for (ChannelProvider p : beans) {
            Map<String, ChannelProvider> byKey = providers.computeIfAbsent(p.channelType(), c -> new LinkedHashMap<>());
            if (byKey.putIfAbsent(p.providerKey(), p) != null) {
                throw new IllegalStateException(
                        "Duplicate provider_key " + p.providerKey() + " for channel " + p.channelType());
            }
        }
    }

    public boolean isRegistered(ChannelType channel) {
        return channel != null && providers.containsKey(channel);
    }

    public Set<ChannelType> registeredChannels() {
        return Set.copyOf(providers.keySet());
    }

    public Optional<ChannelProvider> provider(ChannelType channel, String providerKey) {
        return Optional.ofNullable(providers.getOrDefault(channel, Map.of()).get(providerKey));
    }

    public Set<String> providerKeys(ChannelType channel) {
        return Set.copyOf(providers.getOrDefault(channel, Map.of()).keySet());
    }

    /** @throws PnhException UNSUPPORTED_CHANNEL or CHANNEL_NOT_CONFIGURED */
    public ChannelProvider activeProvider(ChannelType channel) {
        if (!isRegistered(channel)) {
            throw new PnhException(ErrorCode.UNSUPPORTED_CHANNEL, "Channel " + channel + " is not supported");
        }
        return lookup.activeProviderKey(channel)
                .flatMap(key -> provider(channel, key))
                .orElseThrow(() -> new PnhException(
                        ErrorCode.CHANNEL_NOT_CONFIGURED,
                        "Channel " + channel + " has no active provider configuration"));
    }

    public Optional<ChannelProvider> findActiveProvider(ChannelType channel) {
        if (!isRegistered(channel)) {
            return Optional.empty();
        }
        return lookup.activeProviderKey(channel).flatMap(key -> provider(channel, key));
    }

    /** Capabilities of the active adapter, or of the first registered adapter when none is active. */
    public ChannelCapabilities capabilities(ChannelType channel) {
        if (!isRegistered(channel)) {
            throw new PnhException(ErrorCode.UNSUPPORTED_CHANNEL, "Channel " + channel + " is not supported");
        }
        return findActiveProvider(channel)
                .orElseGet(() -> providers.get(channel).values().iterator().next())
                .capabilities();
    }
}
