package ru.safeai.gateway.knowledge.service;

import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Disabled by default. Implements a restart-safe, fenced two-phase erasure:
 * (1) commit a durable inventory, (2) purge every object version outside any
 * DB transaction, (3) atomically remove online content and acknowledge DELETED.
 *
 * <p>V80/V57 provenance, V56 upload-intent evidence, earlier RAG responses and
 * backup snapshots are deliberately outside this erasure contract. Those
 * retention exceptions must be disclosed before enabling the public API.</p>
 *
 * <p>Requires Flyway migrations V78, V80 and V81; never run this executor
 * against a database whose migration history has not been validated.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "safeai.knowledge.deletion",
        name = "executor-enabled",
        havingValue = "true")
public class KnowledgeDocumentDeletionExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeDocumentDeletionExecutor.class);

    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_CLAIMS_PER_POLL = 5;
    private static final int MAX_RETRY_SECONDS = 900;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectStorage storage;

    public KnowledgeDocumentDeletionExecutor(JdbcTemplate jdbc,
                                             PlatformTransactionManager transactionManager,
                                             ObjectStorage storage) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.storage = storage;
    }

    private record Claim(UUID id, UUID organizationId, UUID knowledgeBaseId,
                         UUID documentId, UUID token, int attempts) { }

    private static final class LeaseLostException extends IllegalStateException {
        private LeaseLostException() {
            super("KNOWLEDGE_ERASURE_LEASE_LOST");
        }
    }

    @Scheduled(fixedDelayString = "${safeai.knowledge.deletion.poll-delay:PT20S}")
    public void poll() {
        for (int i = 0; i < MAX_CLAIMS_PER_POLL; i++) {
            Claim claim = claim();
            if (claim == null) {
                return;
            }

            try {
                // Inventory is committed before any irreversible storage I/O.
                boolean ready = Boolean.TRUE.equals(
                        transactions.execute(tx -> prepareInventory(claim)));
                if (!ready) {
                    continue;
                }

                List<String> keys = jdbc.queryForList("""
                        SELECT storage_key
                        FROM public.knowledge_document_erasure_objects_v81
                        WHERE operation_id = ?
                        ORDER BY storage_key
                        """, String.class, claim.id());

                for (String key : keys) {
                    requireRenewedLease(claim);
                    // Never perform network I/O inside a PostgreSQL transaction.
                    // This provider operation MUST verify all versions and markers.
                    storage.purgeAllVersionsVerified(key);
                }

                requireRenewedLease(claim);
                boolean completed = Boolean.TRUE.equals(
                        transactions.execute(tx -> eraseDatabase(claim)));
                if (!completed) {
                    throw new LeaseLostException();
                }
            } catch (LeaseLostException lost) {
                // An expired lease may already belong to a different worker.
                // Never reset its status/token or acknowledge an old claim.
                log.warn("Knowledge deletion lease lost: operationId={}", claim.id());
            } catch (Exception error) {
                fail(claim, error);
            }
        }
    }

    private Claim claim() {
        UUID token = UUID.randomUUID();
        List<Claim> results = jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM public.knowledge_document_deletions_v78
                    WHERE state = 'DELETING'
                      AND next_attempt_at <= clock_timestamp()
                      AND (lease_until IS NULL OR lease_until < clock_timestamp())
                    ORDER BY next_attempt_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE public.knowledge_document_deletions_v78 d
                SET lease_token = ?,
                    lease_until = clock_timestamp() + interval '5 minutes',
                    attempts = d.attempts + 1,
                    updated_at = clock_timestamp()
                FROM candidate
                WHERE d.id = candidate.id
                RETURNING d.id, d.organization_id, d.knowledge_base_id,
                          d.document_id, d.lease_token, d.attempts
                """, (rs, rowNum) -> new Claim(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("knowledge_base_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("lease_token", UUID.class),
                rs.getInt("attempts")), token);

        return results.isEmpty() ? null : results.getFirst();
    }

    private void requireRenewedLease(Claim claim) {
        int updated = jdbc.update("""
                UPDATE public.knowledge_document_deletions_v78
                SET lease_until = clock_timestamp() + interval '5 minutes',
                    updated_at = clock_timestamp()
                WHERE id = ? AND lease_token = ? AND state = 'DELETING'
                  AND lease_until > clock_timestamp()
                """, claim.id(), claim.token());
        if (updated != 1) {
            throw new LeaseLostException();
        }
    }

    private boolean prepareInventory(Claim claim) {
        assertDeletionLeaseUnderDocumentLock(claim);

        // In-flight uploads can still have unknown physical state. Never
        // presume such an object absent or continue with a partial inventory.
        boolean uncertainIntent = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.knowledge_storage_upload_intents
                    WHERE organization_id = ? AND knowledge_base_id = ?
                      AND document_id = ?
                      AND state NOT IN ('LINKED', 'CLEANED')
                )
                """, Boolean.class,
                claim.organizationId(), claim.knowledgeBaseId(), claim.documentId()));

        if (uncertainIntent) {
            requireTerminalTransition(claim, "NEEDS_REVIEW", "UNCERTAIN_UPLOAD_INTENT");
            return false;
        }

        // Even CLEANED intents must be checked: an earlier ordinary S3 DELETE
        // may have left physical historical versions in a versioned bucket.
        jdbc.update("""
                INSERT INTO public.knowledge_document_erasure_objects_v81
                    (operation_id, storage_key)
                SELECT ?, storage_key
                  FROM public.knowledge_document_versions
                 WHERE organization_id = ? AND knowledge_base_id = ? AND document_id = ?
                UNION
                SELECT ?, storage_key
                  FROM public.knowledge_storage_upload_intents
                 WHERE organization_id = ? AND knowledge_base_id = ? AND document_id = ?
                   AND state IN ('LINKED', 'CLEANED')
                ON CONFLICT DO NOTHING
                """, claim.id(), claim.organizationId(), claim.knowledgeBaseId(),
                claim.documentId(), claim.id(), claim.organizationId(),
                claim.knowledgeBaseId(), claim.documentId());

        return true;
    }

    private boolean eraseDatabase(Claim claim) {
        assertDeletionLeaseUnderDocumentLock(claim);

        // Fail closed if a storage key was added since the committed inventory.
        boolean missingKey = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT storage_key
                      FROM public.knowledge_document_versions
                     WHERE organization_id = ? AND knowledge_base_id = ? AND document_id = ?
                    UNION
                    SELECT storage_key
                      FROM public.knowledge_storage_upload_intents
                     WHERE organization_id = ? AND knowledge_base_id = ? AND document_id = ?
                       AND state IN ('LINKED', 'CLEANED')
                    EXCEPT
                    SELECT storage_key
                      FROM public.knowledge_document_erasure_objects_v81
                     WHERE operation_id = ?
                )
                """, Boolean.class,
                claim.organizationId(), claim.knowledgeBaseId(), claim.documentId(),
                claim.organizationId(), claim.knowledgeBaseId(), claim.documentId(),
                claim.id()));
        if (missingKey) {
            throw new IllegalStateException("INCOMPLETE_ERASURE_INVENTORY");
        }

        // Session settings are transaction-local and authenticated again by
        // V81 against the active DB lease and the DELETING document.
        jdbc.queryForObject(
                "SELECT set_config('safeai.knowledge.erasure_operation', ?, true)",
                String.class, claim.id().toString());
        jdbc.queryForObject(
                "SELECT set_config('safeai.knowledge.erasure_token', ?, true)",
                String.class, claim.token().toString());

        int detached = jdbc.update("""
                UPDATE public.knowledge_documents
                   SET current_version_id = NULL,
                       searchable_version_id = NULL,
                       enabled = false
                 WHERE id = ? AND organization_id = ? AND knowledge_base_id = ?
                   AND lifecycle_state = 'DELETING'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        if (detached != 1) {
            throw new IllegalStateException("ERASURE_DOCUMENT_NOT_DELETING");
        }

        // Change the user-supplied name snapshot; V80 retains independent
        // immutable chunk identities so citations do not lose their FK.
        jdbc.update("""
                UPDATE public.knowledge_retrieval_hits h
                   SET document_name_snapshot = 'Удалённый документ'
                  FROM public.knowledge_chunk_identities_v80 ci
                 WHERE h.chunk_id = ci.id
                   AND ci.document_id = ? AND ci.organization_id = ?
                   AND ci.knowledge_base_id = ?
                   AND h.document_name_snapshot <> 'Удалённый документ'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());

        jdbc.update("""
                DELETE FROM public.knowledge_document_chunks
                 WHERE document_id = ? AND organization_id = ? AND knowledge_base_id = ?
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());

        // V57/V63 require real GC state transitions and retain generation
        // evidence. Clear chunks first to satisfy the cited-chunk GC guard.
        jdbc.update("""
                UPDATE public.knowledge_index_generations
                   SET state = 'RETIRED',
                       retired_at = greatest(clock_timestamp(), published_at)
                 WHERE document_id = ? AND organization_id = ?
                   AND knowledge_base_id = ? AND state = 'ACTIVE'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        jdbc.update("""
                UPDATE public.knowledge_index_generations
                   SET state = 'COLLECTING'
                 WHERE document_id = ? AND organization_id = ?
                   AND knowledge_base_id = ? AND state = 'RETIRED'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        jdbc.update("""
                UPDATE public.knowledge_index_generations
                   SET state = 'COLLECTED',
                       collected_at = greatest(clock_timestamp(), retired_at)
                 WHERE document_id = ? AND organization_id = ?
                   AND knowledge_base_id = ? AND state = 'COLLECTING'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());

        jdbc.update("""
                DELETE FROM public.knowledge_ingestion_jobs
                 WHERE document_id = ? AND organization_id = ? AND knowledge_base_id = ?
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        jdbc.update("""
                DELETE FROM public.knowledge_document_versions
                 WHERE document_id = ? AND organization_id = ? AND knowledge_base_id = ?
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());

        int removed = jdbc.update("""
                DELETE FROM public.knowledge_documents
                 WHERE id = ? AND organization_id = ? AND knowledge_base_id = ?
                   AND lifecycle_state = 'DELETING'
                """, claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        if (removed != 1) {
            throw new IllegalStateException("ERASURE_DOCUMENT_DELETE_NOT_APPLIED");
        }

        boolean remaining = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.knowledge_documents WHERE id = ?)
                    OR EXISTS (SELECT 1 FROM public.knowledge_document_versions WHERE document_id = ?)
                    OR EXISTS (SELECT 1 FROM public.knowledge_ingestion_jobs WHERE document_id = ?)
                    OR EXISTS (SELECT 1 FROM public.knowledge_document_chunks WHERE document_id = ?)
                """, Boolean.class, claim.documentId(), claim.documentId(),
                claim.documentId(), claim.documentId()));
        if (remaining) {
            throw new IllegalStateException("INCOMPLETE_DB_ERASURE");
        }

        // V81 independently rejects DELETED while inventory/content remains.
        jdbc.update("""
                DELETE FROM public.knowledge_document_erasure_objects_v81
                 WHERE operation_id = ?
                """, claim.id());
        requireTerminalTransition(claim, "DELETED", null);
        return true;
    }

    /**
     * Throws instead of returning a flag that callers could silently ignore.
     * Must be called inside the corresponding TransactionTemplate callback.
     */
    private void assertDeletionLeaseUnderDocumentLock(Claim claim) {
        List<String> states = jdbc.queryForList("""
                SELECT lifecycle_state
                  FROM public.knowledge_documents
                 WHERE id = ? AND organization_id = ? AND knowledge_base_id = ?
                 FOR UPDATE
                """, String.class,
                claim.documentId(), claim.organizationId(), claim.knowledgeBaseId());
        if (states.size() != 1 || !"DELETING".equals(states.getFirst())) {
            throw new IllegalStateException("ERASURE_DOCUMENT_NOT_DELETING");
        }

        boolean leaseOwned = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.knowledge_document_deletions_v78
                     WHERE id = ? AND organization_id = ? AND knowledge_base_id = ?
                       AND document_id = ? AND lease_token = ?
                       AND state = 'DELETING' AND lease_until > clock_timestamp()
                )
                """, Boolean.class, claim.id(), claim.organizationId(),
                claim.knowledgeBaseId(), claim.documentId(), claim.token()));
        if (!leaseOwned) {
            throw new LeaseLostException();
        }
    }

    /**
     * Persist a terminal status only while this worker owns the current lease.
     * Losing the lease is exceptional, never a silently ignored boolean result.
     */
    private void requireTerminalTransition(Claim claim, String state, String errorCode) {
        int updated = jdbc.update("""
                UPDATE public.knowledge_document_deletions_v78
                   SET state = ?, last_error_code = ?,
                       lease_token = NULL, lease_until = NULL,
                       completed_at = CASE WHEN ? = 'DELETED'
                                           THEN clock_timestamp() END,
                       updated_at = clock_timestamp()
                 WHERE id = ? AND lease_token = ? AND state = 'DELETING'
                   AND lease_until > clock_timestamp()
                """, state, errorCode, state, claim.id(), claim.token());
        if (updated != 1) {
            throw new LeaseLostException();
        }
    }

    private void fail(Claim claim, Exception error) {
        String code = error.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        boolean blockedByRetention = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ErrorResponseException response
                    && response.errorResponse() != null) {
                String providerCode = response.errorResponse().code();
                if ("ObjectLocked".equals(providerCode)
                        || "InvalidObjectState".equals(providerCode)) {
                    blockedByRetention = true;
                }
            }
        }

        String newState = blockedByRetention ? "BLOCKED_BY_RETENTION"
                : claim.attempts() >= MAX_ATTEMPTS ? "NEEDS_REVIEW" : "DELETING";
        int retrySeconds = Math.min(MAX_RETRY_SECONDS,
                10 * (1 << Math.min(6, claim.attempts())));

        int updated = jdbc.update("""
                UPDATE public.knowledge_document_deletions_v78
                   SET state = ?, last_error_code = ?,
                       next_attempt_at = clock_timestamp() + (? * interval '1 second'),
                       lease_token = NULL, lease_until = NULL,
                       updated_at = clock_timestamp()
                 WHERE id = ? AND lease_token = ? AND state = 'DELETING'
                   AND lease_until > clock_timestamp()
                """, newState, code.substring(0, Math.min(64, code.length())),
                retrySeconds, claim.id(), claim.token());

        if (updated == 0) {
            log.warn("Knowledge erasure failure on an expired/fenced claim: operationId={} cause={}",
                    claim.id(), code);
        } else {
            log.warn("Knowledge erasure requires retry/review: operationId={} state={} cause={}",
                    claim.id(), newState, code);
        }
    }
}
