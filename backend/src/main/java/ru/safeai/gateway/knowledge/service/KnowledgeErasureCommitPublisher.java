package ru.safeai.gateway.knowledge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.knowledge.config.KnowledgeErasureWorkerDatabaseConfig.WorkerConnections;
import ru.safeai.gateway.knowledge.storage.ErasureTombstoneJournal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional PostgreSQL outbox -> immutable S3 evidence journal.
 * Database commit and S3 PUT cannot share a transaction. The V89 trigger
 * atomically queues final receipts with DELETED. This publisher offers
 * at-least-once delivery with an idempotent S3 key/payload, fencing and retry.
 * Do not report journal completion to callers until DELIVERED is durable.
 */
@Component
@ConditionalOnProperty(prefix="safeai.knowledge.deletion", name="executor-enabled", havingValue="true")
public final class KnowledgeErasureCommitPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeErasureCommitPublisher.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ErasureTombstoneJournal journal;

    public KnowledgeErasureCommitPublisher(WorkerConnections worker, ErasureTombstoneJournal journal) {
        this.jdbc = Objects.requireNonNull(worker).jdbc();
        this.transactions = new TransactionTemplate(worker.transactionManager());
        this.journal = Objects.requireNonNull(journal);
    }

    record Receipt(String kind, UUID operationId, UUID orgId, UUID baseId, UUID documentId,
                   UUID requestedBy, long expectedVersion, UUID token, int attempts) { }

    @Scheduled(fixedDelayString="${safeai.knowledge.deletion.receipt-poll-delay:PT20S}")
    public void poll() {
        for (int i = 0; i < 10; i++) {
            Receipt receipt = transactions.execute(tx -> claim(null));
            if (receipt == null) return;
            deliver(receipt);
        }
    }

    /** Deterministic targeted delivery, with exactly the same SQL lease and
     * retry protocol as the background queue. Never bypasses authorisation. */
    public void pollOperation(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        Receipt receipt = transactions.execute(tx -> claim(operationId));
        if (receipt != null) deliver(receipt);
    }

    private void deliver(Receipt receipt) {
            try {
                if ("DOCUMENT".equals(receipt.kind())) {
                    journal.publishDocumentCommitted(receipt.operationId(), receipt.orgId(),
                            receipt.baseId(), receipt.documentId());
                } else if ("BASE".equals(receipt.kind())) {
                    journal.publishBaseCommitted(receipt.operationId(), receipt.orgId(),
                            receipt.baseId(), receipt.requestedBy(), receipt.expectedVersion());
                } else {
                    throw new IllegalStateException("Unsupported Knowledge receipt type");
                }
                transactions.executeWithoutResult(tx -> acknowledge(receipt));
            } catch (Exception failure) {
                // Never log content, object key, SQL payload, S3 response or credentials.
                LOG.warn("Knowledge receipt delivery retry. kind={} operationId={} failureClass={}",
                        receipt.kind(), receipt.operationId(), failure.getClass().getSimpleName());
                try {
                    transactions.executeWithoutResult(tx -> retry(receipt));
                } catch (Exception failedRetry) {
                    LOG.error("Knowledge receipt retry ledger failed closed. operationId={} failureClass={}",
                            receipt.operationId(), failedRetry.getClass().getSimpleName());
                }
            }
    }

    private Receipt claim(UUID operationId) {
        UUID token = UUID.randomUUID();
        List<Receipt> claims = jdbc.query("""
            WITH candidate AS (
              SELECT r.kind,r.operation_id FROM public.knowledge_erasure_commit_outbox_v89 r
              WHERE (r.kind='DOCUMENT' OR NOT EXISTS (
                  SELECT 1 FROM public.knowledge_erasure_commit_outbox_v89 child
                  WHERE child.kind='DOCUMENT' AND child.organization_id=r.organization_id
                    AND child.knowledge_base_id=r.knowledge_base_id
                    AND child.state<>'DELIVERED'))
                AND r.state IN ('PENDING','DELIVERING')
                AND r.next_attempt_at<=clock_timestamp()
                AND (r.lease_until IS NULL OR r.lease_until<clock_timestamp())
                AND (?::uuid IS NULL OR r.operation_id=?::uuid)
              ORDER BY r.next_attempt_at,r.kind,r.operation_id
              LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE public.knowledge_erasure_commit_outbox_v89 receipt
              SET state='DELIVERING',lease_token=?,lease_until=clock_timestamp()+interval '3 minutes',
                  attempts=receipt.attempts+1
              FROM candidate WHERE receipt.kind=candidate.kind
                AND receipt.operation_id=candidate.operation_id
            RETURNING receipt.kind,receipt.operation_id,receipt.organization_id,
                receipt.knowledge_base_id,receipt.document_id,receipt.requested_by_user_id,
                receipt.expected_version,receipt.lease_token,receipt.attempts
            """, this::map, operationId, operationId, token);
        return claims.isEmpty() ? null : claims.getFirst();
    }

    private Receipt map(ResultSet rs, int ignored) throws SQLException {
        return new Receipt(rs.getString("kind"), rs.getObject("operation_id",UUID.class),
                rs.getObject("organization_id",UUID.class), rs.getObject("knowledge_base_id",UUID.class),
                rs.getObject("document_id",UUID.class), rs.getObject("requested_by_user_id",UUID.class),
                rs.getLong("expected_version"),rs.getObject("lease_token",UUID.class),rs.getInt("attempts"));
    }

    private void acknowledge(Receipt r) {
        if (jdbc.update("""
            UPDATE public.knowledge_erasure_commit_outbox_v89
               SET state='DELIVERED',delivered_at=clock_timestamp(),
                   lease_token=NULL,lease_until=NULL,last_error_code=NULL
             WHERE kind=? AND operation_id=? AND state='DELIVERING'
               AND lease_token=? AND lease_until>clock_timestamp()
            """,r.kind(),r.operationId(),r.token()) != 1) {
            throw new IllegalStateException("Knowledge receipt lease lost after publish");
        }
    }

    private void retry(Receipt r) {
        // A transient outage must not silently discard proof. Retries are unbounded
        // but capped at 1h backoff; monitor outbox lag and attempts >= 10.
        int backoff = Math.min(3600, 10 * (1 << Math.min(8,r.attempts())));
        jdbc.update("""
            UPDATE public.knowledge_erasure_commit_outbox_v89
               SET state='PENDING',next_attempt_at=clock_timestamp()+(? * interval '1 second'),
                   lease_token=NULL,lease_until=NULL,last_error_code='DELIVERY_RETRY_REQUIRED'
             WHERE kind=? AND operation_id=? AND state='DELIVERING' AND lease_token=?
               AND lease_until>clock_timestamp()
            """,backoff,r.kind(),r.operationId(),r.token());
    }
}
