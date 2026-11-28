package ru.safeai.gateway.erasure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ErasureWorkspaceReads {

    static final int PAGE_SIZE = 25;

    private static final Set<String> STATES = Set.of(
            "ALL",
            "ATTENTION",
            "HELD",
            "PENDING",
            "NEEDS_INFORMATION",
            "APPROVED_REVIEW_ONLY",
            "REJECTED",
            "WITHDRAWN",
            "SUBMITTED",
            "DRAFT"
    );

    private final JdbcTemplate jdbc;
    private final ErasureCaseAuthority authority;
    private final ErasureReviewService reviews;

    private final boolean requestsEnabled;
    private final boolean approvalsEnabled;

    public ErasureWorkspaceReads(
            JdbcTemplate jdbc,
            ErasureCaseAuthority authority,
            ErasureReviewService reviews,
            @Value("${safeai.erasure.requests.enabled:false}")
            boolean requestsEnabled,
            @Value("${safeai.erasure.approvals.enabled:false}")
            boolean approvalsEnabled
    ) {
        this.jdbc = jdbc;
        this.authority = authority;
        this.reviews = reviews;
        this.requestsEnabled = requestsEnabled;
        this.approvalsEnabled = approvalsEnabled;
    }

    public record Capabilities(
            boolean platform,
            boolean tenantRegistry,
            boolean personalRequests,
            boolean reviewQueue,
            boolean requestsEnabled,
            boolean approvalsEnabled
    ) {
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Item(
            UUID requestId,
            String organization,
            String requester,
            String resourceType,
            String state,
            boolean holdActive,
            Instant updatedAt, String intakeState, String reviewState, boolean reviewVisible
    ) {
    }

    // Empty selections must retain the exact wire contract, including items: [].
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Page(
            List<Item> items,
            int page,
            int pageSize,
            boolean hasNext
    ) {
    }

    @Transactional
    public Capabilities capabilities(SafeAiUserPrincipal actor) {
        return resolveCapabilitiesInTransaction(actor);
    }

    // Called only from transactional public entry points. Keep the role
    // locks and subsequent registry reads in the same transaction.
    private Capabilities resolveCapabilitiesInTransaction(SafeAiUserPrincipal actor) {
        List<String> roles = authority.liveRoles(actor);
        boolean platform = roles.contains("SUPER_ADMIN") && authority.platformSuperAdmin(actor);
        boolean tenant = roles.contains("ADMIN");
        boolean review = false;
        if (approvalsEnabled && (tenant || platform)) {
            var grant = reviews.access(actor);
            review = grant.reviewer() || grant.legal();
        }
        return new Capabilities(platform, tenant && requestsEnabled,
                requestsEnabled && !roles.contains("SUPER_ADMIN") && (tenant || roles.contains("USER")),
                review, requestsEnabled, approvalsEnabled);
    }

    /** Read projection: no resource identity, content, requester or destructive authority. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record RegistryDetail(UUID requestId, String organization, String resourceType,
            String intakeState, String reviewState, boolean reviewVisible, Boolean holdActive,
            Instant createdAt, Instant updatedAt, String executionState, boolean completionProven) { }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record RegistrySummary(String scope, long total, long registered, long withdrawn,
            Long drafts, Long reviewed, Long pending, Long needsInformation, Long held) { }

    public record HistoryItem(UUID requestId, String organization, String eventCode, Instant createdAt,
            long version, UUID eventId, String source) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record HistoryPage(List<HistoryItem> items, int page, int pageSize, boolean hasNext) { }

    private static final String REGISTERED_SCOPE = "r.state IN ('SUBMITTED', 'WITHDRAWN')";
    private static final String REVIEW_JOIN = " LEFT JOIN public.erasure_reviews_v105 v "
            + "ON v.request_id = r.id AND v.organization_id = r.organization_id ";
    private static final String HOLD_EXISTS = "EXISTS (SELECT 1 FROM public.erasure_review_holds_v105 h "
            + "WHERE h.request_id = r.id AND h.organization_id = r.organization_id AND h.released_at IS NULL)";

    private boolean registryPlatform(SafeAiUserPrincipal actor) {
        var cap = resolveCapabilitiesInTransaction(actor);
        if (!cap.platform() && !cap.tenantRegistry()) {
            throw new ForbiddenOperationException("Нет доступа к реестру.");
        }
        return cap.platform();
    }

    @Transactional
    public RegistrySummary registrySummary(SafeAiUserPrincipal actor) {
        boolean platform = registryPlatform(actor);
        if (platform) {
            return jdbc.query("""
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE r.state = 'SUBMITTED') AS registered,
                           count(*) FILTER (WHERE r.state = 'WITHDRAWN') AS withdrawn,
                           count(v.request_id) AS reviewed,
                           count(*) FILTER (WHERE v.review_state = 'PENDING') AS pending,
                           count(*) FILTER (WHERE v.review_state = 'NEEDS_INFORMATION') AS needs_information,
                           count(*) FILTER (WHERE
                    """ + HOLD_EXISTS + ") AS held FROM public.erasure_requests_v103 r "
                    + REVIEW_JOIN + " WHERE " + REGISTERED_SCOPE,
                    (rs, i) -> new RegistrySummary("PLATFORM", rs.getLong("total"),
                            rs.getLong("registered"), rs.getLong("withdrawn"), null,
                            rs.getLong("reviewed"), rs.getLong("pending"),
                            rs.getLong("needs_information"), rs.getLong("held"))).getFirst();
        }
        return jdbc.query("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE r.state = 'SUBMITTED') AS registered,
                       count(*) FILTER (WHERE r.state = 'WITHDRAWN') AS withdrawn,
                       count(*) FILTER (WHERE r.state = 'DRAFT') AS drafts
                FROM public.erasure_requests_v103 r WHERE r.organization_id = ?
                """, (rs, i) -> new RegistrySummary("TENANT", rs.getLong("total"),
                rs.getLong("registered"), rs.getLong("withdrawn"), rs.getLong("drafts"),
                null, null, null, null), actor.getOrganizationId()).getFirst();
    }

    @Transactional
    public RegistryDetail registryDetail(SafeAiUserPrincipal actor, UUID requestId) {
        boolean platform = registryPlatform(actor);
        String sql = "SELECT r.id, o.name AS organization, r.resource_type, r.state AS intake_state, "
                + (platform ? "v.review_state" : "CAST(NULL AS text)") + " AS review_state, "
                + (platform ? HOLD_EXISTS : "CAST(NULL AS boolean)") + " AS hold_active, "
                + "r.created_at, " + (platform ? "coalesce(v.updated_at, r.updated_at)" : "r.updated_at")
                + " AS workspace_updated_at FROM public.erasure_requests_v103 r "
                + "JOIN public.organizations o ON o.id = r.organization_id "
                + (platform ? REVIEW_JOIN : "") + " WHERE r.id = ? AND "
                + (platform ? REGISTERED_SCOPE : "r.organization_id = ?");
        Object[] args = platform ? new Object[]{requestId} : new Object[]{requestId, actor.getOrganizationId()};
        var rows = jdbc.query(sql, (rs, i) -> new RegistryDetail(rs.getObject("id", UUID.class),
                rs.getString("organization"), rs.getString("resource_type"), rs.getString("intake_state"),
                rs.getString("review_state"), platform, rs.getObject("hold_active", Boolean.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("workspace_updated_at").toInstant(),
                "NOT_AVAILABLE", false), args);
        if (rows.isEmpty()) {
            throw new ru.safeai.gateway.common.exception.ResourceNotFoundException("Заявка не найдена.");
        }
        return rows.getFirst();
    }

    @Transactional
    public HistoryPage history(SafeAiUserPrincipal actor, String query, int page) {
        var cap = resolveCapabilitiesInTransaction(actor);
        if (!cap.platform() && !cap.reviewQueue()) {
            throw new ForbiddenOperationException("Нет доступа к истории.");
        }
        if (query == null || query.length() > 160 || page < 0 || page > 100_000) {
            throw new BadRequestException("Некорректные параметры поиска.");
        }
        // V103 and V105 are separate append-only journals. The review event mirrored from
        // intake remains a distinct event; source and UUID disambiguate their versions.
        // Platform never sees cases still in DRAFT. Tenant reviewers see only reviewed cases.
        String sql = """
                WITH visible AS (
                    SELECT r.id, r.organization_id, o.name AS organization
                    FROM public.erasure_requests_v103 r
                    JOIN public.organizations o ON o.id = r.organization_id
                    WHERE
                """ + REGISTERED_SCOPE;
        List<Object> args = new ArrayList<>();
        if (!cap.platform()) {
            sql += " AND r.organization_id = ? AND EXISTS (SELECT 1 FROM public.erasure_reviews_v105 v "
                    + "WHERE v.request_id = r.id AND v.organization_id = r.organization_id) ";
            args.add(actor.getOrganizationId());
        }
        if (!query.isBlank()) {
            sql += " AND strpos(lower(r.id::text), lower(?)) > 0 ";
            args.add(query.strip());
        }
        sql += """
                ), events AS (
                    SELECT e.id AS event_id, e.request_id, v.organization, e.event_type AS event_code,
                           e.created_at, e.version AS event_version, 'INTAKE' AS event_source
                    FROM public.erasure_request_events_v103 e
                    JOIN visible v ON v.id = e.request_id AND v.organization_id = e.organization_id
                    UNION ALL
                    SELECT e.id AS event_id, e.request_id, v.organization, e.event_code,
                           e.created_at, e.review_version AS event_version, 'REVIEW' AS event_source
                    FROM public.erasure_review_events_v105 e
                    JOIN visible v ON v.id = e.request_id AND v.organization_id = e.organization_id
                )
                SELECT * FROM events
                ORDER BY created_at DESC, request_id DESC, event_source DESC, event_version DESC, event_id DESC
                LIMIT ? OFFSET ?
                """;
        args.add(PAGE_SIZE + 1);
        args.add((long) page * PAGE_SIZE);
        var rows = jdbc.query(sql, (rs, i) -> new HistoryItem(rs.getObject("request_id", UUID.class),
                rs.getString("organization"), rs.getString("event_code"), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("event_version"), rs.getObject("event_id", UUID.class), rs.getString("event_source")),
                args.toArray());
        return new HistoryPage(List.copyOf(rows.subList(0, Math.min(rows.size(), PAGE_SIZE))),
                page, PAGE_SIZE, rows.size() > PAGE_SIZE);
    }

    @Transactional
    public Page registry(
            SafeAiUserPrincipal actor,
            boolean reviewOnly,
            String state,
            String query,
            int page
    ) {
        var cap = resolveCapabilitiesInTransaction(actor);

        if (reviewOnly
                ? !cap.reviewQueue()
                : !cap.platform() && !cap.tenantRegistry()) {

            throw new ForbiddenOperationException(
                    "Нет доступа к реестру."
            );
        }

        if (state == null || !STATES.contains(state)) {
            throw new BadRequestException(
                    "Некорректные параметры поиска."
            );
        }

        validateSearch(query, page);

        // Платформенный реестр не раскрывает персональные
        // данные заявителей и содержимое объектов.
        //
        // Реестр организации сохраняет область доступа V103.
        //
        // Очередь согласований ограничена организацией
        // текущего пользователя, включая SUPER_ADMIN.

        boolean global = cap.platform() && !reviewOnly;
        boolean reviewMetadata = global || reviewOnly;

        String stateSql = reviewMetadata
                ? "coalesce(v.review_state, r.state)"
                : "r.state";

        String holdSql = reviewMetadata
                ? """
                  EXISTS (
                      SELECT 1
                      FROM public.erasure_review_holds_v105 h
                      WHERE h.request_id = r.id
                        AND h.organization_id = r.organization_id
                        AND h.released_at IS NULL
                  )
                  """
                : "false";

        String requesterSql = global
                ? "CAST(NULL AS text)"
                : "coalesce(nullif(u.full_name, ''), u.email)";

        String updatedAtSql = reviewMetadata
                ? "coalesce(v.updated_at, r.updated_at)"
                : "r.updated_at";

        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        StringBuilder sql = new StringBuilder();

        sql.append("SELECT r.id, ");
        sql.append("o.name AS organization, ");
        sql.append(requesterSql).append(" AS requester, ");
        sql.append("r.resource_type, ");
        sql.append(stateSql).append(" AS state, r.state AS intake_state, ");
        sql.append(reviewMetadata ? "v.review_state" : "CAST(NULL AS text)").append(" AS review_state, ");
        sql.append(holdSql).append(" AS hold_active, ");
        sql.append(updatedAtSql)
                .append(" AS workspace_updated_at ");

        sql.append("""
                FROM public.erasure_requests_v103 r
                JOIN public.organizations o
                    ON o.id = r.organization_id
                """);

        if (reviewMetadata) {
            sql.append("""
                    LEFT JOIN public.erasure_reviews_v105 v
                        ON v.request_id = r.id
                        AND v.organization_id = r.organization_id
                    """);

            conditions.add(
                    "r.state IN ('SUBMITTED', 'WITHDRAWN')"
            );
        }

        if (!global) {
            sql.append("""
                    LEFT JOIN public.users u
                        ON u.id = r.requested_by_user_id
                        AND u.organization_id = r.organization_id
                    """);

            conditions.add("r.organization_id = ?");
            args.add(actor.getOrganizationId());
        }

        if (reviewOnly) {
            conditions.add("v.request_id IS NOT NULL");
        }

        if ("HELD".equals(state)) {

            conditions.add(holdSql);

        } else if ("ATTENTION".equals(state)) {

            conditions.add(
                    "(" + stateSql
                            + " IN ('PENDING', 'NEEDS_INFORMATION')"
                            + " OR " + holdSql + ")"
            );

        } else if (!"ALL".equals(state)) {

            conditions.add(stateSql + " = ?");
            args.add(state);
        }

        if (!query.isBlank()) {

            String search = query.strip();

            StringBuilder searchCondition = new StringBuilder(
                    """
                    (
                        strpos(lower(r.id::text), lower(?)) > 0
                        OR strpos(lower(o.name), lower(?)) > 0
                    """
            );

            args.add(search);
            args.add(search);

            if (!global) {
                searchCondition.append("""
                        OR strpos(
                            lower(coalesce(u.email, '')),
                            lower(?)
                        ) > 0
                        OR strpos(
                            lower(coalesce(u.full_name, '')),
                            lower(?)
                        ) > 0
                        """);

                args.add(search);
                args.add(search);
            }

            searchCondition.append(")");

            conditions.add(searchCondition.toString());
        }

        sql.append(whereClause(conditions));

        sql.append("""
                ORDER BY
                    workspace_updated_at DESC,
                    r.id DESC
                LIMIT ? OFFSET ?
                """);

        args.add(PAGE_SIZE + 1);
        args.add((long) page * PAGE_SIZE);

        var rows = jdbc.query(
                sql.toString(),
                (rs, i) -> new Item(
                        rs.getObject("id", UUID.class),
                        rs.getString("organization"),
                        rs.getString("requester"),
                        rs.getString("resource_type"),
                        rs.getString("state"),
                        rs.getBoolean("hold_active"),
                        rs.getTimestamp(
                                "workspace_updated_at"
                        ).toInstant(), rs.getString("intake_state"),
                        rs.getString("review_state"), reviewMetadata
                ),
                args.toArray()
        );

        return new Page(
                List.copyOf(
                        rows.subList(
                                0,
                                Math.min(rows.size(), PAGE_SIZE)
                        )
                ),
                page,
                PAGE_SIZE,
                rows.size() > PAGE_SIZE
        );
    }

    /**
     * Проверяет параметры поиска и пагинации.
     */
    private static void validateSearch(String query, int page) {

        if (query == null
                || query.length() > 160
                || page < 0
                || page > 100_000) {

            throw new BadRequestException(
                    "Некорректные параметры поиска."
            );
        }
    }

    /**
     * Формирует WHERE только при наличии условий.
     */
    private static String whereClause(List<String> conditions) {

        if (conditions.isEmpty()) {
            return "";
        }

        return " WHERE "
                + String.join(" AND ", conditions)
                + " ";
    }
}
