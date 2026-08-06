
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

@Repository
@RequiredArgsConstructor
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
public class AuditDirectoryQueryRepository {

    private static final RowMapper<
            AuditTargetOrganizationDirectoryResponse
            > TARGET_ORGANIZATION_ROW_MAPPER =
            (resultSet, rowNumber) ->
                    new AuditTargetOrganizationDirectoryResponse(
                            resultSet.getObject(
                                    "target_organization_id",
                                    UUID.class
                            ),
                            resultSet.getString(
                                    "target_organization_name"
                            )
                    );

    private static final RowMapper<
            AuditActorDirectoryResponse
            > ACTOR_ROW_MAPPER =
            (resultSet, rowNumber) ->
                    new AuditActorDirectoryResponse(
                            resultSet.getObject(
                                    "actor_user_id",
                                    UUID.class
                            ),
                            resultSet.getObject(
                                    "actor_organization_id",
                                    UUID.class
                            ),
                            resultSet.getString(
                                    "actor_email"
                            ),
                            resultSet.getString(
                                    "actor_display_name"
                            )
                    );

    private final JdbcTemplate jdbcTemplate;

    public List<AuditTargetOrganizationDirectoryResponse>
    findTargetOrganizations(
            String normalizedQuery,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                select directory.target_organization_id,
                       directory.target_organization_name
                from (
                    select audit_event.organization_id
                               as target_organization_id,
                           max(organization.name)
                               as target_organization_name,
                           max(audit_event.created_at)
                               as last_seen_at
                    from public.audit_events as audit_event
                    left join public.organizations as organization
                      on organization.id = audit_event.organization_id
                    where 1 = 1
                """);

        List<Object> arguments = new ArrayList<>();

        if (normalizedQuery != null) {
            sql.append("""
                      and (
                          position(
                              ? in lower(
                                  coalesce(organization.name, '')
                              )
                          ) > 0
                          or position(
                              ? in lower(
                                  cast(audit_event.organization_id as text)
                              )
                          ) > 0
                      )
                    """);

            arguments.add(normalizedQuery);
            arguments.add(normalizedQuery);
        }

        sql.append("""
                    group by audit_event.organization_id
                ) as directory
                order by directory.last_seen_at desc,
                         lower(
                             coalesce(
                                 directory.target_organization_name,
                                 ''
                             )
                         ),
                         directory.target_organization_id
                limit ?
                """);

        arguments.add(limit);

        return jdbcTemplate.query(
                sql.toString(),
                TARGET_ORGANIZATION_ROW_MAPPER,
                arguments.toArray()
        );
    }

    public List<AuditActorDirectoryResponse> findActors(
            UUID enforcedTargetOrganizationId,
            String normalizedQuery,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                select directory.actor_user_id,
                       directory.actor_organization_id,
                       directory.actor_email,
                       directory.actor_display_name
                from (
                    select audit_event.actor_user_id,
                           audit_event.actor_organization_id,
                           audit_event.actor_email,
                           audit_event.actor_display_name,
                           max(audit_event.created_at)
                               as last_seen_at
                    from public.audit_events as audit_event
                    where (
                        audit_event.actor_user_id is not null
                        or audit_event.actor_email is not null
                    )
                """);

        List<Object> arguments = new ArrayList<>();

        if (enforcedTargetOrganizationId != null) {
            sql.append("""
                      and audit_event.organization_id = ?
                    """);

            arguments.add(enforcedTargetOrganizationId);
        }

        if (normalizedQuery != null) {
            sql.append("""
                      and (
                          position(
                              ? in lower(
                                  coalesce(audit_event.actor_email, '')
                              )
                          ) > 0
                          or position(
                              ? in lower(
                                  coalesce(
                                      audit_event.actor_display_name,
                                      ''
                                  )
                              )
                          ) > 0
                          or position(
                              ? in lower(
                                  coalesce(
                                      cast(
                                          audit_event.actor_user_id
                                          as text
                                      ),
                                      ''
                                  )
                              )
                          ) > 0
                          or position(
                              ? in lower(
                                  coalesce(
                                      cast(
                                          audit_event.actor_organization_id
                                          as text
                                      ),
                                      ''
                                  )
                              )
                          ) > 0
                      )
                    """);

            arguments.add(normalizedQuery);
            arguments.add(normalizedQuery);
            arguments.add(normalizedQuery);
            arguments.add(normalizedQuery);
        }

        sql.append("""
                    group by audit_event.actor_user_id,
                             audit_event.actor_organization_id,
                             audit_event.actor_email,
                             audit_event.actor_display_name
                ) as directory
                order by directory.last_seen_at desc,
                         lower(coalesce(directory.actor_email, '')),
                         lower(
                             coalesce(
                                 directory.actor_display_name,
                                 ''
                             )
                         ),
                         directory.actor_user_id nulls last
                limit ?
                """);

        arguments.add(limit);

        return jdbcTemplate.query(
                sql.toString(),
                ACTOR_ROW_MAPPER,
                arguments.toArray()
        );
    }
}
