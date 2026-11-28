// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ChatExecutionAssessment.java
package ru.safeai.gateway.erasure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.chat.service.ChatErasureLimits;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * R3a.1 foundation. A point-in-time observation for the owner, not an execution
 * permit, not a manifest and not a lock across transactions. No mutation or
 * executor entry point. Do not use this projection to authorize V86 writes.
 */
@Service
public class ChatExecutionAssessment {
    private final JdbcTemplate jdbc;
    private final ErasureCaseAuthority authority;

    public ChatExecutionAssessment(JdbcTemplate jdbc, ErasureCaseAuthority authority) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.authority = Objects.requireNonNull(authority);
    }

    public enum Blocker {
        CASE_NOT_SUBMITTED,
        REVIEW_NOT_APPROVED,
        APPROVER_NOT_CURRENTLY_AUTHORIZED,
        RESOURCE_CHANGED,
        CHAT_NOT_ARCHIVED,
        CHAT_NOT_AVAILABLE,
        LEGAL_HOLD_V86,
        LEGAL_HOLD_V105,
        ACTIVE_AI_OPERATION,
        ATOMIC_LIMIT_EXCEEDED,
        EXISTING_V86_OPERATION,
        POLICY_NOT_ALLOWED,
        UNKNOWN_EVIDENCE,
        // These are unconditional until their trusted issuers are implemented.
        VERIFIED_IDENTITY_REQUIRED,
        INDEPENDENT_EXECUTION_AUTHORITY_REQUIRED,
        FROZEN_MANIFEST_REQUIRED,
        UNIFIED_DB_EXECUTION_GATE_REQUIRED
    }

    /**
     * Metadata only. This is deliberately NOT signed and NOT executable.
     */
    public record Binding(UUID requestId, UUID organizationId, UUID ownerId, UUID chatId,
                          long caseVersion, long resourceVersion, long reviewVersion,
                          long holdRevision, UUID reviewerId, long reviewerTokenVersion,
                          long ownerTokenVersion, long organizationAuthVersion,
                          long policyVersion, String intakeDigest) {
    }

    public record Assessment(
        List<Blocker> blockers,
        Optional<Binding> binding
    ) {
        public Assessment {
            blockers = List.copyOf(blockers);
            Objects.requireNonNull(binding, "binding must not be null");
        }

        public boolean executionAvailable() {
            return false;
        }

        public boolean permitIssued() {
            return false;
        }
    }

    // No readOnly=true: liveRoles uses PostgreSQL FOR SHARE. The observation
    // is consistent at REPEATABLE READ when called as its own transaction.
    // It must never be consumed as current authorization in a later tx.
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Assessment assess(UUID requestId, SafeAiUserPrincipal actor) {
        List<String> roles = authority.liveRoles(actor);
        if (roles.contains("SUPER_ADMIN") || !(roles.contains("USER") || roles.contains("ADMIN"))) {
            throw new ForbiddenOperationException("Недостаточно прав.");
        }
        if (requestId == null) throw notFound();
        List<Assessment> rows = jdbc.query("""
                SELECT r.id, r.organization_id, r.requested_by_user_id, r.resource_id,
                       r.state, r.version AS case_version, r.expected_resource_version,
                       r.request_digest, c.version AS chat_version,
                       c.archived_at IS NOT NULL AS archived, c.erasure_state,
                       v.review_state, v.review_version, v.hold_revision,
                       v.updated_by_user_id AS reviewer_id,
                       reviewer.token_version AS reviewer_token_version,
                       u.token_version AS owner_token_version, o.auth_version AS org_auth_version,
                       p.version AS policy_version, p.chat_allowed,
                       EXISTS (SELECT 1 FROM public.erasure_review_authorities_v105 a
                         WHERE a.organization_id=r.organization_id AND a.user_id=v.updated_by_user_id
                           AND a.authority='REVIEW' AND a.enabled
                           AND reviewer.enabled AND reviewer.id<>r.requested_by_user_id
                           AND EXISTS (SELECT 1 FROM public.user_roles ur
                             JOIN public.roles rr ON rr.id=ur.role_id
                             WHERE ur.user_id=reviewer.id AND rr.name IN ('ADMIN','USER'))
                           AND EXISTS (SELECT 1 FROM public.erasure_review_events_v105 e
                             WHERE e.request_id=r.id AND e.organization_id=r.organization_id
                               AND e.review_version=v.review_version
                               AND e.actor_user_id=v.updated_by_user_id
                               AND e.event_code='APPROVED_REVIEW_ONLY')) AS reviewer_current,
                       EXISTS (SELECT 1 FROM public.chat_retention_holds_v86 h
                         WHERE h.chat_id=c.id AND h.organization_id=c.organization_id
                           AND h.released_at IS NULL) AS held_v86,
                       EXISTS (SELECT 1 FROM public.erasure_review_holds_v105 h
                         JOIN public.erasure_requests_v103 other
                           ON other.id=h.request_id AND other.organization_id=h.organization_id
                         WHERE other.organization_id=r.organization_id
                           AND other.resource_type='CHAT' AND other.resource_id=c.id
                           AND h.released_at IS NULL) AS held_v105,
                       public.chat_has_active_execution_v86(c.id) AS active_ai,
                       ((SELECT count(*) FROM (SELECT 1 FROM public.chat_messages m
                         WHERE m.session_id=c.id AND m.content IS DISTINCT FROM '[СОДЕРЖИМОЕ УДАЛЕНО]'
                         LIMIT ?) bounded_messages) >= ?
                         OR (SELECT count(*) FROM (SELECT 1 FROM public.knowledge_retrieval_runs k
                         JOIN public.chat_turns t ON t.id=k.chat_turn_id
                         WHERE t.session_id=c.id AND k.query_text IS DISTINCT FROM '[REDACTED:SHA256_ONLY]'
                         LIMIT ?) bounded_retrievals) >= ?) AS oversized,
                       EXISTS (SELECT 1 FROM public.chat_erasure_operations_v86 op
                         WHERE op.organization_id=r.organization_id AND op.chat_id=c.id
                           AND op.state<>'CANCELLED') AS existing_operation
                FROM public.erasure_requests_v103 r
                JOIN public.chat_sessions c ON c.id=r.resource_id AND c.organization_id=r.organization_id
                    AND c.user_id=r.requested_by_user_id
                JOIN public.users u ON u.id=r.requested_by_user_id AND u.organization_id=r.organization_id
                JOIN public.organizations o ON o.id=r.organization_id
                LEFT JOIN public.erasure_reviews_v105 v ON v.request_id=r.id
                    AND v.organization_id=r.organization_id AND v.requester_user_id=r.requested_by_user_id
                LEFT JOIN public.users reviewer ON reviewer.id=v.updated_by_user_id
                    AND reviewer.organization_id=r.organization_id
                LEFT JOIN public.platform_erasure_policy_v102 p ON p.singleton
                WHERE r.id=? AND r.organization_id=? AND r.requested_by_user_id=?
                  AND r.resource_type='CHAT' AND r.operation='REDACT_CONTENT'
                  AND r.scope='FULL_CHAT_CONTENT' AND u.enabled AND o.enabled
                """, (rs, row) -> map(rs),
            ChatErasureLimits.MAX_ROWS_PER_KIND + 1, ChatErasureLimits.MAX_ROWS_PER_KIND + 1,
            ChatErasureLimits.MAX_ROWS_PER_KIND + 1, ChatErasureLimits.MAX_ROWS_PER_KIND + 1,
            requestId, actor.getOrganizationId(), actor.getId());
        if (rows.size() != 1) throw notFound();
        return rows.getFirst();
    }

    static Assessment map(ResultSet rs) throws SQLException {
        var blockers = new ArrayList<Blocker>();
        if (!"SUBMITTED".equals(rs.getString("state"))) blockers.add(Blocker.CASE_NOT_SUBMITTED);
        if (!"APPROVED_REVIEW_ONLY".equals(rs.getString("review_state"))) blockers.add(Blocker.REVIEW_NOT_APPROVED);
        check(rs, "reviewer_current", false, Blocker.APPROVER_NOT_CURRENTLY_AUTHORIZED, blockers);
        Long expected = rs.getObject("expected_resource_version", Long.class);
        Long version = rs.getObject("chat_version", Long.class);
        if (expected == null || version == null || expected < 0 || version < 0) unknown(blockers);
        else if (!expected.equals(version)) blockers.add(Blocker.RESOURCE_CHANGED);
        check(rs, "archived", false, Blocker.CHAT_NOT_ARCHIVED, blockers);
        if (!"AVAILABLE".equals(rs.getString("erasure_state"))) blockers.add(Blocker.CHAT_NOT_AVAILABLE);
        check(rs, "held_v86", true, Blocker.LEGAL_HOLD_V86, blockers);
        check(rs, "held_v105", true, Blocker.LEGAL_HOLD_V105, blockers);
        check(rs, "active_ai", true, Blocker.ACTIVE_AI_OPERATION, blockers);
        check(rs, "oversized", true, Blocker.ATOMIC_LIMIT_EXCEEDED, blockers);
        check(rs, "existing_operation", true, Blocker.EXISTING_V86_OPERATION, blockers);
        check(rs, "chat_allowed", false, Blocker.POLICY_NOT_ALLOWED, blockers);
        Optional<Binding> binding = Optional.empty();
        if (blockers.isEmpty()) {
            UUID reviewer = rs.getObject("reviewer_id", UUID.class);
            Long caseVersion = rs.getObject("case_version", Long.class);
            Long reviewVersion = rs.getObject("review_version", Long.class);
            Long holdRevision = rs.getObject("hold_revision", Long.class);
            Long reviewerVersion = rs.getObject("reviewer_token_version", Long.class);
            Long ownerVersion = rs.getObject("owner_token_version", Long.class);
            Long orgVersion = rs.getObject("org_auth_version", Long.class);
            Long policyVersion = rs.getObject("policy_version", Long.class);
            String digest = rs.getString("request_digest");
            UUID request = rs.getObject("id", UUID.class), org = rs.getObject("organization_id", UUID.class);
            UUID owner = rs.getObject("requested_by_user_id", UUID.class), chat = rs.getObject("resource_id", UUID.class);
            if (reviewer == null || request == null || org == null || owner == null || chat == null
                || reviewer.equals(owner) || caseVersion == null || reviewVersion == null || holdRevision == null
                || reviewerVersion == null || ownerVersion == null || orgVersion == null || policyVersion == null
                || caseVersion < 0 || reviewVersion < 0 || holdRevision < 0
                || reviewerVersion < 0 || ownerVersion < 0 || orgVersion < 0 || policyVersion < 0
                || digest == null || !digest.matches("[a-f0-9]{64}")) {
                unknown(blockers);
            } else {
                binding = Optional.of(new Binding(request, org, owner, chat, caseVersion,
                    Objects.requireNonNull(version), reviewVersion, holdRevision, reviewer, reviewerVersion,
                    ownerVersion, orgVersion, policyVersion, digest));
            }
        }
        blockers.add(Blocker.VERIFIED_IDENTITY_REQUIRED);
        blockers.add(Blocker.INDEPENDENT_EXECUTION_AUTHORITY_REQUIRED);
        blockers.add(Blocker.FROZEN_MANIFEST_REQUIRED);
        blockers.add(Blocker.UNIFIED_DB_EXECUTION_GATE_REQUIRED);
        return new Assessment(blockers, binding);
    }

    private static void check(ResultSet rs, String column, boolean blockingValue,
                              Blocker blocker, List<Blocker> blockers) throws SQLException {
        Boolean value = rs.getObject(column, Boolean.class);
        if (value == null) unknown(blockers);
        else if (value == blockingValue) blockers.add(blocker);
    }

    private static void unknown(List<Blocker> blockers) {
        if (!blockers.contains(Blocker.UNKNOWN_EVIDENCE)) blockers.add(Blocker.UNKNOWN_EVIDENCE);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Заявка не найдена.");
    }
}
