package ru.safeai.gateway.knowledge.ingestion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;

import java.time.Instant;
import java.util.Map;

@Service
public class KnowledgeIngestionFailureService {

    private final KnowledgeIngestionQueueRepository queue;
    private final AuditEventService audit;

    public KnowledgeIngestionFailureService(
            KnowledgeIngestionQueueRepository queue,
            AuditEventService audit
    ) {
        this.queue = queue;
        this.audit = audit;
    }

    @Transactional
    public void recordFailure(
            KnowledgeIngestionClaim claim,
            String code,
            String message,
            boolean retryable,
            int maxAttempts,
            Instant now,
            Instant nextAttemptAt
    ) {
        queue.fail(
                claim,
                code,
                message,
                retryable,
                maxAttempts,
                now,
                nextAttemptAt
        );
        boolean terminal = !retryable || claim.attempt() >= maxAttempts;
        if (terminal) {
            audit.recordSystem(
                    claim.organizationId(),
                    AuditEventType.KNOWLEDGE_INGESTION_FAILED,
                    Map.of(
                            "knowledgeBaseId",
                            claim.knowledgeBaseId().toString(),
                            "documentId", claim.documentId().toString(),
                            "documentVersionId",
                            claim.documentVersionId().toString(),
                            "ingestionJobId", claim.jobId().toString(),
                            "attempt", claim.attempt(),
                            "errorCode", code
                    )
            );
        }
    }
}
