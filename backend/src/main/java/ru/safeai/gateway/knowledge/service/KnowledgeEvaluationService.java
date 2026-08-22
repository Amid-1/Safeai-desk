package ru.safeai.gateway.knowledge.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationCaseRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationCaseResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationResponse;
import ru.safeai.gateway.knowledge.evaluation.KnowledgeRetrievalMetrics;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalExecution;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalHit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeEvaluationService {

    private static final int MAX_DATASET_NAME_CODE_POINTS = 255;

    private final KnowledgeRetrievalService retrievalService;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final AuditEventService audit;
    private final Clock clock;
    private final TransactionTemplate persistenceTransaction;

    public KnowledgeEvaluationService(
            KnowledgeRetrievalService retrievalService,
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            AuditEventService audit,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.retrievalService = retrievalService;
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.audit = audit;
        this.clock = clock;
        this.persistenceTransaction =
                new TransactionTemplate(
                        transactionManager
                );
    }

    public KnowledgeEvaluationResponse evaluate(
            UUID knowledgeBaseId,
            KnowledgeEvaluationRequest request,
            SafeAiUserPrincipal user
    ) {
        if (!KnowledgeAccessService.isAdmin(
                user
        )) {
            throw new ForbiddenOperationException(
                    "Knowledge evaluation доступен только tenant ADMIN."
            );
        }

        if (request == null) {
            throw new BadRequestException(
                    "Evaluation request не должен быть пустым."
            );
        }

        String datasetName =
                normalizeDatasetName(
                        request.datasetName()
                );

        /*
         * Validate the whole dataset before the first provider call so a
         * malformed/cross-tenant dataset cannot create a partially executed
         * evaluation run.
         */
        for (KnowledgeEvaluationCaseRequest evaluationCase : request.cases()) {
            validateExpectedVersions(
                    knowledgeBaseId,
                    evaluationCase.expectedDocumentVersionIds(),
                    user.getOrganizationId()
            );
        }

        List<CaseResult> results =
                new ArrayList<>(
                        request.cases().size()
                );

        for (
                int index = 0;
                index < request.cases().size();
                index++
        ) {
            KnowledgeEvaluationCaseRequest evaluationCase =
                    request.cases()
                            .get(index);

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
                            retrieval.hits()
                                    .stream()
                                    .map(
                                            KnowledgeRetrievalHit
                                                    ::documentVersionId
                                    )
                                    .toList(),
                            evaluationCase
                                    .expectedDocumentVersionIds()
                    );

            results.add(
                    new CaseResult(
                            index + 1,
                            retrieval.retrievalRunId(),
                            evaluationCase
                                    .expectedDocumentVersionIds()
                                    .stream()
                                    .sorted()
                                    .map(UUID::toString)
                                    .collect(
                                            java.util.stream.Collectors
                                                    .joining(",")
                                    ),
                            metrics
                    )
            );
        }

        double meanRecall =
                average(
                        results,
                        Metric.RECALL
                );

        double meanMrr =
                average(
                        results,
                        Metric.MRR
                );

        double meanNdcg =
                average(
                        results,
                        Metric.NDCG
                );

        UUID runId =
                UUID.randomUUID();

        Instant now =
                clock.instant();

        persistenceTransaction.executeWithoutResult(
                status -> {
                    jdbc.update(
                            """
                            insert into knowledge_evaluation_runs (
                                id,
                                organization_id,
                                knowledge_base_id,
                                user_id,
                                dataset_name,
                                case_count,
                                mean_recall,
                                mean_reciprocal_rank,
                                mean_ndcg,
                                created_at
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            runId,
                            user.getOrganizationId(),
                            knowledgeBaseId,
                            user.getId(),
                            datasetName,
                            results.size(),
                            meanRecall,
                            meanMrr,
                            meanNdcg,
                            Timestamp.from(now)
                    );

                    jdbc.batchUpdate(
                            """
                            insert into knowledge_evaluation_cases (
                                id,
                                evaluation_run_id,
                                retrieval_run_id,
                                organization_id,
                                knowledge_base_id,
                                user_id,
                                ordinal,
                                expected_version_ids,
                                recall,
                                reciprocal_rank,
                                ndcg
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            results,
                            100,
                            (statement, result) -> {
                                statement.setObject(
                                        1,
                                        UUID.randomUUID()
                                );
                                statement.setObject(
                                        2,
                                        runId
                                );
                                statement.setObject(
                                        3,
                                        result.retrievalRunId()
                                );
                                statement.setObject(
                                        4,
                                        user.getOrganizationId()
                                );
                                statement.setObject(
                                        5,
                                        knowledgeBaseId
                                );
                                statement.setObject(
                                        6,
                                        user.getId()
                                );
                                statement.setInt(
                                        7,
                                        result.ordinal()
                                );
                                statement.setString(
                                        8,
                                        result.expectedVersionIds()
                                );
                                statement.setDouble(
                                        9,
                                        result.metrics().recall()
                                );
                                statement.setDouble(
                                        10,
                                        result.metrics()
                                                .reciprocalRank()
                                );
                                statement.setDouble(
                                        11,
                                        result.metrics().ndcg()
                                );
                            }
                    );

                    audit.record(
                            user,
                            user.getOrganizationId(),
                            AuditEventType.KNOWLEDGE_EVALUATION_COMPLETED,
                            Map.of(
                                    "knowledgeBaseId",
                                    knowledgeBaseId.toString(),
                                    "evaluationRunId",
                                    runId.toString(),
                                    "datasetName",
                                    datasetName,
                                    "caseCount",
                                    results.size(),
                                    "topK",
                                    request.topK()
                            )
                    );
                }
        );

        return new KnowledgeEvaluationResponse(
                runId,
                knowledgeBaseId,
                datasetName,
                request.topK(),
                meanRecall,
                meanMrr,
                meanNdcg,
                now,
                results.stream()
                        .map(
                                result ->
                                        new KnowledgeEvaluationCaseResponse(
                                                result.ordinal(),
                                                result.retrievalRunId(),
                                                result.metrics().recall(),
                                                result.metrics()
                                                        .reciprocalRank(),
                                                result.metrics().ndcg(),
                                                result.metrics()
                                                        .firstRelevantRank()
                                        )
                        )
                        .toList()
        );
    }

    private void validateExpectedVersions(
            UUID knowledgeBaseId,
            Set<UUID> expected,
            UUID organizationId
    ) {
        if (expected == null
                || expected.isEmpty()) {
            throw new BadRequestException(
                    "expectedDocumentVersionIds не должен быть пустым."
            );
        }

        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue(
                                "organizationId",
                                organizationId
                        )
                        .addValue(
                                "knowledgeBaseId",
                                knowledgeBaseId
                        )
                        .addValue(
                                "versionIds",
                                expected
                        );

        Long count =
                namedJdbc.queryForObject(
                        """
                        select count(*)
                        from knowledge_document_versions version
                        where version.organization_id = :organizationId
                          and version.knowledge_base_id = :knowledgeBaseId
                          and version.id in (:versionIds)
                        """,
                        parameters,
                        Long.class
                );

        if (count == null
                || count != expected.size()) {
            throw new BadRequestException(
                    "Evaluation dataset содержит версии документов, "
                            + "не принадлежащие указанной базе знаний."
            );
        }
    }

    private static String normalizeDatasetName(
            String value
    ) {
        if (value == null) {
            throw new BadRequestException(
                    "datasetName должен быть указан."
            );
        }

        String normalized =
                value.strip();

        if (normalized.isEmpty()
                || normalized.codePointCount(
                0,
                normalized.length()
        ) > MAX_DATASET_NAME_CODE_POINTS) {
            throw new BadRequestException(
                    "Некорректное название evaluation dataset."
            );
        }

        boolean unsafeControl =
                normalized.codePoints()
                        .anyMatch(
                                Character::isISOControl
                        );

        if (unsafeControl) {
            throw new BadRequestException(
                    "Название evaluation dataset содержит управляющие символы."
            );
        }

        return normalized;
    }

    private static double average(
            List<CaseResult> results,
            Metric metric
    ) {
        return results.stream()
                .mapToDouble(
                        result -> switch (metric) {
                            case RECALL ->
                                    result.metrics()
                                            .recall();

                            case MRR ->
                                    result.metrics()
                                            .reciprocalRank();

                            case NDCG ->
                                    result.metrics()
                                            .ndcg();
                        }
                )
                .average()
                .orElse(0.0);
    }

    private enum Metric {
        RECALL,
        MRR,
        NDCG
    }

    private record CaseResult(
            int ordinal,
            UUID retrievalRunId,
            String expectedVersionIds,
            KnowledgeRetrievalMetrics.Result metrics
    ) {
    }
}
