package ru.safeai.gateway.chat.service;

import org.springframework.beans.factory.annotation.Autowired;
import ru.safeai.gateway.chat.config.ChatErasureWorkerDatabaseConfig.WorkerConnections;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable, bounded and resumable content erasure. No provider calls; no FK or
 * immutable-evidence triggers are disabled. Sessions and accounting remain.
 */
@Service
@ConditionalOnProperty(name = {
    "safeai.chat.erasure.executor-enabled", "safeai.chat.erasure.retention-approved"
}, havingValue = "true")
public class ChatErasureWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ChatErasureWorker.class);
    private static final String ERASED_MESSAGE = "[СОДЕРЖИМОЕ УДАЛЕНО]";
    private static final String ERASED_QUERY = "[REDACTED:SHA256_ONLY]";
    private static final int BATCH_SIZE = ChatErasureLimits.BATCH_SIZE;
    // Hard limit for ONE atomic PostgreSQL transaction. Exceeding it rolls
    // back ALL redactions and requires operator review; no half-erased chats.
    private static final int MAX_BATCHES_PER_CLAIM = ChatErasureLimits.MAX_BATCHES;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private static final String WORKER_ROLE = "safeai_chat_erasure_worker";

    @Autowired
    public ChatErasureWorker(WorkerConnections connections) {
        this(connections.jdbc(), connections.transactionManager());
    }

    // Package-private test seam. The production constructor always receives
    // the isolated connections, never Spring Boot's main JDBC/transaction manager.
    ChatErasureWorker(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        String role = this.jdbc.queryForObject("SELECT session_user", String.class);
        if (!WORKER_ROLE.equals(role)) {
            throw new IllegalStateException("Erasure worker must connect as dedicated DB login");
        }
    }

    private record Claim(UUID id, UUID chatId, UUID organizationId, UUID token) { }
    private static final class LeaseLost extends RuntimeException { }
    private static final class AtomicLimitExceeded extends RuntimeException { }
    private static final class BatchStalled extends RuntimeException { }

    @Scheduled(fixedDelayString = "${safeai.chat.erasure.poll-delay:PT20S}")
    public void poll() {
        for (int i = 0; i < 4; i++) {
            Claim claim = transactions.execute(status -> claim());
            if (claim == null) return;
            runClaim(claim);
        }
    }

    /**
     * One chat is erased in ONE transaction. A retention hold arriving while
     * processing blocks on the locked session row and cannot be admitted once
     * the transaction reaches ERASED. If a hold wins the lock first, the worker
     * sees it before redacting anything. No partially committed redactions.
     */
    private void runClaim(Claim claim) {
        try {
            transactions.executeWithoutResult(status -> {
                for (int i = 0; i < MAX_BATCHES_PER_CLAIM; i++) {
                    if (processBatch(claim)) return;
                }
                throw new AtomicLimitExceeded();
            });
        } catch (LeaseLost ignored) {
            LOG.info("Erasure lease changed. operationId={}", claim.id());
        } catch (Exception exception) {
            // No SQL, exception.message, prompts or PII in logs.
            String safeCode = safeFailureCode(exception);
            LOG.warn("Erasure requires review. operationId={} failureClass={} errorCode={}",
                claim.id(), exception.getClass().getSimpleName(), safeCode);
            // An exception rolls back every redaction in this claim. Only then
            // may the operation be marked for review, if the lease is still live.
            // Never store exception messages, SQL, or user content in the ledger.
            try {
                transactions.executeWithoutResult(status -> markForReview(claim, safeCode));
            } catch (LeaseLost ignored) {
                LOG.info("Erasure review update lost lease. operationId={}", claim.id());
            } catch (Exception reviewFailure) {
                LOG.error("Erasure review update failed closed. operationId={} failureClass={}",
                    claim.id(), reviewFailure.getClass().getSimpleName());
            }
        }
    }

    private Claim claim() {
        UUID token = UUID.randomUUID();
        List<Claim> claims = jdbc.query("""
                WITH candidate AS (
                   SELECT id FROM public.chat_erasure_operations_v86
                   WHERE state='ERASING' AND next_attempt_at<=clock_timestamp()
                     AND (lease_until IS NULL OR lease_until<clock_timestamp())
                   ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE public.chat_erasure_operations_v86 op
                   SET lease_token=?,lease_until=clock_timestamp()+interval '2 minutes',
                       attempts=attempts+1,updated_at=clock_timestamp()
                  FROM candidate WHERE op.id=candidate.id
                RETURNING op.id,op.chat_id,op.organization_id,op.lease_token
                """, this::mapClaim, token);
        return claims.isEmpty() ? null : claims.getFirst();
    }

    private Claim mapClaim(ResultSet rs, int index) throws SQLException {
        return new Claim(rs.getObject("id", UUID.class), rs.getObject("chat_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getObject("lease_token", UUID.class));
    }

    private boolean processBatch(Claim claim) {
        requireLiveLease(claim);
        bindLeaseToTransaction(claim);
        boolean held = requiredBoolean(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.chat_retention_holds_v86
                              WHERE chat_id=? AND organization_id=? AND released_at IS NULL)
                """, Boolean.class, claim.chatId(), claim.organizationId()),
                "CHAT_ERASURE_RETENTION_CHECK_UNKNOWN");
        if (held) {
            transition(claim, "BLOCKED_BY_RETENTION", "LEGAL_HOLD");
            return true;
        }
        boolean active = requiredBoolean(jdbc.queryForObject(
                "SELECT public.chat_has_active_execution_v86(?)", Boolean.class, claim.chatId()),
                "CHAT_ERASURE_EXECUTION_CHECK_UNKNOWN");
        if (active) {
            transition(claim, "NEEDS_REVIEW", "ACTIVE_AI_OPERATION");
            return true;
        }

        // Both identity and live lease are rechecked by DB triggers.

        int messages = jdbc.update("""
                WITH victims AS (
                    SELECT id FROM public.chat_messages
                    WHERE session_id=? AND content IS DISTINCT FROM ?
                    ORDER BY created_at,id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE public.chat_messages m SET content=?
                FROM victims v WHERE m.id=v.id
                """, claim.chatId(), ERASED_MESSAGE, BATCH_SIZE, ERASED_MESSAGE);
        int retrievals = jdbc.update("""
                WITH victims AS (
                    SELECT r.id FROM public.knowledge_retrieval_runs r
                    JOIN public.chat_turns t ON t.id=r.chat_turn_id
                    WHERE t.session_id=? AND r.query_text IS DISTINCT FROM ?
                    ORDER BY r.id LIMIT ? FOR UPDATE OF r SKIP LOCKED
                )
                UPDATE public.knowledge_retrieval_runs r SET query_text=?
                FROM victims v WHERE r.id=v.id
                """, claim.chatId(), ERASED_QUERY, BATCH_SIZE, ERASED_QUERY);

        // The limit is PER ROW KIND. An unexpected JDBC count cannot be used
        // to certify erasure; the enclosing transaction must roll back.
        validateBatchCount(messages);
        validateBatchCount(retrievals);

        // Always verify the remainder: exactly 10,000 rows must complete on
        // the 100th batch, not be mislabelled as exceeding the atomic limit.

        // Never infer completion from a zero-row SKIP LOCKED batch alone.
        // A locked unredacted row must not produce a false completed state.
        boolean remainder = requiredBoolean(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.chat_messages
                              WHERE session_id=? AND content IS DISTINCT FROM ?)
                    OR EXISTS(SELECT 1 FROM public.knowledge_retrieval_runs r
                              JOIN public.chat_turns t ON t.id=r.chat_turn_id
                              WHERE t.session_id=? AND r.query_text IS DISTINCT FROM ?)
                """, Boolean.class, claim.chatId(), ERASED_MESSAGE, claim.chatId(), ERASED_QUERY),
                "CHAT_ERASURE_REMAINDER_UNKNOWN");
        if (remainder) {
            if (messages == 0 && retrievals == 0) {
                // SKIP LOCKED may have skipped outstanding rows. Never commit
                // a partial redaction or spin through 100 empty batches.
                throw new BatchStalled();
            }
            return false;
        }

        if (jdbc.update("""
                UPDATE public.chat_sessions
                   SET title=?,title_source='USER',erasure_state='ERASED',
                       erased_at=clock_timestamp(),
                       updated_at=GREATEST(updated_at,clock_timestamp()),version=version+1
                 WHERE id=? AND organization_id=? AND erasure_state='ERASING'
                """, ERASED_MESSAGE, claim.chatId(), claim.organizationId()) != 1) {
            throw new IllegalStateException("CHAT_ERASURE_SESSION_FENCED");
        }
        if (jdbc.update("""
                UPDATE public.chat_erasure_operations_v86
                   SET state='ERASED',completed_at=clock_timestamp(),
                       lease_token=NULL,lease_until=NULL,
                       updated_at=clock_timestamp(),last_error_code=NULL
                 WHERE id=? AND lease_token=? AND state='ERASING'
                   AND lease_until>clock_timestamp()
                """, claim.id(), claim.token()) != 1) {
            throw new LeaseLost();
        }
        return true;
    }

    private static void validateBatchCount(int updated) {
        if (updated < 0 || updated > BATCH_SIZE) {
            throw new IllegalStateException("CHAT_ERASURE_INVALID_BATCH_COUNT");
        }
    }

    private static boolean requiredBoolean(Boolean value, String failureCode) {
        if (value == null) {
            // SQL boolean checks must never fail open when their result is unknown.
            throw new IllegalStateException(failureCode);
        }
        return value;
    }

    private void requireLiveLease(Claim claim) {
        List<UUID> ids = jdbc.query("""
                SELECT id FROM public.chat_erasure_operations_v86
                WHERE id=? AND chat_id=? AND organization_id=?
                  AND lease_token=? AND lease_until>clock_timestamp()
                  AND state='ERASING' FOR UPDATE
                """, (rs, index) -> rs.getObject(1, UUID.class),
                claim.id(), claim.chatId(), claim.organizationId(), claim.token());
        if (ids.size() != 1) throw new LeaseLost();
        String state = jdbc.queryForObject("""
                SELECT erasure_state FROM public.chat_sessions
                WHERE id=? AND organization_id=? FOR UPDATE
                """, String.class, claim.chatId(), claim.organizationId());
        if (!"ERASING".equals(state)) throw new LeaseLost();
    }

    private void bindLeaseToTransaction(Claim claim) {
        // These settings are LOCAL to this transaction, never to the connection pool.
        jdbc.queryForObject("SELECT set_config('safeai.chat.erasure_operation',?,true)",
                String.class, claim.id().toString());
        jdbc.queryForObject("SELECT set_config('safeai.chat.erasure_token',?,true)",
                String.class, claim.token().toString());
    }

    private void transition(Claim claim, String state, String errorCode) {
        if (jdbc.update("""
                UPDATE public.chat_erasure_operations_v86
                SET state=?,last_error_code=?,lease_token=NULL,lease_until=NULL,
                    updated_at=clock_timestamp()
                WHERE id=? AND lease_token=? AND state='ERASING'
                """, state, errorCode, claim.id(), claim.token()) != 1) {
            throw new LeaseLost();
        }
    }

    /** Only a SQLSTATE is retained, never SQL text, driver messages or user data. */
    static String safeFailureCode(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 12 && current != null; depth++) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.matches("[0-9A-Z]{5}")) {
                    return "SQLSTATE_" + state;
                }
            }
            current = current.getCause();
        }
        return "ERASURE_EXECUTION_FAILED";
    }

    private void markForReview(Claim claim, String failureCode) {
        // Only the owner of the still-live lease may mark this operation.
        bindLeaseToTransaction(claim);
        int updated = jdbc.update("""
                UPDATE public.chat_erasure_operations_v86
                   SET state='NEEDS_REVIEW',last_error_code=?,
                       lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
                 WHERE id=? AND lease_token=? AND state='ERASING'
                   AND lease_until>clock_timestamp()
                """, failureCode, claim.id(), claim.token());
        if (updated != 1) throw new LeaseLost();
    }
}
