package ru.safeai.gateway.chat.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.chat.config.ChatQuotaProperties;
import ru.safeai.gateway.chat.entity.ChatQuotaReservationState;
import ru.safeai.gateway.chat.quota.ChatQuotaConsumption;
import ru.safeai.gateway.chat.quota.ChatQuotaPolicy;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Repository
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
public class ChatQuotaRepository {

    private static final String LOCK_ORGANIZATION_POLICY_SQL = """
            select
                enabled,
                monthly_request_limit,
                monthly_input_token_limit,
                monthly_output_token_limit,
                monthly_cost_limit_usd
            from organization_ai_quotas
            where organization_id = ?
            for update
            """;

    private static final String LOCK_USER_POLICY_SQL = """
            select
                enabled,
                monthly_request_limit,
                monthly_input_token_limit,
                monthly_output_token_limit,
                monthly_cost_limit_usd
            from user_ai_quotas
            where user_id = ?
            for update
            """;

    private static final String INSERT_RESERVATION_SQL = """
            insert into chat_quota_reservations (
                turn_id,
                organization_id,
                user_id,
                period_start,
                state,
                reserved_input_tokens,
                reserved_output_tokens,
                reserved_cost_usd,
                created_at,
                updated_at
            ) values (
                ?,
                ?,
                ?,
                ?,
                'RESERVED',
                ?,
                ?,
                ?,
                ?,
                ?
            )
            """;

    private static final String SETTLE_SUCCESS_SQL = """
            update chat_quota_reservations
               set state = ?,
                   actual_input_tokens = ?,
                   actual_output_tokens = ?,
                   actual_cost_usd = ?,
                   usage_status = ?,
                   pricing_status = ?,
                   settled_at = ?,
                   updated_at = ?
             where turn_id = ?
               and state = 'RESERVED'
            """;

    private static final String RELEASE_FAILURE_SQL = """
            update chat_quota_reservations
               set state = 'RELEASED',
                   settled_at = ?,
                   updated_at = ?
             where turn_id = ?
               and state = 'RESERVED'
            """;

    private static final String MARK_AMBIGUOUS_SQL = """
            update chat_quota_reservations
               set state = 'AMBIGUOUS',
                   settled_at = ?,
                   updated_at = ?
             where turn_id = ?
               and state = 'RESERVED'
            """;

    private static final String ORGANIZATION_CONSUMPTION_SQL = """
            select
                count(*) filter (
                    where state <> 'RELEASED'
                )::bigint as requests,
                coalesce(
                    sum(
                        case
                            when state = 'RELEASED' then 0
                            else coalesce(
                                actual_input_tokens,
                                reserved_input_tokens
                            )
                        end
                    ),
                    0
                )::bigint as input_tokens,
                coalesce(
                    sum(
                        case
                            when state = 'RELEASED' then 0
                            else coalesce(
                                actual_output_tokens,
                                reserved_output_tokens
                            )
                        end
                    ),
                    0
                )::bigint as output_tokens,
                coalesce(
                    sum(
                        case
                            when state = 'RELEASED' then 0
                            else coalesce(
                                actual_cost_usd,
                                reserved_cost_usd
                            )
                        end
                    ),
                    0
                )::numeric(30, 12) as cost_usd
            from chat_quota_reservations
            where organization_id = ?
              and period_start = ?
            """;

    private static final String USER_CONSUMPTION_SQL = """
            select
                count(*) filter (
                    where state <> 'RELEASED'
                )::bigint as requests,
                coalesce(
                    sum(
                        case
                            when state = 'RELEASED' then 0
                            else coalesce(
                                actual_input_tokens,
                                reserved_input_tokens
                            )
                        end
                    ),
                    0
                )::bigint as input_tokens,
                coalesce(
                    sum(
                        case
                            when state = 'RELEASED' then 0
                            else coalesce(
                                actual_output_tokens,
                                reserved_output_tokens
                            )
                        end
                    ),
                    0
                )::bigint as output_tokens,
                coalesce(
                    sum(
                        case
                            when state = 'RELEASED' then 0
                            else coalesce(
                                actual_cost_usd,
                                reserved_cost_usd
                            )
                        end
                    ),
                    0
                )::numeric(30, 12) as cost_usd
            from chat_quota_reservations
            where user_id = ?
              and period_start = ?
            """;

    private static final ChatQuotaConsumption EMPTY_CONSUMPTION =
            new ChatQuotaConsumption(
                    0L,
                    0L,
                    0L,
                    BigDecimal.ZERO
            );

    private final JdbcTemplate jdbcTemplate;

    public ChatQuotaRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate не должен быть null"
        );
    }

    public ChatQuotaPolicy lockOrganizationPolicy(
            UUID organizationId
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        return lockPolicy(
                LOCK_ORGANIZATION_POLICY_SQL,
                organizationId
        );
    }

    public ChatQuotaPolicy lockUserPolicy(
            UUID userId
    ) {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        return lockPolicy(
                LOCK_USER_POLICY_SQL,
                userId
        );
    }

    public ChatQuotaConsumption organizationConsumption(
            UUID organizationId,
            LocalDate periodStart
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(
                periodStart,
                "periodStart не должен быть null"
        );

        return queryConsumption(
                ORGANIZATION_CONSUMPTION_SQL,
                organizationId,
                periodStart
        );
    }

    public ChatQuotaConsumption userConsumption(
            UUID userId,
            LocalDate periodStart
    ) {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        Objects.requireNonNull(
                periodStart,
                "periodStart не должен быть null"
        );

        return queryConsumption(
                USER_CONSUMPTION_SQL,
                userId,
                periodStart
        );
    }

    public void insertReservation(
            UUID turnId,
            UUID organizationId,
            UUID userId,
            LocalDate periodStart,
            ChatQuotaProperties properties,
            Instant now
    ) {
        Objects.requireNonNull(
                turnId,
                "turnId не должен быть null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        Objects.requireNonNull(
                periodStart,
                "periodStart не должен быть null"
        );

        Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );

        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        Timestamp timestamp = Timestamp.from(now);

        int updated = jdbcTemplate.update(
                INSERT_RESERVATION_SQL,
                turnId,
                organizationId,
                userId,
                Date.valueOf(periodStart),
                properties.reservationInputTokens(),
                properties.reservationOutputTokens(),
                properties.reservationCostUsd(),
                timestamp,
                timestamp
        );

        requireExactlyOneUpdate(
                updated,
                "insertReservation",
                turnId
        );
    }

    public void settleSuccess(
            UUID turnId,
            AiChatResponse response,
            Instant now
    ) {
        Objects.requireNonNull(
                turnId,
                "turnId не должен быть null"
        );

        Objects.requireNonNull(
                response,
                "response не должен быть null"
        );

        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        ChatQuotaReservationState reservationState =
                resolveSuccessfulReservationState(response);

        Timestamp timestamp = Timestamp.from(now);

        int updated = jdbcTemplate.update(
                SETTLE_SUCCESS_SQL,
                reservationState.name(),
                response.inputTokens(),
                response.outputTokens(),
                response.costUsd(),
                response.usageStatus().name(),
                response.pricingStatus().name(),
                timestamp,
                timestamp,
                turnId
        );

        requireExactlyOneUpdate(
                updated,
                "settleSuccess",
                turnId
        );
    }

    public void releaseFailure(
            UUID turnId,
            Instant now
    ) {
        Objects.requireNonNull(
                turnId,
                "turnId не должен быть null"
        );

        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        Timestamp timestamp = Timestamp.from(now);

        int updated = jdbcTemplate.update(
                RELEASE_FAILURE_SQL,
                timestamp,
                timestamp,
                turnId
        );

        requireAtMostOneUpdate(
                updated,
                "releaseFailure",
                turnId
        );
    }

    public void markAmbiguous(
            UUID turnId,
            Instant now
    ) {
        Objects.requireNonNull(
                turnId,
                "turnId не должен быть null"
        );

        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        Timestamp timestamp = Timestamp.from(now);

        int updated = jdbcTemplate.update(
                MARK_AMBIGUOUS_SQL,
                timestamp,
                timestamp,
                turnId
        );

        requireAtMostOneUpdate(
                updated,
                "markAmbiguous",
                turnId
        );
    }

    private ChatQuotaPolicy lockPolicy(
            String sql,
            UUID subjectId
    ) {
        try {
            ChatQuotaPolicy policy =
                    jdbcTemplate.queryForObject(
                            sql,
                            ChatQuotaRepository::mapPolicy,
                            subjectId
                    );

            return policy == null
                    ? ChatQuotaPolicy.unlimited()
                    : policy;
        } catch (EmptyResultDataAccessException ignored) {
            return ChatQuotaPolicy.unlimited();
        }
    }

    private ChatQuotaConsumption queryConsumption(
            String sql,
            UUID scopeId,
            LocalDate periodStart
    ) {
        ChatQuotaConsumption consumption =
                jdbcTemplate.queryForObject(
                        sql,
                        ChatQuotaRepository::mapConsumption,
                        scopeId,
                        Date.valueOf(periodStart)
                );

        return consumption == null
                ? EMPTY_CONSUMPTION
                : consumption;
    }

    private static ChatQuotaPolicy mapPolicy(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new ChatQuotaPolicy(
                resultSet.getBoolean("enabled"),
                nullableLong(
                        resultSet,
                        "monthly_request_limit"
                ),
                nullableLong(
                        resultSet,
                        "monthly_input_token_limit"
                ),
                nullableLong(
                        resultSet,
                        "monthly_output_token_limit"
                ),
                resultSet.getBigDecimal(
                        "monthly_cost_limit_usd"
                )
        );
    }

    private static ChatQuotaConsumption mapConsumption(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new ChatQuotaConsumption(
                resultSet.getLong("requests"),
                resultSet.getLong("input_tokens"),
                resultSet.getLong("output_tokens"),
                resultSet.getBigDecimal("cost_usd")
        );
    }

    private static ChatQuotaReservationState
    resolveSuccessfulReservationState(
            AiChatResponse response
    ) {
        return switch (response.pricingStatus()) {
            case PRICED, FREE ->
                    ChatQuotaReservationState.SETTLED;

            case UNPRICED, CALCULATION_FAILED ->
                    ChatQuotaReservationState.UNPRICED;

            case NOT_APPLICABLE ->
                    throw new IllegalArgumentException(
                            "Provider response не может иметь "
                                    + "NOT_APPLICABLE pricing"
                    );
        };
    }

    private static Long nullableLong(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        long value = resultSet.getLong(column);

        return resultSet.wasNull()
                ? null
                : value;
    }

    private static void requireExactlyOneUpdate(
            int updated,
            String operation,
            UUID turnId
    ) {
        if (updated != 1) {
            throw new IllegalStateException(
                    operation
                            + " ожидал изменение одной quota reservation: "
                            + "turnId="
                            + turnId
                            + ", updated="
                            + updated
            );
        }
    }

    private static void requireAtMostOneUpdate(
            int updated,
            String operation,
            UUID turnId
    ) {
        if (updated > 1) {
            throw new IllegalStateException(
                    operation
                            + " изменил несколько quota reservations: "
                            + "turnId="
                            + turnId
                            + ", updated="
                            + updated
            );
        }
    }
}