package ru.safeai.gateway.knowledge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Parent coordinator. Never deletes objects directly: each V78 child operation
 * runs through the existing verified version-aware document erasure executor.
 * Disabled by default and fenced against multi-instance processing.
 */
@Component
@ConditionalOnProperty(prefix = "safeai.knowledge.base-deletion",
        name = "executor-enabled", havingValue = "true")
public class KnowledgeBaseDeletionExecutor {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseDeletionExecutor.class);
    private static final int MAX_FAILURES = 5;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean childExecutorEnabled;

    public KnowledgeBaseDeletionExecutor(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            @Value("${safeai.knowledge.deletion.executor-enabled:false}") boolean childExecutorEnabled) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.childExecutorEnabled = childExecutorEnabled;
    }

    private record Claim(UUID id, UUID baseId, UUID orgId, UUID token, int attempts) { }
    private static final class LeaseLost extends RuntimeException { }

    @Scheduled(fixedDelayString = "${safeai.knowledge.base-deletion.poll-delay:PT20S}")
    public void poll() {
        // No parent coordination while the actual child purge mechanism is disabled.
        if (!childExecutorEnabled) return;
        for (int i = 0; i < 5; i++) {
            Claim claim = claim();
            if (claim == null) return;
            try {
                transactions.executeWithoutResult(tx -> finishOrReschedule(claim));
            } catch (LeaseLost lost) {
                log.warn("Base deletion lease expired or fenced: {}", claim.id());
            } catch (Exception ex) {
                fail(claim, ex);
            }
        }
    }

    private Claim claim() {
        UUID token = UUID.randomUUID();
        List<Claim> claimed = jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM public.knowledge_base_deletions_v82
                    WHERE state='DELETING' AND next_attempt_at<=clock_timestamp()
                      AND (lease_until IS NULL OR lease_until<clock_timestamp())
                    ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE public.knowledge_base_deletions_v82 op
                   SET lease_token=?,lease_until=clock_timestamp()+interval '2 minutes',
                       updated_at=clock_timestamp()
                  FROM candidate WHERE op.id=candidate.id
                RETURNING op.id,op.knowledge_base_id,op.organization_id,op.lease_token,op.attempts
                """, (rs, n) -> new Claim(
                rs.getObject("id", UUID.class), rs.getObject("knowledge_base_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("lease_token", UUID.class), rs.getInt("attempts")), token);
        return claimed.isEmpty() ? null : claimed.getFirst();
    }

    private void finishOrReschedule(Claim claim) {
        // Serialize parent finalization with base mutation, then validate live lease.
        List<String> states = jdbc.queryForList("""
                SELECT lifecycle_state FROM public.knowledge_bases
                WHERE id=? AND organization_id=? FOR UPDATE
                """, String.class, claim.baseId(), claim.orgId());
        if (states.size() != 1 || !"DELETING".equals(states.getFirst())) {
            throw new IllegalStateException("BASE_NOT_DELETING");
        }
        requireLease(claim);

        List<String> childStates = jdbc.queryForList("""
                SELECT state FROM public.knowledge_document_deletions_v78
                WHERE organization_id=? AND knowledge_base_id=?
                """, String.class, claim.orgId(), claim.baseId());
        if (childStates.contains("BLOCKED_BY_RETENTION")) {
            transition(claim, "BLOCKED_BY_RETENTION", "CHILD_BLOCKED_BY_RETENTION");
            return;
        }
        if (childStates.contains("NEEDS_REVIEW")) {
            transition(claim, "NEEDS_REVIEW", "CHILD_NEEDS_REVIEW");
            return;
        }
        if (childStates.contains("DELETING")) {
            reschedule(claim);
            return;
        }

        boolean unsettledIntent = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.knowledge_storage_upload_intents
                    WHERE knowledge_base_id=? AND organization_id=?
                      AND state NOT IN ('LINKED','CLEANED')
                )
                """, Boolean.class, claim.baseId(), claim.orgId()));
        if (unsettledIntent) {
            // Never silently purge an ambiguous/orphan storage upload.
            transition(claim, "NEEDS_REVIEW", "UNSETTLED_UPLOAD_INTENT");
            return;
        }
        boolean remaining = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.knowledge_documents WHERE knowledge_base_id=?)
                    OR EXISTS(SELECT 1 FROM public.knowledge_document_versions WHERE knowledge_base_id=?)
                    OR EXISTS(SELECT 1 FROM public.knowledge_document_chunks WHERE knowledge_base_id=?)
                    OR EXISTS(SELECT 1 FROM public.knowledge_ingestion_jobs WHERE knowledge_base_id=?)
                    OR EXISTS(SELECT 1 FROM public.knowledge_document_erasure_objects_v81 inventory
                        JOIN public.knowledge_document_deletions_v78 deletion
                        ON deletion.id=inventory.operation_id WHERE deletion.knowledge_base_id=?)
                """, Boolean.class, claim.baseId(), claim.baseId(), claim.baseId(),
                claim.baseId(), claim.baseId()));
        if (remaining) {
            transition(claim, "NEEDS_REVIEW", "INCOMPLETE_CHILD_CLEANUP");
            return;
        }

        // Transaction-local fencing: V82 trigger checks the same live DB lease.
        jdbc.queryForObject("SELECT set_config('safeai.knowledge.base_erasure_operation', ?, true)",
                String.class, claim.id().toString());
        jdbc.queryForObject("SELECT set_config('safeai.knowledge.base_erasure_token', ?, true)",
                String.class, claim.token().toString());
        if (jdbc.update("""
                DELETE FROM public.knowledge_bases
                WHERE id=? AND organization_id=? AND lifecycle_state='DELETING' AND NOT enabled
                """, claim.baseId(), claim.orgId()) != 1) {
            throw new IllegalStateException("BASE_DELETION_NOT_APPLIED");
        }
        transition(claim, "DELETED", null);
    }

    private void requireLease(Claim claim) {
        boolean owned = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.knowledge_base_deletions_v82
                WHERE id=? AND knowledge_base_id=? AND organization_id=?
                  AND lease_token=? AND lease_until>clock_timestamp()
                  AND state='DELETING')
                """, Boolean.class, claim.id(), claim.baseId(), claim.orgId(), claim.token()));
        if (!owned) throw new LeaseLost();
    }

    private void transition(Claim claim, String state, String reason) {
        int count = jdbc.update("""
                UPDATE public.knowledge_base_deletions_v82
                SET state=?,last_error_code=?,lease_token=NULL,lease_until=NULL,
                    completed_at=CASE WHEN ?='DELETED' THEN clock_timestamp() END,
                    updated_at=clock_timestamp()
                WHERE id=? AND organization_id=? AND knowledge_base_id=?
                  AND lease_token=? AND state='DELETING' AND lease_until>clock_timestamp()
                """, state, reason, state, claim.id(), claim.orgId(), claim.baseId(), claim.token());
        if (count != 1) throw new LeaseLost();
    }

    private void reschedule(Claim claim) {
        if (jdbc.update("""
                UPDATE public.knowledge_base_deletions_v82
                SET next_attempt_at=clock_timestamp()+interval '20 seconds',
                    lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
                WHERE id=? AND lease_token=? AND state='DELETING' AND lease_until>clock_timestamp()
                """, claim.id(), claim.token()) != 1) throw new LeaseLost();
    }

    private void fail(Claim claim, Exception error) {
        String code = error.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        int nextAttempt = claim.attempts() + 1;
        String status = nextAttempt >= MAX_FAILURES ? "NEEDS_REVIEW" : "DELETING";
        int backoff = Math.min(900, 10 * (1 << Math.min(nextAttempt, 6)));
        int updated = jdbc.update("""
                UPDATE public.knowledge_base_deletions_v82
                SET state=?,attempts=attempts+1,last_error_code=?,
                    next_attempt_at=clock_timestamp()+(? * interval '1 second'),
                    lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
                WHERE id=? AND lease_token=? AND state='DELETING' AND lease_until>clock_timestamp()
                """, status, code.substring(0, Math.min(code.length(), 64)),
                backoff, claim.id(), claim.token());
        log.warn("KB erasure failed: opId={},status={},acknowledged={},reason={}",
                claim.id(), status, updated == 1, code);
    }
}
