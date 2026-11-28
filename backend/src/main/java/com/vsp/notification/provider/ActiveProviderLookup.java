package com.vsp.notification.provider;

import com.vsp.notification.shared.domain.ChannelType;
import java.util.Optional;

/** Resolves the provider key of the single active channel configuration. */
@FunctionalInterface
public interface ActiveProviderLookup {
    Optional<String> activeProviderKey(ChannelType channel);
}
