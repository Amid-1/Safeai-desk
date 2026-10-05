package ru.safeai.gateway.model.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence boundary for immutable reserved/prepared request receipts. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class ModelRouteRequestBindingRepository {

    private static final String SHA_256_PATTERN = "[0-9a-f]{64}";

    private final JdbcTemplate jdbc;

    public ModelRouteRequestBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public int insertReserved(
            UUID decisionId,
            UUID chatTurnId,
            UUID providerOperationId,
            String baseRequestSha256,
            Instant createdAt
    ) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(chatTurnId, "chatTurnId");
        Objects.requireNonNull(providerOperationId, "providerOperationId");
        requireSha256(baseRequestSha256, "baseRequestSha256");
        Objects.requireNonNull(createdAt, "createdAt");

        return jdbc.update(
                """
                insert into public.model_route_reserved_requests (
                    model_route_decision_id,
                    chat_turn_id,
                    provider_operation_id,
                    base_request_sha256,
                    created_at
                ) values (?, ?, ?, ?, ?)
                """,
                statement -> {
                    statement.setObject(1, decisionId);
                    statement.setObject(2, chatTurnId);
                    statement.setObject(3, providerOperationId);
                    statement.setString(4, baseRequestSha256);
                    statement.setTimestamp(5, Timestamp.from(createdAt));
                }
        );
    }

    public Optional<String> findReservedHash(
            UUID decisionId,
            UUID chatTurnId,
            UUID providerOperationId
    ) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(chatTurnId, "chatTurnId");
        Objects.requireNonNull(providerOperationId, "providerOperationId");

        List<String> rows = jdbc.query(
                """
                select base_request_sha256
                from public.model_route_reserved_requests
                where model_route_decision_id = ?
                  and chat_turn_id = ?
                  and provider_operation_id = ?
                """,
                (rs, row) -> rs.getString("base_request_sha256"),
                decisionId,
                chatTurnId,
                providerOperationId
        );
        return rows.stream().findFirst();
    }

    public Optional<KnowledgeRagReceipt> findKnowledgeRagReceipt(
            UUID chatTurnId,
            UUID knowledgeBaseId
    ) {
        Objects.requireNonNull(chatTurnId, "chatTurnId");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");

        List<KnowledgeRagReceipt> rows = jdbc.query(
                """
                select retrieval_run_id,
                       chat_turn_id,
                       organization_id,
                       user_id,
                       knowledge_base_id,
                       embedding_model,
                       context_sha256,
                       prepared_request_sha256,
                       created_at
                from public.knowledge_rag_context_receipts_v64
                where chat_turn_id = ?
                  and knowledge_base_id = ?
                """,
                (rs, row) -> new KnowledgeRagReceipt(
                        rs.getObject("retrieval_run_id", UUID.class),
                        rs.getObject("chat_turn_id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("knowledge_base_id", UUID.class),
                        rs.getString("embedding_model"),
                        rs.getString("context_sha256"),
                        rs.getString("prepared_request_sha256"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                chatTurnId,
                knowledgeBaseId
        );
        return rows.stream().findFirst();
    }

    /**
     * Idempotently persists the immutable V62/V64 prepared-request receipt.
     *
     * <p>A concurrent replay may legitimately affect zero rows because the
     * decision primary key already exists. The caller subsequently loads and
     * compares the persisted receipt byte-for-byte/field-for-field.</p>
     */
    public void insertPreparedIfAbsent(PreparedBinding binding) {
        Objects.requireNonNull(binding, "binding");

        int affected = jdbc.update(
                """
                insert into public.model_route_prepared_requests_v62 (
                    model_route_decision_id,
                    chat_turn_id,
                    provider_operation_id,
                    base_request_sha256,
                    prepared_request_sha256,
                    input_accounting_version,
                    prepared_input_units,
                    created_at,
                    knowledge_base_id,
                    retrieval_run_id,
                    rag_context_sha256,
                    binding_version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (model_route_decision_id) do nothing
                """,
                statement -> {
                    statement.setObject(1, binding.modelRouteDecisionId());
                    statement.setObject(2, binding.chatTurnId());
                    statement.setObject(3, binding.providerOperationId());
                    statement.setString(4, binding.baseRequestSha256());
                    statement.setString(5, binding.preparedRequestSha256());
                    statement.setString(6, binding.inputAccountingVersion());
                    statement.setLong(7, binding.preparedInputUnits());
                    statement.setTimestamp(8, Timestamp.from(binding.createdAt()));
                    statement.setObject(9, binding.knowledgeBaseId());
                    statement.setObject(10, binding.retrievalRunId());
                    statement.setString(11, binding.ragContextSha256());
                    statement.setShort(12, binding.bindingVersion());
                }
        );

        if (affected != 0 && affected != 1) {
            throw new IllegalStateException(
                    "Prepared request insert affected unexpected row count: " + affected
            );
        }
    }

    public Optional<PreparedBinding> findPrepared(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId");

        List<PreparedBinding> rows = jdbc.query(
                """
                select model_route_decision_id,
                       chat_turn_id,
                       provider_operation_id,
                       base_request_sha256,
                       prepared_request_sha256,
                       input_accounting_version,
                       prepared_input_units,
                       created_at,
                       knowledge_base_id,
                       retrieval_run_id,
                       rag_context_sha256,
                       binding_version
                from public.model_route_prepared_requests_v62
                where model_route_decision_id = ?
                """,
                (rs, row) -> new PreparedBinding(
                        rs.getObject("model_route_decision_id", UUID.class),
                        rs.getObject("chat_turn_id", UUID.class),
                        rs.getObject("provider_operation_id", UUID.class),
                        rs.getString("base_request_sha256"),
                        rs.getString("prepared_request_sha256"),
                        rs.getString("input_accounting_version"),
                        rs.getLong("prepared_input_units"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("knowledge_base_id", UUID.class),
                        rs.getObject("retrieval_run_id", UUID.class),
                        rs.getString("rag_context_sha256"),
                        rs.getShort("binding_version")
                ),
                decisionId
        );
        return rows.stream().findFirst();
    }

    public record KnowledgeRagReceipt(
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
        public KnowledgeRagReceipt {
            Objects.requireNonNull(retrievalRunId, "retrievalRunId");
            Objects.requireNonNull(chatTurnId, "chatTurnId");
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
            embeddingModel = requireSafeText(embeddingModel, 128, "embeddingModel");
            requireSha256(contextSha256, "contextSha256");
            requireSha256(preparedRequestSha256, "preparedRequestSha256");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record PreparedBinding(
            UUID modelRouteDecisionId,
            UUID chatTurnId,
            UUID providerOperationId,
            String baseRequestSha256,
            String preparedRequestSha256,
            String inputAccountingVersion,
            long preparedInputUnits,
            Instant createdAt,
            UUID knowledgeBaseId,
            UUID retrievalRunId,
            String ragContextSha256,
            short bindingVersion
    ) {
        public PreparedBinding {
            Objects.requireNonNull(modelRouteDecisionId, "modelRouteDecisionId");
            Objects.requireNonNull(chatTurnId, "chatTurnId");
            Objects.requireNonNull(providerOperationId, "providerOperationId");
            requireSha256(baseRequestSha256, "baseRequestSha256");
            requireSha256(preparedRequestSha256, "preparedRequestSha256");
            inputAccountingVersion = requireSafeText(
                    inputAccountingVersion,
                    64,
                    "inputAccountingVersion"
            );
            Objects.requireNonNull(createdAt, "createdAt");

            if (preparedInputUnits < 0L) {
                throw new IllegalArgumentException(
                        "preparedInputUnits must be non-negative"
                );
            }
            if (bindingVersion < 1 || bindingVersion > 2) {
                throw new IllegalArgumentException(
                        "bindingVersion must be 1 or 2"
                );
            }

            boolean noKnowledge = knowledgeBaseId == null
                    && retrievalRunId == null
                    && ragContextSha256 == null;
            boolean fullKnowledge = knowledgeBaseId != null
                    && retrievalRunId != null
                    && ragContextSha256 != null;
            if (!noKnowledge && !fullKnowledge) {
                throw new IllegalArgumentException(
                        "Prepared binding Knowledge provenance must be absent or complete"
                );
            }
            if (ragContextSha256 != null) {
                requireSha256(ragContextSha256, "ragContextSha256");
            }
            if (bindingVersion == 1 && !noKnowledge) {
                throw new IllegalArgumentException(
                        "Historical bindingVersion=1 cannot carry V64 Knowledge provenance"
                );
            }
        }
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches(SHA_256_PATTERN)) {
            throw new IllegalArgumentException(
                    field + " must be a lowercase SHA-256 value"
            );
        }
    }

    private static String requireSafeText(
            String value,
            int maxLength,
            String field
    ) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()
                || !value.equals(value.trim())
                || value.length() > maxLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " must be trimmed, non-blank, bounded and control-free"
            );
        }
        return value;
    }
}
