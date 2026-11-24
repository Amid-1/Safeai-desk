// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ErasureCaseRepository.java
package ru.safeai.gateway.erasure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.erasure.ErasureCaseTypes.Target;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** R1a intake persistence. No destructive SQL or worker dispatch. */
@Repository
public class ErasureCaseRepository {

    private final JdbcTemplate jdbc;

    public ErasureCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Instant insertPreview(
            UUID id, UUID organizationId, UUID userId,
            Target target, long version, String digest
    ) {
        return jdbc.queryForObject("""
                INSERT INTO public.erasure_previews_v103
                    (id, organization_id, requester_user_id, resource_type, resource_id,
                     operation, scope, resource_version, preview_digest, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
                        transaction_timestamp() + interval '15 minutes')
                RETURNING expires_at
                """, (rs, row) -> rs.getTimestamp(1).toInstant(),
                id, organizationId, userId, target.resourceType().name(),
                target.resourceId(), target.operation().name(), target.scope(),
                version, digest);
    }

    /** True only for an unexpired preview bound to this actor, tenant and target. */
    public boolean matchesPreview(
            UUID id, UUID organizationId, UUID userId,
            Target target, long version, String digest
    ) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM public.erasure_previews_v103
                    WHERE id = ? AND organization_id = ? AND requester_user_id = ?
                      AND resource_type = ? AND resource_id = ?
                      AND operation = ? AND scope = ?
                      AND resource_version = ? AND preview_digest = ?
                      AND expires_at > transaction_timestamp()
                )
                """, Boolean.class, id, organizationId, userId,
                target.resourceType().name(), target.resourceId(),
                target.operation().name(), target.scope(), version, digest);
        return Boolean.TRUE.equals(exists);
    }

    public boolean insertDraft(
            UUID id, UUID organizationId, UUID actorId, Target target,
            UUID previewId, String previewDigest, long resourceVersion,
            String reason, String key, String requestDigest
    ) {
        List<UUID> inserted = jdbc.query("""
                INSERT INTO public.erasure_requests_v103
                    (id, organization_id, requested_by_user_id, resource_type, resource_id,
                     operation, scope, reason_code, preview_id, preview_digest,
                     expected_resource_version, idempotency_key, request_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class),
                id, organizationId, actorId, target.resourceType().name(),
                target.resourceId(), target.operation().name(), target.scope(),
                reason, previewId, previewDigest, resourceVersion, key, requestDigest);
        return !inserted.isEmpty();
    }

    public void event(
            UUID requestId, UUID organizationId, UUID actorId,
            String eventType, long version
    ) {
        jdbc.update("""
                INSERT INTO public.erasure_request_events_v103
                    (id, request_id, organization_id, actor_user_id, event_type, version)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), requestId, organizationId, actorId, eventType, version);
    }

    public boolean transition(
            UUID id, UUID organizationId, String from, String to,
            long version, String key
    ) {
        int affected = jdbc.update("""
                UPDATE public.erasure_requests_v103
                SET state = ?,
                    version = version + 1,
                    updated_at = transaction_timestamp(),
                    submit_key = CASE WHEN ? = 'SUBMITTED' THEN ? ELSE submit_key END,
                    withdraw_key = CASE WHEN ? = 'WITHDRAWN' THEN ? ELSE withdraw_key END,
                    submitted_at = CASE WHEN ? = 'SUBMITTED' THEN transaction_timestamp()
                                        ELSE submitted_at END
                WHERE id = ? AND organization_id = ? AND state = ? AND version = ?
                """, to, to, key, to, key, to, id, organizationId, from, version);
        return affected == 1;
    }
}
