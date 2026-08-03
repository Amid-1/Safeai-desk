package ru.safeai.gateway.usage.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.usage.config.UsageJdbcClients;
import ru.safeai.gateway.usage.repository.UsageRollupStateRepository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class UsageRollupDayProcessor {

    private final JdbcTemplate jdbcTemplate;
    private final UsageRollupStateRepository stateRepository;

    public UsageRollupDayProcessor(
            UsageJdbcClients jdbcClients,
            UsageRollupStateRepository stateRepository
    ) {
        this.jdbcTemplate = jdbcClients.rollup();
        this.stateRepository = stateRepository;
    }

    @Transactional
    public void rebuildDay(
            LocalDate usageDate,
            boolean advanceWatermark
    ) {
        Objects.requireNonNull(
                usageDate,
                "usageDate не должен быть null"
        );

        Instant from = usageDate
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        Instant to = usageDate
                .plusDays(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);

        jdbcTemplate.update(
                "delete from usage_daily_user_model_rollups "
                        + "where usage_date = ?",
                Date.valueOf(usageDate)
        );
        jdbcTemplate.update(
                "delete from usage_daily_org_model_rollups "
                        + "where usage_date = ?",
                Date.valueOf(usageDate)
        );
        jdbcTemplate.update(
                "delete from usage_daily_quality_rollups "
                        + "where usage_date = ?",
                Date.valueOf(usageDate)
        );

        insertUserRollups(usageDate, from, to);
        insertOrganizationRollups(usageDate, from, to);
        insertQualityRollups(usageDate, from, to);
        upsertAmbiguousTurnCounts(usageDate, from, to);

        if (advanceWatermark) {
            stateRepository.markCompleted(usageDate);
        }
    }

    private void insertUserRollups(
            LocalDate usageDate,
            Instant from,
            Instant to
    ) {
        jdbcTemplate.update(
                """
                insert into usage_daily_user_model_rollups (
                    usage_date,
                    organization_id,
                    user_id,
                    model,
                    input_tokens,
                    output_tokens,
                    total_tokens,
                    cost_usd,
                    assistant_message_count,
                    failed_message_count,
                    completed_response_count,
                    refused_response_count,
                    incomplete_response_count,
                    partial_input_tokens,
                    partial_output_tokens,
                    available_usage_message_count,
                    partial_usage_message_count,
                    missing_usage_message_count,
                    usage_not_applicable_message_count,
                    priced_message_count,
                    free_message_count,
                    unpriced_message_count,
                    pricing_failed_message_count,
                    pricing_not_applicable_message_count,
                    created_at,
                    updated_at
                )
                select
                    ?::date,
                    m.organization_id,
                    s.user_id,
                    coalesce(
                        m.model,
                        '__unattributed__'
                    ),
                    coalesce(sum(
                        case when m.usage_status = 'AVAILABLE'
                             then coalesce(m.input_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'AVAILABLE'
                             then coalesce(m.output_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'AVAILABLE'
                             then coalesce(m.input_tokens, 0)
                                + coalesce(m.output_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.pricing_status in ('PRICED', 'FREE')
                             then coalesce(m.cost_usd, 0)
                             else 0 end
                    ), 0),
                    count(*)::bigint,
                    count(*) filter (
                        where m.status = 'FAILED'
                    )::bigint,
                    count(*) filter (
                        where m.status = 'COMPLETED'
                          and m.ai_response_status = 'COMPLETED'
                    )::bigint,
                    count(*) filter (
                        where m.status = 'COMPLETED'
                          and m.ai_response_status = 'REFUSED'
                    )::bigint,
                    count(*) filter (
                        where m.status = 'COMPLETED'
                          and m.ai_response_status = 'INCOMPLETE'
                    )::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'PARTIAL'
                             then coalesce(m.input_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'PARTIAL'
                             then coalesce(m.output_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    count(*) filter (
                        where m.usage_status = 'AVAILABLE'
                    )::bigint,
                    count(*) filter (
                        where m.usage_status = 'PARTIAL'
                    )::bigint,
                    count(*) filter (
                        where m.usage_status = 'MISSING'
                    )::bigint,
                    count(*) filter (
                        where m.usage_status = 'NOT_APPLICABLE'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'PRICED'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'FREE'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'UNPRICED'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'CALCULATION_FAILED'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'NOT_APPLICABLE'
                    )::bigint,
                    current_timestamp,
                    current_timestamp
                from chat_messages m
                join chat_sessions s on s.id = m.session_id
                where m.role = 'ASSISTANT'
                  and m.created_at >= ?
                  and m.created_at < ?
                group by
                    m.organization_id,
                    s.user_id,
                    coalesce(m.model, '__unattributed__')
                """,
                Date.valueOf(usageDate),
                Timestamp.from(from),
                Timestamp.from(to)
        );
    }

    private void insertOrganizationRollups(
            LocalDate usageDate,
            Instant from,
            Instant to
    ) {
        jdbcTemplate.update(
                """
                insert into usage_daily_org_model_rollups (
                    usage_date,
                    organization_id,
                    model,
                    input_tokens,
                    output_tokens,
                    total_tokens,
                    cost_usd,
                    assistant_message_count,
                    failed_message_count,
                    completed_response_count,
                    refused_response_count,
                    incomplete_response_count,
                    partial_input_tokens,
                    partial_output_tokens,
                    available_usage_message_count,
                    partial_usage_message_count,
                    missing_usage_message_count,
                    usage_not_applicable_message_count,
                    priced_message_count,
                    free_message_count,
                    unpriced_message_count,
                    pricing_failed_message_count,
                    pricing_not_applicable_message_count,
                    created_at,
                    updated_at
                )
                select
                    ?::date,
                    m.organization_id,
                    coalesce(
                        m.model,
                        '__unattributed__'
                    ),
                    coalesce(sum(
                        case when m.usage_status = 'AVAILABLE'
                             then coalesce(m.input_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'AVAILABLE'
                             then coalesce(m.output_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'AVAILABLE'
                             then coalesce(m.input_tokens, 0)
                                + coalesce(m.output_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.pricing_status in ('PRICED', 'FREE')
                             then coalesce(m.cost_usd, 0)
                             else 0 end
                    ), 0),
                    count(*)::bigint,
                    count(*) filter (
                        where m.status = 'FAILED'
                    )::bigint,
                    count(*) filter (
                        where m.status = 'COMPLETED'
                          and m.ai_response_status = 'COMPLETED'
                    )::bigint,
                    count(*) filter (
                        where m.status = 'COMPLETED'
                          and m.ai_response_status = 'REFUSED'
                    )::bigint,
                    count(*) filter (
                        where m.status = 'COMPLETED'
                          and m.ai_response_status = 'INCOMPLETE'
                    )::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'PARTIAL'
                             then coalesce(m.input_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'PARTIAL'
                             then coalesce(m.output_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    count(*) filter (
                        where m.usage_status = 'AVAILABLE'
                    )::bigint,
                    count(*) filter (
                        where m.usage_status = 'PARTIAL'
                    )::bigint,
                    count(*) filter (
                        where m.usage_status = 'MISSING'
                    )::bigint,
                    count(*) filter (
                        where m.usage_status = 'NOT_APPLICABLE'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'PRICED'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'FREE'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'UNPRICED'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'CALCULATION_FAILED'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'NOT_APPLICABLE'
                    )::bigint,
                    current_timestamp,
                    current_timestamp
                from chat_messages m
                where m.role = 'ASSISTANT'
                  and m.created_at >= ?
                  and m.created_at < ?
                group by
                    m.organization_id,
                    coalesce(m.model, '__unattributed__')
                """,
                Date.valueOf(usageDate),
                Timestamp.from(from),
                Timestamp.from(to)
        );
    }

    private void insertQualityRollups(
            LocalDate usageDate,
            Instant from,
            Instant to
    ) {
        jdbcTemplate.update(
                """
                insert into usage_daily_quality_rollups (
                    usage_date,
                    organization_id,
                    assistant_message_count,
                    stored_completed_message_count,
                    stored_failed_message_count,
                    missing_model_message_count,
                    input_tokens,
                    output_tokens,
                    partial_input_tokens,
                    partial_output_tokens,
                    cost_usd,
                    available_usage_message_count,
                    partial_usage_message_count,
                    missing_usage_message_count,
                    usage_not_applicable_message_count,
                    priced_message_count,
                    free_message_count,
                    unpriced_message_count,
                    pricing_failed_message_count,
                    pricing_not_applicable_message_count,
                    created_at,
                    updated_at
                )
                select
                    ?::date,
                    m.organization_id,
                    count(*)::bigint,
                    count(*) filter (
                        where m.status = 'COMPLETED'
                    )::bigint,
                    count(*) filter (
                        where m.status = 'FAILED'
                    )::bigint,
                    count(*) filter (
                        where m.model is null
                    )::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'AVAILABLE'
                             then coalesce(m.input_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'AVAILABLE'
                             then coalesce(m.output_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'PARTIAL'
                             then coalesce(m.input_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.usage_status = 'PARTIAL'
                             then coalesce(m.output_tokens, 0)
                             else 0 end
                    ), 0)::bigint,
                    coalesce(sum(
                        case when m.pricing_status in ('PRICED', 'FREE')
                             then coalesce(m.cost_usd, 0)
                             else 0 end
                    ), 0),
                    count(*) filter (
                        where m.usage_status = 'AVAILABLE'
                    )::bigint,
                    count(*) filter (
                        where m.usage_status = 'PARTIAL'
                    )::bigint,
                    count(*) filter (
                        where m.usage_status = 'MISSING'
                    )::bigint,
                    count(*) filter (
                        where m.usage_status = 'NOT_APPLICABLE'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'PRICED'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'FREE'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'UNPRICED'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'CALCULATION_FAILED'
                    )::bigint,
                    count(*) filter (
                        where m.pricing_status = 'NOT_APPLICABLE'
                    )::bigint,
                    current_timestamp,
                    current_timestamp
                from chat_messages m
                where m.role = 'ASSISTANT'
                  and m.created_at >= ?
                  and m.created_at < ?
                group by m.organization_id
                """,
                Date.valueOf(usageDate),
                Timestamp.from(from),
                Timestamp.from(to)
        );
    }
    private void upsertAmbiguousTurnCounts(
            LocalDate usageDate,
            Instant from,
            Instant to
    ) {
        jdbcTemplate.update(
                """
                insert into usage_daily_quality_rollups (
                    usage_date,
                    organization_id,
                    ambiguous_provider_operation_count,
                    created_at,
                    updated_at
                )
                select
                    ?::date,
                    turn.organization_id,
                    count(*)::bigint,
                    current_timestamp,
                    current_timestamp
                from chat_turns turn
                where turn.state = 'AMBIGUOUS'
                  and turn.completed_at >= ?
                  and turn.completed_at < ?
                group by turn.organization_id
                on conflict (usage_date, organization_id)
                do update set
                    ambiguous_provider_operation_count =
                        excluded.ambiguous_provider_operation_count,
                    updated_at = current_timestamp
                """,
                Date.valueOf(usageDate),
                Timestamp.from(from),
                Timestamp.from(to)
        );
    }

}
