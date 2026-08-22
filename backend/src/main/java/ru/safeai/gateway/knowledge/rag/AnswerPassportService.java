package ru.safeai.gateway.knowledge.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.service.ChatProcessingContext;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.AnswerCitationResponse;
import ru.safeai.gateway.knowledge.dto.AnswerPassportResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class AnswerPassportService {

    private final JdbcTemplate jdbc;
    private final AuditEventService audit;

    public AnswerPassportService(
            JdbcTemplate jdbc,
            AuditEventService audit
    ) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * Answer Passport is part of the durable chat-finalization boundary.
     * It must never commit independently from the assistant message/turn.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AnswerPassportResponse persist(
            ChatProcessingContext context,
            UUID assistantMessageId,
            String provider,
            RagCompletion completion,
            SafeAiUserPrincipal user,
            Instant now
    ) {
        Objects.requireNonNull(
                context,
                "context не должен быть null"
        );
        Objects.requireNonNull(
                assistantMessageId,
                "assistantMessageId не должен быть null"
        );
        Objects.requireNonNull(
                completion,
                "completion не должен быть null"
        );
        Objects.requireNonNull(
                user,
                "user не должен быть null"
        );
        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        if (!completion.preparation()
                .mode()
                .usesKnowledge()) {
            return null;
        }

        RagPreparation preparation =
                completion.preparation();

        AiChatResponse response =
                completion.response();

        validateCompletion(
                preparation,
                completion
        );

        UUID passportId =
                UUID.randomUUID();

        jdbc.update(
                """
                insert into knowledge_answer_passports (
                    id,
                    organization_id,
                    knowledge_base_id,
                    user_id,
                    chat_id,
                    chat_turn_id,
                    retrieval_run_id,
                    assistant_message_id,
                    knowledge_mode,
                    provider,
                    requested_model,
                    resolved_model,
                    embedding_model,
                    context_sha256,
                    answer_sha256,
                    evidence_sufficient,
                    citations_valid,
                    citation_count,
                    created_at
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                passportId,
                user.getOrganizationId(),
                preparation.knowledgeBaseId(),
                user.getId(),
                context.chatId(),
                context.turnId(),
                preparation.retrievalRunId(),
                assistantMessageId,
                preparation.mode().name(),
                normalizeProvider(provider),
                response.requestedModel(),
                response.model(),
                preparation.embeddingModel(),
                preparation.contextSha256(),
                KnowledgeHashing.sha256(
                        response.content()
                ),
                completion.evidenceSufficient(),
                completion.citationsValid(),
                completion.citations().size(),
                Timestamp.from(now)
        );

        Map<String, KnowledgeContextSource> sources =
                sourceIndex(
                        preparation.sources()
                );

        List<CitationRow> citationRows =
                completion.citations()
                        .stream()
                        .sorted(
                                Comparator.comparingInt(
                                        RagCitation::ordinal
                                )
                        )
                        .map(
                                citation -> new CitationRow(
                                        citation,
                                        requireSource(
                                                sources,
                                                citation.label()
                                        )
                                )
                        )
                        .toList();

        jdbc.batchUpdate(
                """
                insert into knowledge_answer_citations (
                    id,
                    answer_passport_id,
                    retrieval_run_id,
                    organization_id,
                    knowledge_base_id,
                    chunk_id,
                    citation_label,
                    ordinal,
                    created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                citationRows,
                100,
                (statement, row) -> {
                    statement.setObject(
                            1,
                            UUID.randomUUID()
                    );
                    statement.setObject(
                            2,
                            passportId
                    );
                    statement.setObject(
                            3,
                            preparation.retrievalRunId()
                    );
                    statement.setObject(
                            4,
                            user.getOrganizationId()
                    );
                    statement.setObject(
                            5,
                            preparation.knowledgeBaseId()
                    );
                    statement.setObject(
                            6,
                            row.citation().chunkId()
                    );
                    statement.setString(
                            7,
                            row.citation().label()
                    );
                    statement.setInt(
                            8,
                            row.citation().ordinal()
                    );
                    statement.setTimestamp(
                            9,
                            Timestamp.from(now)
                    );
                }
        );

        audit.record(
                user,
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_ANSWER_GENERATED,
                Map.of(
                        "chatTurnId",
                        context.turnId().toString(),
                        "retrievalRunId",
                        preparation.retrievalRunId().toString(),
                        "answerPassportId",
                        passportId.toString(),
                        "knowledgeMode",
                        preparation.mode().name(),
                        "citationCount",
                        completion.citations().size(),
                        "citationsValid",
                        completion.citationsValid(),
                        "evidenceSufficient",
                        completion.evidenceSufficient()
                )
        );

        return toResponse(
                passportId,
                context.turnId(),
                preparation,
                normalizeProvider(provider),
                response,
                completion,
                citationRows,
                now
        );
    }

    @Transactional(readOnly = true)
    public AnswerPassportResponse findByTurn(
            UUID turnId,
            UUID organizationId,
            UUID userId
    ) {
        List<PassportRow> rows =
                jdbc.query(
                        """
                        select
                            id,
                            chat_turn_id,
                            retrieval_run_id,
                            knowledge_base_id,
                            knowledge_mode,
                            provider,
                            requested_model,
                            resolved_model,
                            embedding_model,
                            context_sha256,
                            answer_sha256,
                            evidence_sufficient,
                            citations_valid,
                            created_at
                        from knowledge_answer_passports
                        where chat_turn_id = ?
                          and organization_id = ?
                          and user_id = ?
                        """,
                        this::mapPassport,
                        turnId,
                        organizationId,
                        userId
                );

        if (rows.isEmpty()) {
            return null;
        }

        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Нарушен invariant: более одного Answer Passport для chat turn"
            );
        }

        PassportRow row =
                rows.getFirst();

        return toResponse(
                row,
                findCitations(
                        row.id()
                )
        );
    }

    @Transactional(readOnly = true)
    public AnswerPassportResponse requireByTurn(
            UUID chatId,
            UUID turnId,
            UUID organizationId,
            UUID userId
    ) {
        List<PassportRow> rows =
                jdbc.query(
                        """
                        select
                            id,
                            chat_turn_id,
                            retrieval_run_id,
                            knowledge_base_id,
                            knowledge_mode,
                            provider,
                            requested_model,
                            resolved_model,
                            embedding_model,
                            context_sha256,
                            answer_sha256,
                            evidence_sufficient,
                            citations_valid,
                            created_at
                        from knowledge_answer_passports
                        where chat_id = ?
                          and chat_turn_id = ?
                          and organization_id = ?
                          and user_id = ?
                        """,
                        this::mapPassport,
                        chatId,
                        turnId,
                        organizationId,
                        userId
                );

        if (rows.isEmpty()) {
            throw new ResourceNotFoundException(
                    "Answer Passport не найден."
            );
        }

        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Нарушен invariant: более одного Answer Passport для chat turn"
            );
        }

        PassportRow row =
                rows.getFirst();

        return toResponse(
                row,
                findCitations(
                        row.id()
                )
        );
    }

    private List<AnswerCitationResponse> findCitations(
            UUID passportId
    ) {
        return jdbc.query(
                """
                select
                    citation.citation_label,
                    chunk.id as chunk_id,
                    chunk.document_id,
                    chunk.document_version_id,
                    retrieval_hit.document_name_snapshot as document_name,
                    version.version_number,
                    chunk.ordinal as chunk_ordinal,
                    chunk.page_from,
                    chunk.page_to,
                    chunk.heading,
                    chunk.content_sha256
                from knowledge_answer_citations citation
                join knowledge_retrieval_hits retrieval_hit
                  on retrieval_hit.retrieval_run_id =
                        citation.retrieval_run_id
                 and retrieval_hit.chunk_id =
                        citation.chunk_id
                 and retrieval_hit.knowledge_base_id =
                        citation.knowledge_base_id
                 and retrieval_hit.organization_id =
                        citation.organization_id
                join knowledge_document_chunks chunk
                  on chunk.id = citation.chunk_id
                 and chunk.knowledge_base_id =
                        citation.knowledge_base_id
                 and chunk.organization_id =
                        citation.organization_id
                join knowledge_document_versions version
                  on version.id =
                        chunk.document_version_id
                 and version.document_id =
                        chunk.document_id
                 and version.knowledge_base_id =
                        chunk.knowledge_base_id
                 and version.organization_id =
                        chunk.organization_id
                where citation.answer_passport_id = ?
                order by citation.ordinal
                """,
                this::mapCitation,
                passportId
        );
    }

    private static void validateCompletion(
            RagPreparation preparation,
            RagCompletion completion
    ) {
        if (completion.response() == null) {
            throw new IllegalArgumentException(
                    "RAG completion response не должен быть null"
            );
        }

        if (preparation.retrievalRunId() == null
                || preparation.knowledgeBaseId() == null) {
            throw new IllegalStateException(
                    "Knowledge completion не содержит retrieval identity"
            );
        }

        if (!completion.citationsValid()
                && !completion.citations().isEmpty()) {
            throw new IllegalStateException(
                    "Invalid citation set не должен сохраняться как Answer Passport"
            );
        }
    }

    private static Map<String, KnowledgeContextSource> sourceIndex(
            List<KnowledgeContextSource> sources
    ) {
        Map<String, KnowledgeContextSource> index =
                new LinkedHashMap<>();

        for (KnowledgeContextSource source : sources) {
            if (source == null
                    || source.label() == null
                    || source.label().isBlank()) {
                throw new IllegalStateException(
                        "Knowledge source имеет некорректный label"
                );
            }

            if (index.put(
                    source.label(),
                    source
            ) != null) {
                throw new IllegalStateException(
                        "Knowledge source label продублирован: "
                                + source.label()
                );
            }
        }

        return Map.copyOf(
                index
        );
    }

    private static KnowledgeContextSource requireSource(
            Map<String, KnowledgeContextSource> sources,
            String label
    ) {
        KnowledgeContextSource source =
                sources.get(label);

        if (source == null) {
            throw new IllegalStateException(
                    "Citation ссылается на отсутствующий source: "
                            + label
            );
        }

        return source;
    }

    private static String normalizeProvider(
            String provider
    ) {
        if (provider == null
                || provider.isBlank()) {
            throw new IllegalArgumentException(
                    "provider не должен быть пустым"
            );
        }

        String normalized =
                provider.strip();

        if (normalized.length() > 32) {
            throw new IllegalArgumentException(
                    "provider слишком длинный"
            );
        }

        return normalized;
    }

    private AnswerPassportResponse toResponse(
            UUID passportId,
            UUID turnId,
            RagPreparation preparation,
            String provider,
            AiChatResponse response,
            RagCompletion completion,
            List<CitationRow> rows,
            Instant now
    ) {
        List<AnswerCitationResponse> citations =
                new ArrayList<>();

        for (CitationRow row : rows) {
            var hit =
                    row.source()
                            .hit();

            citations.add(
                    new AnswerCitationResponse(
                            row.citation().label(),
                            hit.chunkId(),
                            hit.documentId(),
                            hit.documentVersionId(),
                            hit.documentName(),
                            hit.versionNumber(),
                            hit.chunkOrdinal(),
                            hit.pageFrom(),
                            hit.pageTo(),
                            hit.heading(),
                            hit.contentSha256()
                    )
            );
        }

        return new AnswerPassportResponse(
                passportId,
                turnId,
                preparation.retrievalRunId(),
                preparation.knowledgeBaseId(),
                preparation.mode(),
                provider,
                response.requestedModel(),
                response.model(),
                preparation.embeddingModel(),
                preparation.contextSha256(),
                KnowledgeHashing.sha256(
                        response.content()
                ),
                completion.evidenceSufficient(),
                completion.citationsValid(),
                now,
                citations
        );
    }

    private static AnswerPassportResponse toResponse(
            PassportRow row,
            List<AnswerCitationResponse> citations
    ) {
        return new AnswerPassportResponse(
                row.id(),
                row.chatTurnId(),
                row.retrievalRunId(),
                row.knowledgeBaseId(),
                row.mode(),
                row.provider(),
                row.requestedModel(),
                row.resolvedModel(),
                row.embeddingModel(),
                row.contextSha256(),
                row.answerSha256(),
                row.evidenceSufficient(),
                row.citationsValid(),
                row.createdAt(),
                citations
        );
    }

    private PassportRow mapPassport(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new PassportRow(
                resultSet.getObject(
                        "id",
                        UUID.class
                ),
                resultSet.getObject(
                        "chat_turn_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "retrieval_run_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "knowledge_base_id",
                        UUID.class
                ),
                KnowledgeMode.valueOf(
                        resultSet.getString(
                                "knowledge_mode"
                        )
                ),
                resultSet.getString(
                        "provider"
                ),
                resultSet.getString(
                        "requested_model"
                ),
                resultSet.getString(
                        "resolved_model"
                ),
                resultSet.getString(
                        "embedding_model"
                ),
                resultSet.getString(
                        "context_sha256"
                ),
                resultSet.getString(
                        "answer_sha256"
                ),
                resultSet.getBoolean(
                        "evidence_sufficient"
                ),
                resultSet.getBoolean(
                        "citations_valid"
                ),
                resultSet.getTimestamp(
                        "created_at"
                ).toInstant()
        );
    }

    private AnswerCitationResponse mapCitation(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new AnswerCitationResponse(
                resultSet.getString(
                        "citation_label"
                ),
                resultSet.getObject(
                        "chunk_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "document_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "document_version_id",
                        UUID.class
                ),
                resultSet.getString(
                        "document_name"
                ),
                resultSet.getInt(
                        "version_number"
                ),
                resultSet.getInt(
                        "chunk_ordinal"
                ),
                nullableInteger(
                        resultSet,
                        "page_from"
                ),
                nullableInteger(
                        resultSet,
                        "page_to"
                ),
                resultSet.getString(
                        "heading"
                ),
                resultSet.getString(
                        "content_sha256"
                )
        );
    }

    private static Integer nullableInteger(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        int value =
                resultSet.getInt(column);

        return resultSet.wasNull()
                ? null
                : value;
    }

    private record CitationRow(
            RagCitation citation,
            KnowledgeContextSource source
    ) {
    }

    private record PassportRow(
            UUID id,
            UUID chatTurnId,
            UUID retrievalRunId,
            UUID knowledgeBaseId,
            KnowledgeMode mode,
            String provider,
            String requestedModel,
            String resolvedModel,
            String embeddingModel,
            String contextSha256,
            String answerSha256,
            boolean evidenceSufficient,
            boolean citationsValid,
            Instant createdAt
    ) {
    }
}
