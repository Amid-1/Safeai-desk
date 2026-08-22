package ru.safeai.gateway.knowledge.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationCaseResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationResponse;
import ru.safeai.gateway.knowledge.evaluation.KnowledgeRetrievalMetrics;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalExecution;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeEvaluationService {

    private final KnowledgeRetrievalService retrievalService;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate persistenceTransaction;

    public KnowledgeEvaluationService(
            KnowledgeRetrievalService retrievalService,
            JdbcTemplate jdbc,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.retrievalService = retrievalService;
        this.jdbc = jdbc;
        this.clock = clock;
        this.persistenceTransaction = new TransactionTemplate(transactionManager);
    }

    public KnowledgeEvaluationResponse evaluate(
            UUID knowledgeBaseId,
            KnowledgeEvaluationRequest request,
            SafeAiUserPrincipal user
    ) {
        List<CaseResult> results = new ArrayList<>();
        for (int index = 0; index < request.cases().size(); index++) {
            var evaluationCase = request.cases().get(index);
            KnowledgeRetrievalExecution retrieval =
                    retrievalService.retrieveForChat(
                            knowledgeBaseId,
                            null,
                            evaluationCase.query(),
                            request.topK(),
                            user
                    );
            KnowledgeRetrievalMetrics.Result metrics =
                    KnowledgeRetrievalMetrics.evaluate(
                            retrieval.hits().stream()
                                    .map(hit -> hit.documentVersionId())
                                    .toList(),
                            evaluationCase.expectedDocumentVersionIds()
                    );
            results.add(new CaseResult(
                    index + 1,
                    retrieval.retrievalRunId(),
                    evaluationCase.expectedDocumentVersionIds().stream()
                            .sorted()
                            .map(UUID::toString)
                            .collect(java.util.stream.Collectors.joining(",")),
                    metrics
            ));
        }

        double meanRecall = average(results, Metric.RECALL);
        double meanMrr = average(results, Metric.MRR);
        double meanNdcg = average(results, Metric.NDCG);
        UUID runId = UUID.randomUUID();
        Instant now = clock.instant();
        persistenceTransaction.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into knowledge_evaluation_runs (
                        id, organization_id, knowledge_base_id, user_id,
                        dataset_name, case_count, mean_recall,
                        mean_reciprocal_rank, mean_ndcg, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    runId,
                    user.getOrganizationId(),
                    knowledgeBaseId,
                    user.getId(),
                    request.datasetName().strip(),
                    results.size(),
                    meanRecall,
                    meanMrr,
                    meanNdcg,
                    Timestamp.from(now)
            );
            jdbc.batchUpdate("""
                    insert into knowledge_evaluation_cases (
                        id, evaluation_run_id, retrieval_run_id, ordinal,
                        expected_version_ids, recall, reciprocal_rank, ndcg
                    ) values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    results,
                    100,
                    (statement, result) -> {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, runId);
                        statement.setObject(3, result.retrievalRunId());
                        statement.setInt(4, result.ordinal());
                        statement.setString(5, result.expectedVersionIds());
                        statement.setDouble(6, result.metrics().recall());
                        statement.setDouble(7, result.metrics().reciprocalRank());
                        statement.setDouble(8, result.metrics().ndcg());
                    }
            );
        });
        return new KnowledgeEvaluationResponse(
                runId,
                knowledgeBaseId,
                request.datasetName().strip(),
                request.topK(),
                meanRecall,
                meanMrr,
                meanNdcg,
                now,
                results.stream().map(result ->
                        new KnowledgeEvaluationCaseResponse(
                                result.ordinal(),
                                result.retrievalRunId(),
                                result.metrics().recall(),
                                result.metrics().reciprocalRank(),
                                result.metrics().ndcg(),
                                result.metrics().firstRelevantRank()
                        )
                ).toList()
        );
    }

    private static double average(List<CaseResult> results, Metric metric) {
        return results.stream().mapToDouble(result -> switch (metric) {
            case RECALL -> result.metrics().recall();
            case MRR -> result.metrics().reciprocalRank();
            case NDCG -> result.metrics().ndcg();
        }).average().orElse(0.0);
    }

    private enum Metric { RECALL, MRR, NDCG }

    private record CaseResult(
            int ordinal,
            UUID retrievalRunId,
            String expectedVersionIds,
            KnowledgeRetrievalMetrics.Result metrics
    ) {
    }
}
