package ru.safeai.gateway.knowledge.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.config.KnowledgeRetrievalProperties;
import ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalHitResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalResponse;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalExecution;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalHit;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeRetrievalService {

    private static final int DEFAULT_TOP_K = 8;
    private static final int EMBEDDING_DIMENSIONS = 384;

    private final KnowledgeAccessService accessService;
    private final KnowledgeRetrievalRepository retrievalRepository;
    private final KnowledgeEmbeddingProvider embeddingProvider;
    private final KnowledgeRetrievalProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final AuditEventService audit;
    private final Clock clock;
    private final TransactionTemplate persistenceTransaction;

    public KnowledgeRetrievalService(
            KnowledgeAccessService accessService,
            KnowledgeRetrievalRepository retrievalRepository,
            KnowledgeEmbeddingProvider embeddingProvider,
            KnowledgeRetrievalProperties properties,
            JdbcTemplate jdbcTemplate,
            AuditEventService audit,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.accessService = accessService;
        this.retrievalRepository = retrievalRepository;
        this.embeddingProvider = embeddingProvider;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.audit = audit;
        this.clock = clock;
        this.persistenceTransaction =
                new TransactionTemplate(
                        transactionManager
                );
    }

    public KnowledgeRetrievalResponse retrieve(
            UUID knowledgeBaseId,
            KnowledgeRetrievalRequest request,
            SafeAiUserPrincipal user
    ) {
        if (request == null) {
            throw new BadRequestException(
                    "Запрос retrieval не должен быть пустым."
            );
        }

        KnowledgeRetrievalExecution execution =
                execute(
                        knowledgeBaseId,
                        request.query(),
                        request.topK(),
                        null,
                        user
                );

        List<KnowledgeRetrievalHitResponse> responseHits =
                java.util.stream.IntStream
                        .range(
                                0,
                                execution.hits().size()
                        )
                        .mapToObj(
                                index ->
                                        KnowledgeRetrievalHitResponse.from(
                                                index + 1,
                                                execution.hits()
                                                        .get(index)
                                        )
                        )
                        .toList();

        return new KnowledgeRetrievalResponse(
                execution.retrievalRunId(),
                execution.knowledgeBaseId(),
                execution.querySha256(),
                execution.embeddingModel(),
                execution.completedAt(),
                responseHits
        );
    }

    public KnowledgeRetrievalExecution retrieveForChat(
            UUID knowledgeBaseId,
            UUID chatTurnId,
            String query,
            int topK,
            SafeAiUserPrincipal user
    ) {
        return execute(
                knowledgeBaseId,
                query,
                topK,
                chatTurnId,
                user
        );
    }

    private KnowledgeRetrievalExecution execute(
            UUID knowledgeBaseId,
            String rawQuery,
            Integer requestedTopK,
            UUID chatTurnId,
            SafeAiUserPrincipal user
    ) {
        KnowledgeAccessService.Access access =
                accessService.requireAccess(
                        knowledgeBaseId,
                        user,
                        KnowledgeBaseAccessLevel.VIEWER
                );

        String query =
                normalizeQuery(
                        rawQuery
                );

        int topK =
                requestedTopK == null
                        ? DEFAULT_TOP_K
                        : requestedTopK;

        if (topK < 1
                || topK > properties.maxTopK()) {
            throw new BadRequestException(
                    "topK должен быть в диапазоне 1.."
                            + properties.maxTopK()
            );
        }

        Instant startedAt =
                clock.instant();

        float[] queryEmbedding =
                embeddingProvider.embed(
                        query
                );

        validateEmbedding(
                queryEmbedding
        );

        List<KnowledgeRetrievalHit> hits =
                retrievalRepository.hybridSearch(
                        user.getOrganizationId(),
                        knowledgeBaseId,
                        user.getId(),
                        access.administrator(),
                        query,
                        queryEmbedding,
                        embeddingProvider.model(),
                        topK,
                        properties.candidateLimit(),
                        properties.rrfK()
                );

        UUID runId =
                UUID.randomUUID();

        String querySha256 =
                sha256(
                        query
                );

        Instant completedAt =
                clock.instant();

        persistenceTransaction.executeWithoutResult(
                status -> {
                    persistRun(
                            runId,
                            knowledgeBaseId,
                            user,
                            query,
                            querySha256,
                            topK,
                            startedAt,
                            completedAt,
                            chatTurnId
                    );

                    persistHits(
                            runId,
                            knowledgeBaseId,
                            user.getOrganizationId(),
                            hits
                    );

                    audit.record(
                            user,
                            user.getOrganizationId(),
                            AuditEventType.KNOWLEDGE_RETRIEVAL_COMPLETED,
                            Map.of(
                                    "knowledgeBaseId",
                                    knowledgeBaseId.toString(),
                                    "retrievalRunId",
                                    runId.toString(),
                                    "querySha256",
                                    querySha256,
                                    "embeddingModel",
                                    embeddingProvider.model(),
                                    "topK",
                                    topK,
                                    "hitCount",
                                    hits.size()
                            )
                    );
                }
        );

        return new KnowledgeRetrievalExecution(
                runId,
                knowledgeBaseId,
                chatTurnId,
                querySha256,
                embeddingProvider.model(),
                completedAt,
                hits
        );
    }

    private String normalizeQuery(
            String value
    ) {
        String query =
                value == null
                        ? ""
                        : value.strip();

        int codePoints =
                query.codePointCount(
                        0,
                        query.length()
                );

        if (query.isEmpty()
                || codePoints
                > properties.maxQueryChars()) {
            throw new BadRequestException(
                    "Некорректный поисковый запрос."
            );
        }

        boolean unsafeControl =
                query.codePoints()
                        .anyMatch(
                                codePoint ->
                                        Character.isISOControl(
                                                codePoint
                                        )
                                                && codePoint != '\n'
                                                && codePoint != '\t'
                        );

        if (unsafeControl) {
            throw new BadRequestException(
                    "Поисковый запрос содержит недопустимые управляющие символы."
            );
        }

        return query;
    }

    private void validateEmbedding(
            float[] embedding
    ) {
        if (embedding == null
                || embedding.length
                != embeddingProvider.dimensions()
                || embedding.length
                != EMBEDDING_DIMENSIONS) {
            throw new IllegalStateException(
                    "Knowledge embedding dimension must be "
                            + EMBEDDING_DIMENSIONS
            );
        }

        double squareSum = 0.0;

        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException(
                        "Knowledge query embedding contains non-finite value"
                );
            }

            squareSum +=
                    value * value;
        }

        if (squareSum == 0.0
                || !Double.isFinite(squareSum)) {
            throw new IllegalStateException(
                    "Knowledge query embedding must be non-zero"
            );
        }
    }

    private void persistRun(
            UUID runId,
            UUID knowledgeBaseId,
            SafeAiUserPrincipal user,
            String query,
            String querySha256,
            int topK,
            Instant startedAt,
            Instant completedAt,
            UUID chatTurnId
    ) {
        jdbcTemplate.update(
                """
                insert into knowledge_retrieval_runs (
                    id,
                    organization_id,
                    knowledge_base_id,
                    user_id,
                    chat_turn_id,
                    query_text,
                    query_sha256,
                    embedding_model,
                    top_k,
                    candidate_limit,
                    rrf_k,
                    started_at,
                    completed_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId,
                user.getOrganizationId(),
                knowledgeBaseId,
                user.getId(),
                chatTurnId,
                query,
                querySha256,
                embeddingProvider.model(),
                topK,
                properties.candidateLimit(),
                properties.rrfK(),
                Timestamp.from(
                        startedAt
                ),
                Timestamp.from(
                        completedAt
                )
        );
    }

    private void persistHits(
            UUID runId,
            UUID knowledgeBaseId,
            UUID organizationId,
            List<KnowledgeRetrievalHit> hits
    ) {
        List<RankedRetrievalHit> rankedHits =
                java.util.stream.IntStream
                        .range(
                                0,
                                hits.size()
                        )
                        .mapToObj(
                                index ->
                                        new RankedRetrievalHit(
                                                index + 1,
                                                hits.get(index)
                                        )
                        )
                        .toList();

        jdbcTemplate.batchUpdate(
                """
                insert into knowledge_retrieval_hits (
                    id,
                    retrieval_run_id,
                    organization_id,
                    knowledge_base_id,
                    chunk_id,
                    document_name_snapshot,
                    rank,
                    fused_score,
                    lexical_rank,
                    semantic_rank,
                    lexical_score,
                    cosine_similarity
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rankedHits,
                100,
                (
                        PreparedStatement statement,
                        RankedRetrievalHit rankedHit
                ) -> {
                    KnowledgeRetrievalHit hit =
                            rankedHit.hit();

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
                            organizationId
                    );
                    statement.setObject(
                            4,
                            knowledgeBaseId
                    );
                    statement.setObject(
                            5,
                            hit.chunkId()
                    );
                    statement.setString(
                            6,
                            hit.documentName()
                    );
                    statement.setInt(
                            7,
                            rankedHit.rank()
                    );
                    statement.setDouble(
                            8,
                            hit.fusedScore()
                    );

                    setNullableInteger(
                            statement,
                            9,
                            hit.lexicalRank()
                    );

                    setNullableInteger(
                            statement,
                            10,
                            hit.semanticRank()
                    );

                    setNullableFloat(
                            statement,
                            11,
                            hit.lexicalScore()
                    );

                    setNullableFloat(
                            statement,
                            12,
                            hit.cosineSimilarity()
                    );
                }
        );
    }

    private static void setNullableInteger(
            PreparedStatement statement,
            int index,
            Integer value
    ) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(
                    index,
                    Types.INTEGER
            );
        } else {
            statement.setInt(
                    index,
                    value
            );
        }
    }

    private static void setNullableFloat(
            PreparedStatement statement,
            int index,
            Float value
    ) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(
                    index,
                    Types.REAL
            );
        } else {
            statement.setFloat(
                    index,
                    value
            );
        }
    }

    private static String sha256(
            String value
    ) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest
                                    .getInstance(
                                            "SHA-256"
                                    )
                                    .digest(
                                            value.getBytes(
                                                    StandardCharsets.UTF_8
                                            )
                                    )
                    );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }

    private record RankedRetrievalHit(
            int rank,
            KnowledgeRetrievalHit hit
    ) {
    }
}
