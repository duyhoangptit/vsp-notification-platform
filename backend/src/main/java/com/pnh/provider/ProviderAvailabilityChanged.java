package com.pnh.provider;

import java.time.Instant;

/** Circuit-breaker state transition of a provider (alert signal PROVIDER_UNAVAILABLE). */
public record ProviderAvailabilityChanged(String channelType, String providerKey, String state, Instant since) {}
