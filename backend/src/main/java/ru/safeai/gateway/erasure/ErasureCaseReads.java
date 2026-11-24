// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ErasureCaseReads.java
package ru.safeai.gateway.erasure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.erasure.ErasureCaseTypes.CaseView;
import ru.safeai.gateway.erasure.ErasureCaseTypes.Operation;
import ru.safeai.gateway.erasure.ErasureCaseTypes.ResourceType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** PostgreSQL-backed, tenant-scoped R1a case reads. */
@Repository
public class ErasureCaseReads {

    private final JdbcTemplate jdbc;

    public ErasureCaseReads(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Package-private data carrier: only the erasure service boundary consumes it.
    record Row(
            CaseView view,
            UUID organizationId,
            UUID requesterId,
            UUID previewId,
            String previewDigest,
            long resourceVersion,
            String idempotencyKey,
            String requestDigest,
            String submitKey,
            String withdrawKey
    ) { }

    Row findByIdempotency(UUID organizationId, UUID actorId, String key) {
        List<Row> rows = jdbc.query("""
                SELECT id, organization_id, requested_by_user_id, resource_type, resource_id,
                       operation, scope, state, version, created_at, preview_id, preview_digest,
                       expected_resource_version, idempotency_key, request_digest,
                       submit_key, withdraw_key
                FROM public.erasure_requests_v103
                WHERE organization_id = ? AND requested_by_user_id = ? AND idempotency_key = ?
                """, this::mapRow, organizationId, actorId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Row find(UUID id, UUID organizationId, UUID actorId, boolean lock) {
        String sql = """
                SELECT id, organization_id, requested_by_user_id, resource_type, resource_id,
                       operation, scope, state, version, created_at, preview_id, preview_digest,
                       expected_resource_version, idempotency_key, request_digest,
                       submit_key, withdraw_key
                FROM public.erasure_requests_v103
                WHERE id = ? AND organization_id = ? AND requested_by_user_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<Row> rows = jdbc.query(sql, this::mapRow, id, organizationId, actorId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<CaseView> listSelf(UUID organizationId, UUID actorId) {
        return jdbc.query("""
                SELECT id, resource_type, resource_id, operation, scope,
                       state, version, created_at
                FROM public.erasure_requests_v103
                WHERE organization_id = ? AND requested_by_user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 100
                """, (rs, index) -> mapView(rs), organizationId, actorId);
    }

    List<CaseView> listTenant(UUID organizationId) {
        return jdbc.query("""
                SELECT id, resource_type, resource_id, operation, scope,
                       state, version, created_at
                FROM public.erasure_requests_v103
                WHERE organization_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 100
                """, (rs, index) -> mapView(rs), organizationId);
    }

    private Row mapRow(ResultSet rs, int index) throws SQLException {
        return new Row(
                mapView(rs),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("requested_by_user_id", UUID.class),
                rs.getObject("preview_id", UUID.class),
                rs.getString("preview_digest"),
                rs.getLong("expected_resource_version"),
                rs.getString("idempotency_key"),
                rs.getString("request_digest"),
                rs.getString("submit_key"),
                rs.getString("withdraw_key")
        );
    }

    private CaseView mapView(ResultSet rs) throws SQLException {
        return new CaseView(
                rs.getObject("id", UUID.class),
                ResourceType.valueOf(rs.getString("resource_type")),
                rs.getObject("resource_id", UUID.class),
                Operation.valueOf(rs.getString("operation")),
                rs.getString("scope"),
                rs.getString("state"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                false,
                false
        );
    }
}
