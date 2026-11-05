package com.pnh.coreapi;

/** An ingest message was rejected (DLT or FAILED result) — alert signal INGEST_REJECTED_SPIKE. */
public record IngestRejected(String clientId, String errorCode, java.time.Instant occurredAt) {}
