package ru.safeai.gateway.erasure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureReviewTypes.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * R1b: non-destructive decisions for submitted V103 requests.
 * An APPROVED_REVIEW_ONLY decision is NOT an execution permit, signed manifest,
 * identity verification, or authority for any existing destructive endpoint.
 */
@Service
public class ErasureReviewService {
    private static final Set<String> DECISIONS = Set.of(
            "APPROVED_REVIEW_ONLY", "REJECTED", "NEEDS_INFORMATION");
    private static final Set<String> DECISION_REASONS = Set.of(
            "VERIFIED_OWNER_REQUEST", "VALID_TENANT_REQUEST", "INSUFFICIENT_AUTHORITY",
            "INSUFFICIENT_INFORMATION", "RETENTION_REVIEW_REQUIRED", "SECURITY_REVIEW_REQUIRED");
    private static final Set<String> HOLD_REASONS = Set.of(
            "LEGAL_REQUEST", "MANDATORY_RETENTION", "SECURITY_REVIEW");

    private final JdbcTemplate jdbc;
    private final ErasureCaseAuthority authority;
    private final ErasureReviewActorProof actorProof;

    public ErasureReviewService(JdbcTemplate jdbc, ErasureCaseAuthority authority,
            ErasureReviewActorProof actorProof) {
        this.jdbc = jdbc;
        this.authority = authority;
        this.actorProof = actorProof;
    }

    @Transactional
    public ReviewSummary platformSummary(SafeAiUserPrincipal actor) {
        if (!authority.platformSuperAdmin(actor)) throw forbidden();
        return jdbc.queryForObject("""
                SELECT count(*)::bigint,
                  count(*) FILTER (WHERE review_state='PENDING')::bigint,
                  (SELECT count(*)::bigint FROM public.erasure_review_holds_v105
                    WHERE released_at IS NULL),
                  count(*) FILTER (WHERE review_state='NEEDS_INFORMATION')::bigint
                FROM public.erasure_reviews_v105
                """, (rs,i)->new ReviewSummary(rs.getLong(1),rs.getLong(2),
                    rs.getLong(3),rs.getLong(4)));
    }

    @Transactional
    public ReviewAccess access(SafeAiUserPrincipal actor) {
        authority.requireActor(actor);
        return new ReviewAccess(hasGrant(actor,"REVIEW"), hasGrant(actor,"LEGAL"));
    }

    @Transactional
    public ReviewPage queue(SafeAiUserPrincipal actor) {
        requireAnyGrant(actor);
        return new ReviewPage(jdbc.query(BASE_SQL + """
                WHERE v.organization_id = ?
                ORDER BY v.updated_at DESC,v.request_id DESC LIMIT 100
                """, this::map, actor.getOrganizationId()));
    }

    @Transactional
    public ReviewView readForReviewer(UUID requestId, SafeAiUserPrincipal actor) {
        requireAnyGrant(actor);
        return one(requestId, actor.getOrganizationId());
    }

    @Transactional
    public ReviewView readForRequester(UUID requestId, SafeAiUserPrincipal actor) {
        authority.requireActor(actor);
        List<ReviewView> results = jdbc.query(BASE_SQL + """
                WHERE v.request_id = ? AND v.organization_id = ?
                  AND v.requester_user_id = ?
                """, this::map, requestId, actor.getOrganizationId(), actor.getId());
        if (results.isEmpty()) throw notFound();
        return results.getFirst();
    }

    @Transactional
    public ReviewHistory history(UUID requestId, SafeAiUserPrincipal actor, boolean reviewer) {
        if (reviewer) requireAnyGrant(actor);
        else readForRequester(requestId, actor);
        UUID org = actor.getOrganizationId();
        if (reviewer) one(requestId, org);
        return new ReviewHistory(jdbc.query("""
                SELECT e.review_version,e.event_code,e.reason_code,e.created_at
                FROM public.erasure_review_events_v105 e
                WHERE e.request_id=? AND e.organization_id=?
                ORDER BY e.review_version,e.created_at LIMIT 100
                """, (rs, i) -> new ReviewEvent(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getTimestamp(4).toInstant()), requestId, org));
    }

    @Transactional
    public ReviewView decide(UUID requestId, long expected, String key,
            DecisionBody body, SafeAiUserPrincipal actor) {
        ErasureCaseRules.key(key);
        if (body == null || body.decision() == null || body.reasonCode() == null
                || !DECISIONS.contains(body.decision())
                || !DECISION_REASONS.contains(body.reasonCode())
                || !validDecisionReason(body.decision(), body.reasonCode())) throw invalid();
        requireGrant(actor, "REVIEW");
        ReviewView old = locked(requestId, actor.getOrganizationId());
        if (isDuplicate(requestId, actor, key, body.decision(), body.reasonCode())) return old;
        if (old.requesterUserId().equals(actor.getId())) throw forbidden();
        if (old.version() != expected || !"PENDING".equals(old.reviewState())
                || old.holdActive() || !"SUBMITTED".equals(old.baseState())) throw changed();
        actorProof.bind(actor.getId(), actor.getOrganizationId(), requestId,
                body.decision(), expected + 1, key);
        int n = jdbc.update("""
                UPDATE public.erasure_reviews_v105
                SET review_state=?,review_version=review_version+1,
                    updated_by_user_id=?,updated_at=transaction_timestamp(),
                    last_event_code=?,last_key=?,reason_code=?
                WHERE request_id=? AND organization_id=? AND review_version=?
                  AND review_state='PENDING'
                """, body.decision(), actor.getId(), body.decision(), key,
                body.reasonCode(), requestId, actor.getOrganizationId(), expected);
        if (n != 1) throw changed();
        return one(requestId, actor.getOrganizationId());
    }

    @Transactional
    public ReviewView provideInformation(UUID requestId, long expected, String key,
            SafeAiUserPrincipal actor) {
        ErasureCaseRules.key(key);
        authority.requireActor(actor);
        ReviewView old = locked(requestId, actor.getOrganizationId());
        // Match the requester-only read contract before consulting the action journal.
        if (!old.requesterUserId().equals(actor.getId())) throw notFound();
        if (isDuplicate(requestId, actor, key, "INFORMATION_PROVIDED", null)) return old;
        if (old.version() != expected || !"NEEDS_INFORMATION".equals(old.reviewState())
                || !"SUBMITTED".equals(old.baseState()) || old.holdActive()) throw changed();
        actorProof.bind(actor.getId(), actor.getOrganizationId(), requestId,
                "INFORMATION_PROVIDED", expected + 1, key);
        int updated = jdbc.update("""
                UPDATE public.erasure_reviews_v105
                SET review_state='PENDING',review_version=review_version+1,
                    updated_by_user_id=?,last_event_code='INFORMATION_PROVIDED',
                    last_key=?,reason_code=NULL,updated_at=transaction_timestamp()
                WHERE request_id=? AND organization_id=? AND review_version=?
                """, actor.getId(), key, requestId, actor.getOrganizationId(), expected);
        if (updated != 1) throw changed();
        return one(requestId, actor.getOrganizationId());
    }

    @Transactional
    public ReviewView hold(UUID requestId, long expected, String key,
            HoldBody body, SafeAiUserPrincipal actor) {
        ErasureCaseRules.key(key);
        if (body == null || body.reasonCode() == null || !HOLD_REASONS.contains(body.reasonCode())) throw invalid();
        requireGrant(actor, "LEGAL");
        ReviewView old = locked(requestId, actor.getOrganizationId());
        if (isDuplicate(requestId, actor, key, "HOLD_APPLIED", body.reasonCode())) return old;
        if (old.requesterUserId().equals(actor.getId())
                || old.version() != expected || old.holdActive()
                || !"SUBMITTED".equals(old.baseState())
                || "WITHDRAWN".equals(old.reviewState())
                || "REJECTED".equals(old.reviewState())) throw changed();
        actorProof.bind(actor.getId(), actor.getOrganizationId(), requestId,
                "HOLD_APPLIED", expected + 1, key);
        jdbc.update("""
                INSERT INTO public.erasure_review_holds_v105
                    (id,request_id,organization_id,reason_code,applied_by_user_id,apply_key)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), requestId, actor.getOrganizationId(),
                body.reasonCode(), actor.getId(), key);
        // The hold AFTER trigger advances the review version and journals the
        // matching HOLD_APPLIED event atomically. Never update it twice here.
        return one(requestId, actor.getOrganizationId());
    }

    @Transactional
    public ReviewView releaseHold(UUID requestId, long expected, String key,
            SafeAiUserPrincipal actor) {
        ErasureCaseRules.key(key);
        requireGrant(actor, "LEGAL");
        ReviewView old = locked(requestId, actor.getOrganizationId());
        if (isDuplicate(requestId, actor, key, "HOLD_RELEASED", null)) return old;
        if (old.requesterUserId().equals(actor.getId()) || old.version() != expected
                || !old.holdActive() || !"SUBMITTED".equals(old.baseState())) throw changed();
        actorProof.bind(actor.getId(), actor.getOrganizationId(), requestId,
                "HOLD_RELEASED", expected + 1, key);
        int updated = jdbc.update("""
                UPDATE public.erasure_review_holds_v105
                SET released_at=transaction_timestamp(),released_by_user_id=?,release_key=?
                WHERE request_id=? AND organization_id=? AND released_at IS NULL
                  AND applied_by_user_id<>?
                """, actor.getId(), key, requestId, actor.getOrganizationId(), actor.getId());
        if (updated != 1) throw new ForbiddenOperationException(
                "Снять удержание должен другой уполномоченный сотрудник.");
        // The hold AFTER trigger records HOLD_RELEASED in the review and journal.
        return one(requestId, actor.getOrganizationId());
    }

    private static boolean validDecisionReason(String action, String reason) {
        return switch (action) {
            case "APPROVED_REVIEW_ONLY" -> Set.of(
                    "VERIFIED_OWNER_REQUEST", "VALID_TENANT_REQUEST").contains(reason);
            case "REJECTED" -> Set.of("INSUFFICIENT_AUTHORITY", "INSUFFICIENT_INFORMATION",
                    "RETENTION_REVIEW_REQUIRED", "SECURITY_REVIEW_REQUIRED").contains(reason);
            case "NEEDS_INFORMATION" -> Set.of("INSUFFICIENT_INFORMATION",
                    "RETENTION_REVIEW_REQUIRED", "SECURITY_REVIEW_REQUIRED").contains(reason);
            default -> false;
        };
    }

    private boolean hasGrant(SafeAiUserPrincipal actor, String purpose) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.erasure_review_authorities_v105 a
                WHERE a.organization_id=? AND a.user_id=? AND a.authority=? AND a.enabled)
                """, Boolean.class, actor.getOrganizationId(), actor.getId(), purpose));
    }

    private void requireAnyGrant(SafeAiUserPrincipal actor) {
        authority.requireActor(actor);
        if (!hasGrant(actor,"REVIEW") && !hasGrant(actor,"LEGAL")) throw forbidden();
    }

    private void requireGrant(SafeAiUserPrincipal actor, String purpose) {
        authority.requireActor(actor);
        if (!hasGrant(actor,purpose)) throw forbidden();
    }

    private ReviewView locked(UUID id, UUID org) {
        // Lock base case first: same order as V103 withdrawal (base -> review).
        List<UUID> base = jdbc.query("""
                SELECT id FROM public.erasure_requests_v103
                WHERE id=? AND organization_id=? FOR SHARE
                """, (rs,i) -> rs.getObject(1,UUID.class), id, org);
        if (base.isEmpty()) throw notFound();
        List<UUID> review = jdbc.query("""
                SELECT request_id FROM public.erasure_reviews_v105
                WHERE request_id=? AND organization_id=? FOR UPDATE
                """, (rs,i) -> rs.getObject(1,UUID.class), id, org);
        if (review.isEmpty()) throw notFound();
        return one(id, org);
    }

    private ReviewView one(UUID id, UUID org) {
        List<ReviewView> results = jdbc.query(BASE_SQL +
                "WHERE v.request_id=? AND v.organization_id=?", this::map, id, org);
        if (results.size()!=1) throw notFound();
        return results.getFirst();
    }

    private boolean isDuplicate(UUID id, SafeAiUserPrincipal actor,
            String key, String action, String reason) {
        List<UUID> pastActors = jdbc.query("""
                SELECT actor_user_id FROM public.erasure_review_events_v105
                WHERE request_id=? AND organization_id=? AND action_key=?
                  AND event_code=? AND reason_code IS NOT DISTINCT FROM ?
                """, (rs,i)->rs.getObject(1,UUID.class),
                id, actor.getOrganizationId(), key, action, reason);
        if (!pastActors.isEmpty() && pastActors.getFirst().equals(actor.getId())) return true;
        Boolean used = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.erasure_review_events_v105
                  WHERE request_id=? AND organization_id=? AND action_key=?)
                """, Boolean.class, id, actor.getOrganizationId(), key);
        if (Boolean.TRUE.equals(used)) throw new ConflictException(
                "Ключ повтора уже используется другим действием.");
        return false;
    }

    private static final String BASE_SQL = """
            SELECT v.request_id,v.requester_user_id,r.resource_type,r.resource_id,
                   r.operation,r.scope,r.state AS base_state,v.review_state,
                   v.review_version,v.reason_code,v.created_at,v.updated_at,
                   EXISTS (SELECT 1 FROM public.erasure_review_holds_v105 h
                     WHERE h.request_id=v.request_id AND h.released_at IS NULL) AS hold_active
            FROM public.erasure_reviews_v105 v
            JOIN public.erasure_requests_v103 r ON r.id=v.request_id
                AND r.organization_id=v.organization_id
            """;

    private ReviewView map(ResultSet rs, int index) throws SQLException {
        return new ReviewView(rs.getObject("request_id",UUID.class),
                rs.getObject("requester_user_id",UUID.class),
                rs.getString("resource_type"),rs.getObject("resource_id",UUID.class),
                rs.getString("operation"),rs.getString("scope"),
                rs.getString("base_state"),rs.getString("review_state"),
                rs.getLong("review_version"),rs.getBoolean("hold_active"),
                rs.getString("reason_code"),rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),false,false);
    }

    private static BadRequestException invalid() {
        return new BadRequestException("Некорректное решение или причина.");
    }
    private static ConflictException changed() {
        return new ConflictException("Заявка изменилась. Обновите страницу.");
    }
    private static ForbiddenOperationException forbidden() {
        return new ForbiddenOperationException("Нет независимого полномочия для рассмотрения.");
    }
    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Заявка не найдена.");
    }
}
