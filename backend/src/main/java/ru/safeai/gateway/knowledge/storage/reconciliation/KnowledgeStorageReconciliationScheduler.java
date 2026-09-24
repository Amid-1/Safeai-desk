package ru.safeai.gateway.knowledge.storage.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Safe automatic subset only: INTENT (unconfirmed PUT) is NEVER auto-deleted.
 * STORED objects are fenced, linked() cannot succeed after cleanup claim.
 * Disabled by default until the V56 migration, metrics and operational review.
 */
@Component
@ConditionalOnProperty(prefix="safeai.knowledge.storage-reconciliation",
        name="enabled", havingValue="true")
public class KnowledgeStorageReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            KnowledgeStorageReconciliationScheduler.class);
    private final KnowledgeStorageUploadJournal journal;
    private final ObjectStorage storage;
    private final Clock clock;
    private static final Duration GRACE = Duration.ofHours(24);
    private static final int MAX_BATCH = 20;

    public KnowledgeStorageReconciliationScheduler(
            KnowledgeStorageUploadJournal journal, ObjectStorage storage, Clock clock) {
        this.journal = Objects.requireNonNull(journal);
        this.storage = Objects.requireNonNull(storage);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(fixedDelayString=
            "${safeai.knowledge.storage-reconciliation.poll-delay:5m}")
    public void poll() {
        Instant cutoff = clock.instant().minus(GRACE);
        int quarantined = journal.quarantineUncertain(cutoff, MAX_BATCH);
        if (quarantined > 0) {
            log.warn("Quarantined {} ambiguous Knowledge S3 upload intents", quarantined);
        }
        int staleCleanup = journal.quarantineExpiredCleanup(cutoff, MAX_BATCH);
        if (staleCleanup > 0) {
            log.warn("Quarantined {} ambiguous expired Knowledge S3 cleanup claims", staleCleanup);
        }
        int linked = journal.reconcileLinked(cutoff, MAX_BATCH);
        if (linked > 0) {
            log.info("Reconciled {} already linked Knowledge S3 objects", linked);
        }
        for (int i = 0; i < MAX_BATCH; i++) {
            KnowledgeStorageUploadJournal.CleanupClaim claim =
                    journal.claimStoredOrphan(cutoff);
            if (claim == null) return;
            try {
                // Missing objects are already clean for the local backend;
                // the S3 DELETE operation is idempotent for missing keys.
                storage.delete(claim.key());
                journal.cleaned(claim);
                log.info("Reconciled Knowledge unlinked S3 object: intentId={}", claim.id());
            } catch (IOException exception) {
                // A timeout may mean that S3 completed deletion: do not
                // blindly resubmit an ambiguous physical operation.
                journal.cleanupUncertain(claim);
                log.warn("Knowledge orphan cleanup requires review: intentId={}", claim.id());
            }
        }
    }
}
