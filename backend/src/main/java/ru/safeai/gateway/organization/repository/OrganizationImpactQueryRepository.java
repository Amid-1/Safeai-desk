package ru.safeai.gateway.organization.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

/**
 * Read-only, advisory impact preview. It cannot freeze the future and cannot
 * revoke physical AI provider requests already sent over the network.
 */
@Repository
public class OrganizationImpactQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OrganizationImpactQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate, "jdbcTemplate не должен быть null"
        );
    }

    public ImpactSnapshot load(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");

        MapSqlParameterSource parameters =
                new MapSqlParameterSource("organizationId", organizationId);

        ImpactSnapshot snapshot = jdbcTemplate.queryForObject("""
                select
                    (select count(*) from public.users app_user
                     where app_user.organization_id = :organizationId
                       and app_user.enabled = true) as enabled_users,
                    (select count(distinct app_user.id) from public.users app_user
                     join public.user_roles user_role on user_role.user_id = app_user.id
                     join public.roles role on role.id = user_role.role_id
                     where app_user.organization_id = :organizationId
                       and app_user.enabled = true
                       and role.name = 'ADMIN') as administrators,
                    -- Snapshot of refresh rows that pass the same main
                    -- account/organization/epoch/expiry fences as refresh.
                    -- This is NOT a count of concurrently running sessions.
                    (select count(*) from public.refresh_tokens refresh_token
                     join public.users app_user on app_user.id = refresh_token.user_id
                     join public.organizations organization
                       on organization.id = app_user.organization_id
                     where app_user.organization_id = :organizationId
                       and app_user.enabled = true
                       and organization.enabled = true
                       and refresh_token.revoked_at is null
                       and refresh_token.replaced_by_token_id is null
                       and refresh_token.expires_at > current_timestamp
                       and refresh_token.family_expires_at > current_timestamp
                       and refresh_token.issued_token_version = app_user.token_version
                       and refresh_token.issued_organization_auth_version
                            = organization.auth_version)
                       as active_refresh_sessions,
                    -- Chat turns accepted but not terminal.
                    (select count(*) from public.chat_turns chat_turn
                     where chat_turn.organization_id = :organizationId
                       and chat_turn.state in ('NEW', 'PROCESSING'))
                       as active_chat_operations,
                    -- A separate counter avoids silently omitting STARTED
                    -- provider calls whose chat turn became terminal/stale.
                    (select count(*) from public.model_execution_attempts attempt
                     join public.model_execution_plans execution_plan
                       on execution_plan.id = attempt.execution_plan_id
                     where execution_plan.organization_id = :organizationId
                       and attempt.outcome = 'STARTED')
                       as started_physical_attempts,
                    -- Ambiguous/protocol-rejected historical physical outcomes
                    -- deserve explicit review. A historical row is NOT proof
                    -- that billing is still unknown or a remote call is live.
                    (select count(*) from public.model_execution_attempts attempt
                     join public.model_execution_plans execution_plan
                       on execution_plan.id = attempt.execution_plan_id
                     where execution_plan.organization_id = :organizationId
                       and attempt.outcome in ('AMBIGUOUS', 'PROTOCOL_REJECTED'))
                       as uncertain_physical_attempts
                """, parameters, (resultSet, rowNumber) -> new ImpactSnapshot(
                resultSet.getLong("enabled_users"),
                resultSet.getLong("administrators"),
                resultSet.getLong("active_refresh_sessions"),
                resultSet.getLong("active_chat_operations"),
                resultSet.getLong("started_physical_attempts"),
                resultSet.getLong("uncertain_physical_attempts")
        ));
        return Objects.requireNonNull(snapshot, "Impact query returned null");
    }

    /**
     * Snapshot counts rows under the database query snapshot. The counters
     * cannot promise cancellation of remote calls or capture future activity.
     */
    public record ImpactSnapshot(
            long enabledUsers,
            long administrators,
            long activeRefreshSessions,
            long activeChatOperations,
            long startedPhysicalAttempts,
            long uncertainPhysicalAttempts
    ) {
        public ImpactSnapshot {
            requireNonNegative(enabledUsers, "enabledUsers");
            requireNonNegative(administrators, "administrators");
            requireNonNegative(activeRefreshSessions, "activeRefreshSessions");
            requireNonNegative(activeChatOperations, "activeChatOperations");
            requireNonNegative(startedPhysicalAttempts, "startedPhysicalAttempts");
            requireNonNegative(uncertainPhysicalAttempts, "uncertainPhysicalAttempts");
        }

        /** Existing tests and nonproduction callers can still construct the legacy shape. */
        public ImpactSnapshot(
                long enabledUsers,
                long administrators,
                long activeRefreshSessions,
                long activeChatOperations
        ) {
            this(enabledUsers, administrators, activeRefreshSessions,
                    activeChatOperations, 0L, 0L);
        }

        private static void requireNonNegative(long value, String field) {
            if (value < 0L) {
                throw new IllegalArgumentException(field + " не может быть отрицательным");
            }
        }
    }
}
