package ru.safeai.gateway.knowledge.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable write boundary for the exact context materialized by Knowledge RAG. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class KnowledgeRagContextReceiptRepository {

    private final JdbcTemplate jdbc;

    KnowledgeRagContextReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(
                jdbc,
                "jdbc не должен быть null"
        );
    }

    void insert(
            UUID retrievalRunId,
            UUID chatTurnId,
            UUID organizationId,
            UUID userId,
            UUID knowledgeBaseId,
            String embeddingModel,
            String contextSha256,
            String preparedRequestSha256,
            Instant createdAt
    ) {
        Objects.requireNonNull(retrievalRunId, "retrievalRunId");
        Objects.requireNonNull(chatTurnId, "chatTurnId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        Objects.requireNonNull(embeddingModel, "embeddingModel");
        Objects.requireNonNull(contextSha256, "contextSha256");
        Objects.requireNonNull(preparedRequestSha256, "preparedRequestSha256");
        Objects.requireNonNull(createdAt, "createdAt");

        int updated = jdbc.update(
                """
                insert into public.knowledge_rag_context_receipts_v64 (
                    retrieval_run_id,
                    chat_turn_id,
                    organization_id,
                    user_id,
                    knowledge_base_id,
                    embedding_model,
                    context_sha256,
                    prepared_request_sha256,
                    created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                retrievalRunId,
                chatTurnId,
                organizationId,
                userId,
                knowledgeBaseId,
                embeddingModel,
                contextSha256,
                preparedRequestSha256,
                Timestamp.from(createdAt)
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Knowledge RAG context receipt insert affected " + updated + " rows"
            );
        }
    }
}
