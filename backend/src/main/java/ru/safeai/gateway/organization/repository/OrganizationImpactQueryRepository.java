package ru.safeai.gateway.organization.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OrganizationImpactQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ImpactSnapshot load(
            UUID organizationId
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        MapSqlParameterSource parameters =
                new MapSqlParameterSource(
                        "organizationId",
                        organizationId
                );

        ImpactSnapshot snapshot =
                jdbcTemplate.queryForObject(
                        """
                        select
                            (
                                select count(*)
                                from users app_user
                                where app_user.organization_id =
                                      :organizationId
                                  and app_user.enabled = true
                            ) as enabled_users,

                            (
                                select count(distinct app_user.id)
                                from users app_user
                                join user_roles user_role
                                  on user_role.user_id = app_user.id
                                join roles role
                                  on role.id = user_role.role_id
                                where app_user.organization_id =
                                      :organizationId
                                  and app_user.enabled = true
                                  and role.name = 'ADMIN'
                            ) as administrators,

                            (
                                select count(*)
                                from refresh_tokens refresh_token
                                join users app_user
                                  on app_user.id =
                                     refresh_token.user_id
                                where app_user.organization_id =
                                      :organizationId
                                  and refresh_token.revoked_at is null
                                  and refresh_token.expires_at >
                                      current_timestamp
                            ) as active_refresh_sessions,

                            (
                                select count(*)
                                from chat_turns chat_turn
                                where chat_turn.organization_id =
                                      :organizationId
                                  and chat_turn.state = 'PROCESSING'
                            ) as active_chat_operations
                        """,
                        parameters,
                        (resultSet, rowNumber) ->
                                new ImpactSnapshot(
                                        resultSet.getLong(
                                                "enabled_users"
                                        ),
                                        resultSet.getLong(
                                                "administrators"
                                        ),
                                        resultSet.getLong(
                                                "active_refresh_sessions"
                                        ),
                                        resultSet.getLong(
                                                "active_chat_operations"
                                        )
                                )
                );

        return Objects.requireNonNull(
                snapshot,
                "Impact query returned null"
        );
    }

    public record ImpactSnapshot(
            long enabledUsers,
            long administrators,
            long activeRefreshSessions,
            long activeChatOperations
    ) {
        public ImpactSnapshot {
            requireNonNegative(
                    enabledUsers,
                    "enabledUsers"
            );
            requireNonNegative(
                    administrators,
                    "administrators"
            );
            requireNonNegative(
                    activeRefreshSessions,
                    "activeRefreshSessions"
            );
            requireNonNegative(
                    activeChatOperations,
                    "activeChatOperations"
            );
        }

        private static void requireNonNegative(
                long value,
                String field
        ) {
            if (value < 0L) {
                throw new IllegalArgumentException(
                        field + " не может быть отрицательным"
                );
            }
        }
    }
}
