package ru.safeai.gateway.knowledge.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentDeletionResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped access to the immutable-identity V78 deletion intent. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeDocumentDeletionRepository {
    private final JdbcTemplate jdbc;

    public KnowledgeDocumentDeletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Request> findByKey(UUID organizationId, String idempotencyKey) {
        return first(jdbc.query("""
                SELECT * FROM public.knowledge_document_deletions_v78
                WHERE organization_id = ? AND idempotency_key = ?
                """, this::map, organizationId, idempotencyKey));
    }

    public Optional<Request> findByDocument(UUID organizationId, UUID documentId) {
        return first(jdbc.query("""
                SELECT * FROM public.knowledge_document_deletions_v78
                WHERE organization_id = ? AND document_id = ?
                """, this::map, organizationId, documentId));
    }

    public Optional<Request> findById(UUID organizationId, UUID knowledgeBaseId, UUID operationId) {
        return first(jdbc.query("""
                SELECT * FROM public.knowledge_document_deletions_v78
                WHERE organization_id = ? AND knowledge_base_id = ? AND id = ?
                """, this::map, organizationId, knowledgeBaseId, operationId));
    }

    public List<KnowledgeDocumentDeletionResponse> listByBase(UUID organizationId, UUID knowledgeBaseId) {
        return jdbc.query("""
                SELECT * FROM public.knowledge_document_deletions_v78
                WHERE organization_id=? AND knowledge_base_id=?
                ORDER BY created_at DESC, id DESC LIMIT 50
                """, (rs, row) -> map(rs, row).response(), organizationId, knowledgeBaseId);
    }

    /** Zero rows means a competing request has won one of the two UNIQUE constraints. */
    public boolean insertIfAbsent(UUID id, UUID orgId, UUID baseId, UUID documentId,
                                  UUID actorId, String key, long expectedVersion) {
        return jdbc.update("""
                INSERT INTO public.knowledge_document_deletions_v78 (
                    id, organization_id, knowledge_base_id, document_id,
                    requested_by_user_id, idempotency_key, expected_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, id, orgId, baseId, documentId, actorId, key, expectedVersion) == 1;
    }

    private Request map(ResultSet rs, int row) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new Request(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("knowledge_base_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getLong("expected_version"),
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant()
        );
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    public record Request(UUID id, UUID organizationId, UUID knowledgeBaseId,
                          UUID documentId, String idempotencyKey, long expectedVersion,
                          String state, java.time.Instant createdAt,
                          java.time.Instant completedAt) {
        public KnowledgeDocumentDeletionResponse response() {
            return new KnowledgeDocumentDeletionResponse(
                    id, documentId, state, createdAt, completedAt);
        }
    }
}
