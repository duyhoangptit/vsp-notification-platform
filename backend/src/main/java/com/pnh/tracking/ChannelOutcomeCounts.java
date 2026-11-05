package com.pnh.tracking;

/** Outcomes of one channel in a time window (alert fallback when Redis windows are unavailable). */
public record ChannelOutcomeCounts(String channelType, long success, long failure, long stuck) {}
