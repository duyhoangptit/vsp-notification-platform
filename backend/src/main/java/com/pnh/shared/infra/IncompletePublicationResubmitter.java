package com.pnh.shared.infra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-delivers event publications that stayed incomplete for more than 2 minutes (R4). Single runner cluster-wide.
 * The cutoff uses the injected Clock; listeners themselves guard against unsafe replays (FR-039).
 */
@Component
public class IncompletePublicationResubmitter {

    private static final Logger log = LoggerFactory.getLogger(IncompletePublicationResubmitter.class);

    private final IncompleteEventPublications publications;
    private final DistributedLockService locks;
    private final Clock clock;
    private final Duration minAge;
    private final boolean jobsEnabled;

    public IncompletePublicationResubmitter(
            IncompleteEventPublications publications,
            DistributedLockService locks,
            Clock clock,
            @Value("${pnh.events.resubmit-after:2m}") Duration minAge,
            @Value("${pnh.jobs.enabled:true}") boolean jobsEnabled) {
        this.jobsEnabled = jobsEnabled;
        this.publications = publications;
        this.locks = locks;
        this.clock = clock;
        this.minAge = minAge;
    }

    @Scheduled(
            fixedDelayString = "${pnh.events.resubmit-interval:60s}",
            initialDelayString = "${pnh.events.resubmit-initial-delay:60s}")
    public void scheduled() {
        if (jobsEnabled) {
            resubmitNow();
        }
    }

    /** @return whether this instance ran the resubmission (lock acquired) */
    public boolean resubmitNow() {
        return locks.tryRunExclusive("event-resubmission", () -> {
            Instant cutoff = clock.instant().minus(minAge);
            publications.resubmitIncompletePublications(
                    p -> p.getPublicationDate().isBefore(cutoff));
            log.debug("Resubmitted incomplete event publications older than {}", cutoff);
        });
    }
}
