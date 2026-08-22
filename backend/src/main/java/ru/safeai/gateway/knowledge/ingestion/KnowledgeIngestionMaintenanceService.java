package ru.safeai.gateway.knowledge.ingestion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeIngestionMaintenanceService {

    private final KnowledgeIngestionQueueRepository queue;
    private final AuditEventService audit;

    public KnowledgeIngestionMaintenanceService(
            KnowledgeIngestionQueueRepository queue,
            AuditEventService audit
    ) {
        this.queue = queue;
        this.audit = audit;
    }

    @Transactional
    public int failExhaustedExpired(
            Instant now,
            int maxAttempts
    ) {
        List<ExpiredKnowledgeIngestionJob> failed =
                queue.failExhaustedExpiredAndReturn(
                        now,
                        maxAttempts
                );

        for (
                ExpiredKnowledgeIngestionJob job
                : failed
        ) {
            audit.recordSystem(
                    job.organizationId(),
                    AuditEventType.KNOWLEDGE_INGESTION_FAILED,
                    Map.of(
                            "knowledgeBaseId",
                            job.knowledgeBaseId().toString(),
                            "documentId",
                            job.documentId().toString(),
                            "documentVersionId",
                            job.documentVersionId().toString(),
                            "ingestionJobId",
                            job.jobId().toString(),
                            "attempt",
                            job.attempt(),
                            "errorCode",
                            "LEASE_EXPIRED"
                    )
            );
        }

        return failed.size();
    }
}
