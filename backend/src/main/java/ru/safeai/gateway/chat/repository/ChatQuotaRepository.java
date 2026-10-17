package ru.safeai.gateway.chat.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.chat.config.ChatQuotaProperties;
import ru.safeai.gateway.chat.entity.ChatQuotaReservationState;
import ru.safeai.gateway.chat.quota.ChatQuotaConsumption;
import ru.safeai.gateway.chat.quota.ChatQuotaPolicy;
import ru.safeai.gateway.chat.quota.ChatQuotaReservation;

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

    // V67 reservation format version. Never infer that a configured reservation
    // amount is a cryptographically or economically verified provider price.
    private static final int INITIAL_RESERVATION_VERSION = 1;

    private static final String INSERT_RESERVATION_SQL = """
            insert into chat_quota_reservations (
                turn_id,
                organization_id,
                user_id,
                period_start,
                state,
                reservation_version,
                cost_verifiable,
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
                count(*) filter (where r.state <> 'RELEASED')::bigint as requests,
                coalesce(sum(case
                    when r.state = 'RELEASED' then coalesce(p.known_input_tokens, 0)
                    when r.state in ('SETTLED', 'UNPRICED') then
                        greatest(coalesce(p.known_input_tokens, 0), coalesce(r.actual_input_tokens, 0))
                    else greatest(coalesce(p.known_input_tokens, 0),
                                  coalesce(r.actual_input_tokens, r.reserved_input_tokens))
                end), 0)::bigint as input_tokens,
                coalesce(sum(case
                    when r.state = 'RELEASED' then coalesce(p.known_output_tokens, 0)
                    when r.state in ('SETTLED', 'UNPRICED') then
                        greatest(coalesce(p.known_output_tokens, 0), coalesce(r.actual_output_tokens, 0))
                    else greatest(coalesce(p.known_output_tokens, 0),
                                  coalesce(r.actual_output_tokens, r.reserved_output_tokens))
                end), 0)::bigint as output_tokens,
                coalesce(sum(case
                    when r.state = 'RELEASED' then coalesce(p.known_cost_usd, 0)
                    when r.state in ('SETTLED', 'UNPRICED') then
                        greatest(coalesce(p.known_cost_usd, 0), coalesce(r.actual_cost_usd, 0))
                    else greatest(coalesce(p.known_cost_usd, 0),
                                  coalesce(r.actual_cost_usd, r.reserved_cost_usd))
                end), 0)::numeric(30,12) as cost_usd,
                coalesce(bool_or(
                    coalesce(p.usage_unverifiable, false)
                    or exists (
                        select 1 from model_execution_plans ep
                        join model_execution_attempts ea on ea.execution_plan_id = ep.id
                        where ep.chat_turn_id = r.turn_id
                          and ea.outcome in ('STARTED', 'AMBIGUOUS')
                    )
                    or (r.state <> 'RELEASED' and (
                        r.state = 'AMBIGUOUS'
                        or (r.reservation_version <> 2 and r.state = 'RESERVED')
                        or (r.state in ('SETTLED', 'UNPRICED')
                            and r.usage_status is distinct from 'AVAILABLE')
                    ))
                ), false) as usage_unverifiable,
                coalesce(bool_or(
                    coalesce(p.cost_unverifiable, false)
                    or exists (
                        select 1 from model_execution_plans ep
                        join model_execution_attempts ea
                          on ea.execution_plan_id = ep.id
                        where ep.chat_turn_id = r.turn_id
                          and (ea.outcome in ('STARTED', 'AMBIGUOUS')
                               or (ea.outcome_certainty is distinct from 'KNOWN_NOT_EXECUTED'
                                   and ea.outcome is distinct from 'SUCCEEDED'
                                   and ea.pricing_status is distinct from 'PRICED'
                                   and ea.pricing_status is distinct from 'FREE'))
                    )
                    or (r.state <> 'RELEASED' and (
                        r.state in ('AMBIGUOUS', 'UNPRICED')
                        or not r.cost_verifiable
                        or (r.state = 'RESERVED' and (
                            r.reservation_version <> 2
                            or r.model_route_decision_id is null
                            or not r.cost_verifiable))
                        or (r.state = 'SETTLED' and
                            (r.actual_cost_usd is null
                             or (r.pricing_status is distinct from 'PRICED'
                                 and r.pricing_status is distinct from 'FREE')))
                    ))
                ), false) as cost_unverifiable
            from chat_quota_reservations r
            left join chat_physical_spend_v67 p on p.turn_id = r.turn_id
            where r.organization_id = ?
              and r.period_start = ?
            """;

    private static final String USER_CONSUMPTION_SQL = """
            select
                count(*) filter (where r.state <> 'RELEASED')::bigint as requests,
                coalesce(sum(case
                    when r.state = 'RELEASED' then coalesce(p.known_input_tokens, 0)
                    when r.state in ('SETTLED', 'UNPRICED') then
                        greatest(coalesce(p.known_input_tokens, 0), coalesce(r.actual_input_tokens, 0))
                    else greatest(coalesce(p.known_input_tokens, 0),
                                  coalesce(r.actual_input_tokens, r.reserved_input_tokens))
                end), 0)::bigint as input_tokens,
                coalesce(sum(case
                    when r.state = 'RELEASED' then coalesce(p.known_output_tokens, 0)
                    when r.state in ('SETTLED', 'UNPRICED') then
                        greatest(coalesce(p.known_output_tokens, 0), coalesce(r.actual_output_tokens, 0))
                    else greatest(coalesce(p.known_output_tokens, 0),
                                  coalesce(r.actual_output_tokens, r.reserved_output_tokens))
                end), 0)::bigint as output_tokens,
                coalesce(sum(case
                    when r.state = 'RELEASED' then coalesce(p.known_cost_usd, 0)
                    when r.state in ('SETTLED', 'UNPRICED') then
                        greatest(coalesce(p.known_cost_usd, 0), coalesce(r.actual_cost_usd, 0))
                    else greatest(coalesce(p.known_cost_usd, 0),
                                  coalesce(r.actual_cost_usd, r.reserved_cost_usd))
                end), 0)::numeric(30,12) as cost_usd,
                coalesce(bool_or(
                    coalesce(p.usage_unverifiable, false)
                    or exists (
                        select 1 from model_execution_plans ep
                        join model_execution_attempts ea on ea.execution_plan_id = ep.id
                        where ep.chat_turn_id = r.turn_id
                          and ea.outcome in ('STARTED', 'AMBIGUOUS')
                    )
                    or (r.state <> 'RELEASED' and (
                        r.state = 'AMBIGUOUS'
                        or (r.reservation_version <> 2 and r.state = 'RESERVED')
                        or (r.state in ('SETTLED', 'UNPRICED')
                            and r.usage_status is distinct from 'AVAILABLE')
                    ))
                ), false) as usage_unverifiable,
                coalesce(bool_or(
                    coalesce(p.cost_unverifiable, false)
                    or exists (
                        select 1 from model_execution_plans ep
                        join model_execution_attempts ea
                          on ea.execution_plan_id = ep.id
                        where ep.chat_turn_id = r.turn_id
                          and (ea.outcome in ('STARTED', 'AMBIGUOUS')
                               or (ea.outcome_certainty is distinct from 'KNOWN_NOT_EXECUTED'
                                   and ea.outcome is distinct from 'SUCCEEDED'
                                   and ea.pricing_status is distinct from 'PRICED'
                                   and ea.pricing_status is distinct from 'FREE'))
                    )
                    or (r.state <> 'RELEASED' and (
                        r.state in ('AMBIGUOUS', 'UNPRICED')
                        or not r.cost_verifiable
                        or (r.state = 'RESERVED' and (
                            r.reservation_version <> 2
                            or r.model_route_decision_id is null
                            or not r.cost_verifiable))
                        or (r.state = 'SETTLED' and
                            (r.actual_cost_usd is null
                             or (r.pricing_status is distinct from 'PRICED'
                                 and r.pricing_status is distinct from 'FREE')))
                    ))
                ), false) as cost_unverifiable
            from chat_quota_reservations r
            left join chat_physical_spend_v67 p on p.turn_id = r.turn_id
            where r.user_id = ?
              and r.period_start = ?
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

    @Deprecated // legacy v1 fixture/API. Production calls the route-bound overload.
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

        Timestamp timestamp =
                Timestamp.from(now);

        int updated =
                jdbcTemplate.update(
                        INSERT_RESERVATION_SQL,
                        turnId,
                        organizationId,
                        userId,
                        Date.valueOf(periodStart),
                        INITIAL_RESERVATION_VERSION,
                        false, // Fail-closed: no verified route/price evidence is supplied here.
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

    /** V66 scheme identity is mandatory for hard monetary quota evidence. */
    public boolean hasVerifiedRoutePricingScheme(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId не должен быть null");
        Boolean verified = jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from model_route_decisions d
                      join model_catalog_entries e
                        on e.id = d.selected_catalog_entry_id
                       and e.version = d.selected_catalog_version
                     where d.id = ?
                       and d.outcome = 'ALLOWED'
                       and d.pricing_complete = true
                       and e.pricing_complete = true
                       and e.pricing_scheme = 'FLAT_TOKEN_V1'
                       and e.provider = d.selected_provider
                       and e.provider_model_id = d.selected_provider_model_id
                )
                """, Boolean.class, decisionId);
        return Boolean.TRUE.equals(verified);
    }

    /** New reservations use the immutable V67 route-binding format. */
    public void insertReservation(
            UUID turnId,
            UUID organizationId,
            UUID userId,
            LocalDate periodStart,
            ChatQuotaReservation reservation,
            Instant now
    ) {
        Objects.requireNonNull(turnId, "turnId не должен быть null");
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");
        Objects.requireNonNull(userId, "userId не должен быть null");
        Objects.requireNonNull(periodStart, "periodStart не должен быть null");
        Objects.requireNonNull(reservation, "reservation не должен быть null");
        Objects.requireNonNull(now, "now не должен быть null");

        Timestamp timestamp = Timestamp.from(now);
        int updated = jdbcTemplate.update("""
                insert into chat_quota_reservations (
                    turn_id, organization_id, user_id, period_start, state,
                    model_route_decision_id, reservation_version, cost_verifiable,
                    reserved_input_tokens, reserved_output_tokens, reserved_cost_usd,
                    created_at, updated_at
                ) select ?, ?, ?, ?, 'RESERVED', d.id, 2, ?, ?, ?, ?, ?, ?
                  from model_route_decisions d
                 where d.id = ? and d.chat_turn_id = ?
                   and d.organization_id = ? and d.user_id = ?
                   and d.outcome = 'ALLOWED'
                   and d.estimated_input_tokens = ?
                   and d.estimated_output_tokens = ?
                   and (not ? or (d.pricing_complete = true
                       and d.estimated_max_cost_usd is not null
                       and d.estimated_max_cost_usd <= ?))
                """,
                turnId, organizationId, userId, Date.valueOf(periodStart),
                reservation.costVerifiable(),
                reservation.inputUnits(), reservation.outputUnits(),
                reservation.databaseReservedCostUsd(), timestamp, timestamp,
                reservation.modelRouteDecisionId(), turnId, organizationId, userId,
                reservation.inputUnits(), reservation.outputUnits(),
                reservation.costVerifiable(), reservation.databaseReservedCostUsd());
        requireExactlyOneUpdate(updated, "insertRouteReservation", turnId);
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
                resolveSuccessfulReservationState(
                        response
                );

        Timestamp timestamp =
                Timestamp.from(now);

        int updated =
                jdbcTemplate.update(
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

        Timestamp timestamp =
                Timestamp.from(now);

        int updated =
                jdbcTemplate.update(
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

        Timestamp timestamp =
                Timestamp.from(now);

        int updated =
                jdbcTemplate.update(
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
            int ignoredRowNumber
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
            int ignoredRowNumber
    ) throws SQLException {
        return new ChatQuotaConsumption(
                resultSet.getLong("requests"),
                resultSet.getLong("input_tokens"),
                resultSet.getLong("output_tokens"),
                resultSet.getBigDecimal("cost_usd"),
                resultSet.getBoolean("usage_unverifiable"),
                resultSet.getBoolean("cost_unverifiable")
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
        long value =
                resultSet.getLong(column);

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
