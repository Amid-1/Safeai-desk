package ru.safeai.gateway.erasure;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only projection of committed events; not an unread-delivery ledger. */
@Service
public class ErasureNotifications {
    private final JdbcTemplate jdbc;
    private final ErasureWorkspaceReads workspace;
    private final ErasureReviewService reviews;

    public ErasureNotifications(JdbcTemplate jdbc, ErasureWorkspaceReads workspace, ErasureReviewService reviews) {
        this.jdbc = jdbc;
        this.workspace = workspace;
        this.reviews = reviews;
    }

    public record Event(UUID eventId, UUID requestId, String eventCode, Instant createdAt, String audience) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Feed(boolean canRequest, Long registryCount, Long reviewCount,
                       List<Event> personal, List<Event> review) { }

    @Transactional
    public Feed feed(SafeAiUserPrincipal actor) {
        // Separate proxied service joins this transaction; live roles retain their FOR SHARE locks.
        var cap = workspace.capabilities(actor);
        Long registryCount = null;
        Long reviewCount = null;
        if (cap.platform()) {
            registryCount = jdbc.queryForObject("""
                    SELECT count(*) FROM public.erasure_requests_v103 r
                    LEFT JOIN public.erasure_reviews_v105 v
                      ON v.request_id = r.id AND v.organization_id = r.organization_id
                    WHERE r.state = 'SUBMITTED' AND (v.request_id IS NULL OR v.review_state = 'PENDING')
                    """, Long.class);
        } else if (cap.tenantRegistry()) {
            registryCount = jdbc.queryForObject("""
                    SELECT count(*) FROM public.erasure_requests_v103 r
                    WHERE r.organization_id = ? AND r.state = 'SUBMITTED'
                    """, Long.class, actor.getOrganizationId());
        }
        List<Event> personal = List.of();
        List<Event> review = List.of();
        if (cap.personalRequests() && cap.approvalsEnabled()) {
            personal = jdbc.query("""
                    SELECT e.id, e.request_id, e.event_code, e.created_at
                    FROM public.erasure_review_events_v105 e
                    JOIN public.erasure_reviews_v105 v
                      ON v.request_id = e.request_id AND v.organization_id = e.organization_id
                    WHERE v.organization_id = ? AND v.requester_user_id = ?
                      AND e.event_code IN ('APPROVED_REVIEW_ONLY','REJECTED','NEEDS_INFORMATION')
                    ORDER BY e.created_at DESC, e.id DESC LIMIT 20
                    """, (rs, i) -> new Event(rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class),
                    rs.getString("event_code"), rs.getTimestamp("created_at").toInstant(), "PERSONAL"),
                    actor.getOrganizationId(), actor.getId());
        }
        // LEGAL alone is not a grant to decide a case. Never notify reviewers about their own case.
        if (cap.reviewQueue() && reviews.access(actor).reviewer()) {
            reviewCount = jdbc.queryForObject("""
                    SELECT count(*) FROM public.erasure_reviews_v105 v
                    WHERE v.organization_id = ? AND v.requester_user_id <> ? AND v.review_state = 'PENDING'
                      AND EXISTS (SELECT 1 FROM public.erasure_review_authorities_v105 a
                        WHERE a.organization_id = v.organization_id AND a.user_id = ? AND a.authority = 'REVIEW' AND a.enabled)
                    """, Long.class, actor.getOrganizationId(), actor.getId(), actor.getId());
            review = jdbc.query("""
                    SELECT e.id, e.request_id, e.event_code, e.created_at
                    FROM public.erasure_review_events_v105 e
                    JOIN public.erasure_reviews_v105 v
                      ON v.request_id = e.request_id AND v.organization_id = e.organization_id
                    WHERE v.organization_id = ? AND v.requester_user_id <> ? AND v.review_state = 'PENDING'
                      AND EXISTS (SELECT 1 FROM public.erasure_review_authorities_v105 a
                        WHERE a.organization_id = v.organization_id AND a.user_id = ? AND a.authority = 'REVIEW' AND a.enabled)
                      AND e.review_version = v.review_version
                      AND e.event_code IN ('SUBMITTED','INFORMATION_PROVIDED')
                    ORDER BY e.created_at DESC, e.id DESC LIMIT 20
                    """, (rs, i) -> new Event(rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class),
                    rs.getString("event_code"), rs.getTimestamp("created_at").toInstant(), "REVIEW"),
                    actor.getOrganizationId(), actor.getId(), actor.getId());
        }
        return new Feed(cap.personalRequests(), registryCount, reviewCount, List.copyOf(personal), List.copyOf(review));
    }
}
