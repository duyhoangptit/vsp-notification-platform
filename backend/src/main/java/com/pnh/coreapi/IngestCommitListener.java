package com.pnh.coreapi;

/**
 * Hook invoked after an ingest message's intake transaction committed and before its offset is acknowledged
 * (extension point; used by tests to simulate a consumer crash in exactly that window).
 */
public interface IngestCommitListener {
    void afterIntakeCommitted(String clientRequestId);
}
