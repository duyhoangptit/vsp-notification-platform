package com.pnh.provider;

/** A channel's provider configuration changed: caches, provider clients and circuit breakers are rebuilt (R13). */
public record ChannelConfigChanged(String channelType, String providerKey, long configId) {}
