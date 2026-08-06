package ru.safeai.gateway.organization.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OrganizationImpactQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ImpactSnapshot load(UUID organizationId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource(
                "organizationId",
                organizationId
        );

        return jdbcTemplate.queryForObject(
                """
                select
                    (select count(*)
                       from users u
                      where u.organization_id = :organizationId
                        and u.enabled = true) as enabled_users,

                    (select count(distinct u.id)
                       from users u
                       join user_roles ur on ur.user_id = u.id
                       join roles r on r.id = ur.role_id
                      where u.organization_id = :organizationId
                        and u.enabled = true
                        and r.name = 'ADMIN') as administrators,

                    (select count(*)
                       from refresh_tokens rt
                       join users u on u.id = rt.user_id
                      where u.organization_id = :organizationId
                        and rt.revoked_at is null
                        and rt.expires_at > current_timestamp) as active_refresh_sessions,

                    (select count(*)
                       from chat_turns ct
                      where ct.organization_id = :organizationId
                        and ct.state = 'PROCESSING') as active_chat_operations
                """,
                parameters,
                (resultSet, rowNum) -> new ImpactSnapshot(
                        resultSet.getLong("enabled_users"),
                        resultSet.getLong("administrators"),
                        resultSet.getLong("active_refresh_sessions"),
                        resultSet.getLong("active_chat_operations")
                )
        );
    }

    public record ImpactSnapshot(
            long enabledUsers,
            long administrators,
            long activeRefreshSessions,
            long activeChatOperations
    ) {
    }
}
