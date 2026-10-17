package ru.safeai.gateway.audit.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.audit.dto.AuditActorDirectoryResponse;
import ru.safeai.gateway.audit.dto.AuditTargetOrganizationDirectoryResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only historical directories.
 *
 * <p>The unfiltered path selects distinct narrow IDs using V27 btree indexes,
 * then fetches just one most recent row per ID through LATERAL. The filtered
 * path retains the previous historic-email and substring matching semantics.
 * A five-second transaction timeout at the service boundary protects the pool
 * from unbounded scans; indexes do not make arbitrary infix searches free.</p>
 */
@Repository
@RequiredArgsConstructor
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class AuditDirectoryQueryRepository {

    private static final RowMapper<AuditTargetOrganizationDirectoryResponse>
            TARGET_ORGANIZATION_ROW_MAPPER = (rs, ignored) ->
            new AuditTargetOrganizationDirectoryResponse(
                    rs.getObject("target_organization_id", UUID.class),
                    rs.getString("target_organization_name")
            );

    private static final RowMapper<AuditActorDirectoryResponse>
            ACTOR_ROW_MAPPER = (rs, ignored) ->
            new AuditActorDirectoryResponse(
                    rs.getObject("actor_user_id", UUID.class),
                    rs.getObject("actor_organization_id", UUID.class),
                    rs.getString("actor_email"),
                    rs.getString("actor_display_name")
            );

    private final JdbcTemplate jdbcTemplate;

    public List<AuditTargetOrganizationDirectoryResponse> findTargetOrganizations(
            String normalizedQuery, int limit
    ) {
        validateLimit(limit);
        if (normalizedQuery == null) {
            return jdbcTemplate.query("""
                    select latest.target_organization_id,
                           coalesce(latest.target_organization_name, o.name) as target_organization_name
                    from (select distinct organization_id from public.audit_events) ids
                    join lateral (
                        select e.organization_id as target_organization_id,
                               e.target_organization_name, e.created_at as last_seen_at,
                               e.id as last_seen_id
                        from public.audit_events e
                        where e.organization_id = ids.organization_id
                        order by e.created_at desc, e.id desc
                        limit 1
                    ) latest on true
                    left join public.organizations o on o.id = latest.target_organization_id
                    order by latest.last_seen_at desc, latest.last_seen_id desc,
                             lower(coalesce(latest.target_organization_name, o.name, '')),
                             latest.target_organization_id
                    limit ?
                    """, TARGET_ORGANIZATION_ROW_MAPPER, limit);
        }

        String sql = """
                select latest.target_organization_id,
                       coalesce(latest.target_organization_name, organization.name)
                           as target_organization_name
                from (
                    select distinct on (e.organization_id)
                           e.organization_id as target_organization_id,
                           e.target_organization_name,
                           e.created_at as last_seen_at, e.id as last_seen_id
                    from public.audit_events e
                    order by e.organization_id, e.created_at desc, e.id desc
                ) latest
                left join public.organizations organization
                  on organization.id = latest.target_organization_id
                where position(? in lower(coalesce(
                      latest.target_organization_name, organization.name, ''))) > 0
                   or position(? in lower(cast(latest.target_organization_id as text))) > 0
                order by latest.last_seen_at desc, latest.last_seen_id desc,
                         lower(coalesce(latest.target_organization_name, organization.name, '')),
                         latest.target_organization_id
                limit ?
                """;
        return jdbcTemplate.query(sql, TARGET_ORGANIZATION_ROW_MAPPER,
                normalizedQuery, normalizedQuery, limit);
    }

    public List<AuditActorDirectoryResponse> findActors(
            UUID enforcedTargetOrganizationId, String normalizedQuery, int limit
    ) {
        validateLimit(limit);
        if (normalizedQuery == null) {
            return findLatestActors(enforcedTargetOrganizationId, limit);
        }

        // A matching historical snapshot (not only the latest email) is
        // intentionally searchable. Keep the previous result semantics.
        StringBuilder sql = new StringBuilder("""
                select directory.actor_user_id,
                       directory.actor_organization_id,
                       directory.actor_email,
                       directory.actor_display_name
                from (
                    select distinct on (e.actor_user_id)
                           e.actor_user_id, e.actor_organization_id,
                           e.actor_email, e.actor_display_name,
                           e.created_at as last_seen_at,
                           e.id as last_seen_id
                    from public.audit_events e
                    where e.actor_user_id is not null
                """);
        List<Object> args = new ArrayList<>();
        if (enforcedTargetOrganizationId != null) {
            sql.append(" and e.organization_id = ?\n");
            args.add(enforcedTargetOrganizationId);
        }
        sql.append("""
                  and (
                      position(? in lower(coalesce(e.actor_email, ''))) > 0
                      or position(? in lower(coalesce(e.actor_display_name, ''))) > 0
                      or position(? in lower(cast(e.actor_user_id as text))) > 0
                      or position(? in lower(coalesce(cast(e.actor_organization_id as text), ''))) > 0
                  )
                order by e.actor_user_id, e.created_at desc, e.id desc
                ) directory
                order by directory.last_seen_at desc, directory.last_seen_id desc,
                         lower(coalesce(directory.actor_email, '')),
                         lower(coalesce(directory.actor_display_name, '')),
                         directory.actor_user_id
                limit ?
                """);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), ACTOR_ROW_MAPPER, args.toArray());
    }

    private List<AuditActorDirectoryResponse> findLatestActors(
            UUID organizationId, int limit
    ) {
        String tenantPredicate = organizationId == null
                ? "" : " and e.organization_id = ?";
        String sql = """
                select latest_snapshot.actor_user_id, latest_snapshot.actor_organization_id,
                       latest_snapshot.actor_email, latest_snapshot.actor_display_name
                from (
                    select distinct e.actor_user_id
                    from public.audit_events e
                    where e.actor_user_id is not null
                """ + tenantPredicate + """
                ) ids
                join lateral (
                    select e.actor_user_id, e.actor_organization_id,
                           e.actor_email, e.actor_display_name,
                           e.created_at as last_seen_at, e.id as last_seen_id
                    from public.audit_events e
                    where e.actor_user_id = ids.actor_user_id
                """ + tenantPredicate + """
                    order by e.created_at desc, e.id desc
                    limit 1
                ) latest_snapshot on true
                order by latest_snapshot.last_seen_at desc, latest_snapshot.last_seen_id desc,
                         lower(coalesce(latest_snapshot.actor_email, '')),
                         lower(coalesce(latest_snapshot.actor_display_name, '')),
                         latest_snapshot.actor_user_id
                limit ?
                """;
        if (organizationId == null) {
            return jdbcTemplate.query(sql, ACTOR_ROW_MAPPER, limit);
        }
        return jdbcTemplate.query(sql, ACTOR_ROW_MAPPER,
                organizationId, organizationId, limit);
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("audit directory limit должен быть 1..50");
        }
    }
}
