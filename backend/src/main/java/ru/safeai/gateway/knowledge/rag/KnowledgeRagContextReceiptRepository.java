// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/knowledge/rag/KnowledgeRagContextReceiptRepository.java
package ru.safeai.gateway.knowledge.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Хранит неизменяемое доказательство подготовленного RAG-контекста.
 *
 * <p>Работает исключительно через JdbcTemplate. Аннотация {@code @Repository}
 * здесь недопустима: общий JPA-переводчик исключений может преобразовать
 * {@link KnowledgeRagContextRevokedException} в
 * {@code InvalidDataAccessApiUsageException}. Тогда слой исполнения ИИ
 * перестаёт распознавать отзыв контекста как отдельное доменное событие.
 *
 * <p>Используется {@code @Component}: SQL-ошибки по-прежнему переводятся самим
 * JdbcTemplate, а доменные исключения передаются вызывающему коду без подмены.
 */
@Component
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class KnowledgeRagContextReceiptRepository {

    void assertSourcesAvailable(RagPreparation preparation, UUID organizationId) {
        KnowledgeRagErasureGuard.assertAvailable(jdbc, preparation, organizationId);
    }

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
