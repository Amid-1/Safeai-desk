package ru.safeai.gateway.usage.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.usage.config.UsageJdbcClients;
import ru.safeai.gateway.usage.dto.UsageCostSummary;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageDataQualityResponse;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageProblemModelResponse;
import ru.safeai.gateway.usage.dto.UsageResponseSummary;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageTokenSummary;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

@Repository
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
public class JdbcUsageQueryRepository
        implements UsageQueryRepository {

    private static final String UNATTRIBUTED_MODEL =
            "__unattributed__";

    private static final String AGGREGATE_COLUMNS = """
            cast(coalesce(sum(assistant_message_count), 0) as bigint)
                as assistant_message_count,
            cast(coalesce(sum(completed_response_count), 0) as bigint)
                as completed_response_count,
            cast(coalesce(sum(refused_response_count), 0) as bigint)
                as refused_response_count,
            cast(coalesce(sum(incomplete_response_count), 0) as bigint)
                as incomplete_response_count,
            cast(coalesce(sum(failed_message_count), 0) as bigint)
                as failed_message_count,
            cast(coalesce(sum(input_tokens), 0) as bigint)
                as input_tokens,
            cast(coalesce(sum(output_tokens), 0) as bigint)
                as output_tokens,
            cast(coalesce(sum(partial_input_tokens), 0) as bigint)
                as partial_input_tokens,
            cast(coalesce(sum(partial_output_tokens), 0) as bigint)
                as partial_output_tokens,
            cast(coalesce(sum(available_usage_message_count), 0) as bigint)
                as available_usage_message_count,
            cast(coalesce(sum(partial_usage_message_count), 0) as bigint)
                as partial_usage_message_count,
            cast(coalesce(sum(missing_usage_message_count), 0) as bigint)
                as missing_usage_message_count,
            cast(coalesce(sum(usage_not_applicable_message_count), 0) as bigint)
                as usage_not_applicable_message_count,
            coalesce(sum(cost_usd), 0) as known_cost_usd,
            cast(coalesce(sum(priced_message_count), 0) as bigint)
                as priced_message_count,
            cast(coalesce(sum(free_message_count), 0) as bigint)
                as free_message_count,
            cast(coalesce(sum(unpriced_message_count), 0) as bigint)
                as unpriced_message_count,
            cast(coalesce(sum(pricing_failed_message_count), 0) as bigint)
                as pricing_failed_message_count,
            cast(coalesce(sum(pricing_not_applicable_message_count), 0) as bigint)
                as pricing_not_applicable_message_count
            """;

    private static final String LIVE_AGGREGATE_COLUMNS = """
            count(*)::bigint as assistant_message_count,
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'COMPLETED'
            )::bigint as completed_response_count,
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'REFUSED'
            )::bigint as refused_response_count,
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'INCOMPLETE'
            )::bigint as incomplete_response_count,
            count(*) filter (
                where m.status = 'FAILED'
            )::bigint as failed_message_count,
            coalesce(sum(
                case
                    when m.usage_status = 'AVAILABLE'
                        then coalesce(m.input_tokens, 0)
                    else 0
                end
            ), 0)::bigint as input_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'AVAILABLE'
                        then coalesce(m.output_tokens, 0)
                    else 0
                end
            ), 0)::bigint as output_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'PARTIAL'
                        then coalesce(m.input_tokens, 0)
                    else 0
                end
            ), 0)::bigint as partial_input_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'PARTIAL'
                        then coalesce(m.output_tokens, 0)
                    else 0
                end
            ), 0)::bigint as partial_output_tokens,
            count(*) filter (
                where m.usage_status = 'AVAILABLE'
            )::bigint as available_usage_message_count,
            count(*) filter (
                where m.usage_status = 'PARTIAL'
            )::bigint as partial_usage_message_count,
            count(*) filter (
                where m.usage_status = 'MISSING'
            )::bigint as missing_usage_message_count,
            count(*) filter (
                where m.usage_status = 'NOT_APPLICABLE'
            )::bigint as usage_not_applicable_message_count,
            coalesce(sum(
                case
                    when m.pricing_status in ('PRICED', 'FREE')
                        then coalesce(m.cost_usd, 0)
                    else 0
                end
            ), 0) as cost_usd,
            count(*) filter (
                where m.pricing_status = 'PRICED'
            )::bigint as priced_message_count,
            count(*) filter (
                where m.pricing_status = 'FREE'
            )::bigint as free_message_count,
            count(*) filter (
                where m.pricing_status = 'UNPRICED'
            )::bigint as unpriced_message_count,
            count(*) filter (
                where m.pricing_status = 'CALCULATION_FAILED'
            )::bigint as pricing_failed_message_count,
            count(*) filter (
                where m.pricing_status = 'NOT_APPLICABLE'
            )::bigint as pricing_not_applicable_message_count
            """;

    private static final String AGGREGATE_COLUMNS_WITHOUT_ASSISTANT = """
            cast(coalesce(sum(completed_response_count), 0) as bigint)
                as completed_response_count,
            cast(coalesce(sum(refused_response_count), 0) as bigint)
                as refused_response_count,
            cast(coalesce(sum(incomplete_response_count), 0) as bigint)
                as incomplete_response_count,
            cast(coalesce(sum(failed_message_count), 0) as bigint)
                as failed_message_count,
            cast(coalesce(sum(input_tokens), 0) as bigint)
                as input_tokens,
            cast(coalesce(sum(output_tokens), 0) as bigint)
                as output_tokens,
            cast(coalesce(sum(partial_input_tokens), 0) as bigint)
                as partial_input_tokens,
            cast(coalesce(sum(partial_output_tokens), 0) as bigint)
                as partial_output_tokens,
            cast(coalesce(sum(available_usage_message_count), 0) as bigint)
                as available_usage_message_count,
            cast(coalesce(sum(partial_usage_message_count), 0) as bigint)
                as partial_usage_message_count,
            cast(coalesce(sum(missing_usage_message_count), 0) as bigint)
                as missing_usage_message_count,
            cast(coalesce(sum(usage_not_applicable_message_count), 0) as bigint)
                as usage_not_applicable_message_count,
            coalesce(sum(cost_usd), 0) as known_cost_usd,
            cast(coalesce(sum(priced_message_count), 0) as bigint)
                as priced_message_count,
            cast(coalesce(sum(free_message_count), 0) as bigint)
                as free_message_count,
            cast(coalesce(sum(unpriced_message_count), 0) as bigint)
                as unpriced_message_count,
            cast(coalesce(sum(pricing_failed_message_count), 0) as bigint)
                as pricing_failed_message_count,
            cast(coalesce(sum(pricing_not_applicable_message_count), 0) as bigint)
                as pricing_not_applicable_message_count
            """;

    private static final String LIVE_AGGREGATE_COLUMNS_WITHOUT_ASSISTANT = """
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'COMPLETED'
            )::bigint as completed_response_count,
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'REFUSED'
            )::bigint as refused_response_count,
            count(*) filter (
                where m.status = 'COMPLETED'
                  and m.ai_response_status = 'INCOMPLETE'
            )::bigint as incomplete_response_count,
            count(*) filter (
                where m.status = 'FAILED'
            )::bigint as failed_message_count,
            coalesce(sum(
                case
                    when m.usage_status = 'AVAILABLE'
                        then coalesce(m.input_tokens, 0)
                    else 0
                end
            ), 0)::bigint as input_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'AVAILABLE'
                        then coalesce(m.output_tokens, 0)
                    else 0
                end
            ), 0)::bigint as output_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'PARTIAL'
                        then coalesce(m.input_tokens, 0)
                    else 0
                end
            ), 0)::bigint as partial_input_tokens,
            coalesce(sum(
                case
                    when m.usage_status = 'PARTIAL'
                        then coalesce(m.output_tokens, 0)
                    else 0
                end
            ), 0)::bigint as partial_output_tokens,
            count(*) filter (
                where m.usage_status = 'AVAILABLE'
            )::bigint as available_usage_message_count,
            count(*) filter (
                where m.usage_status = 'PARTIAL'
            )::bigint as partial_usage_message_count,
            count(*) filter (
                where m.usage_status = 'MISSING'
            )::bigint as missing_usage_message_count,
            count(*) filter (
                where m.usage_status = 'NOT_APPLICABLE'
            )::bigint as usage_not_applicable_message_count,
            coalesce(sum(
                case
                    when m.pricing_status in ('PRICED', 'FREE')
                        then coalesce(m.cost_usd, 0)
                    else 0
                end
            ), 0) as cost_usd,
            count(*) filter (
                where m.pricing_status = 'PRICED'
            )::bigint as priced_message_count,
            count(*) filter (
                where m.pricing_status = 'FREE'
            )::bigint as free_message_count,
            count(*) filter (
                where m.pricing_status = 'UNPRICED'
            )::bigint as unpriced_message_count,
            count(*) filter (
                where m.pricing_status = 'CALCULATION_FAILED'
            )::bigint as pricing_failed_message_count,
            count(*) filter (
                where m.pricing_status = 'NOT_APPLICABLE'
            )::bigint as pricing_not_applicable_message_count
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcUsageQueryRepository(
            UsageJdbcClients jdbcClients
    ) {
        this.jdbcTemplate = jdbcClients.query();
    }

    @Override
    public Slice<UsageSummaryResponse> findSummary(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan,
            Pageable pageable
    ) {
        FactSql facts = userFacts(criteria, plan);
        MapSqlParameterSource parameters = facts.parameters();
        addPage(parameters, pageable);

        String sql = facts.cte() + """
                select
                    user_id,
                    current_user_email,
                    model,
                """ + AGGREGATE_COLUMNS + """
                from facts
                group by
                    user_id,
                    current_user_email,
                    model
                order by
                    user_id asc,
                    model asc
                limit :limit
                offset :offset
                """;

        return slice(
                sql,
                parameters,
                pageable,
                this::mapSummary
        );
    }

    @Override
    public Slice<UsageUserSummaryResponse> findUsers(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan,
            Pageable pageable
    ) {
        FactSql facts = userFacts(criteria, plan);
        MapSqlParameterSource parameters = facts.parameters();
        addPage(parameters, pageable);

        String sql = facts.cte() + """
                select
                    user_id,
                    current_user_email,
                """ + AGGREGATE_COLUMNS + """
                from facts
                group by
                    user_id,
                    current_user_email
                order by user_id asc
                limit :limit
                offset :offset
                """;

        return slice(
                sql,
                parameters,
                pageable,
                this::mapUserSummary
        );
    }

    @Override
    public List<UsageModelSummaryResponse> findModels(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    ) {
        FactSql facts = organizationFacts(
                criteria,
                plan,
                false
        );

        String sql = facts.cte() + """
                select
                    model,
                """ + AGGREGATE_COLUMNS + """
                from facts
                group by model
                order by model asc
                """;

        return jdbcTemplate.query(
                sql,
                facts.parameters(),
                this::mapModelSummary
        );
    }

    @Override
    public List<UsageDailySummaryResponse> findDaily(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    ) {
        FactSql facts = organizationFacts(
                criteria,
                plan,
                true
        );

        String sql = facts.cte() + """
                select
                    usage_date,
                """ + AGGREGATE_COLUMNS + """
                from facts
                group by usage_date
                order by usage_date desc
                """;

        return jdbcTemplate.query(
                sql,
                facts.parameters(),
                this::mapDailySummary
        );
    }

    @Override
    public UsageDataQualityResponse findDataQuality(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    ) {
        FactSql qualityFacts = qualityFacts(
                criteria,
                plan
        );

        String qualitySql = qualityFacts.cte() + """
                select
                    cast(coalesce(sum(assistant_message_count), 0) as bigint)
                        as assistant_message_count,
                    cast(coalesce(sum(stored_completed_message_count), 0) as bigint)
                        as stored_completed_message_count,
                    cast(coalesce(sum(stored_failed_message_count), 0) as bigint)
                        as stored_failed_message_count,
                    cast(coalesce(sum(missing_model_message_count), 0) as bigint)
                        as missing_model_message_count,
                    cast(coalesce(sum(ambiguous_provider_operation_count), 0) as bigint)
                        as ambiguous_provider_operation_count,
                """ + AGGREGATE_COLUMNS_WITHOUT_ASSISTANT + """
                from facts
                """;

        QualityAggregate quality =
                jdbcTemplate.queryForObject(
                        qualitySql,
                        qualityFacts.parameters(),
                        this::mapQualityAggregate
                );

        if (quality == null) {
            throw new IllegalStateException(
                    "Usage data-quality query не вернул результат"
            );
        }

        List<UsageProblemModelResponse> problemModels =
                findProblemModels(criteria, plan);

        return new UsageDataQualityResponse(
                quality.assistantMessages(),
                quality.storedCompletedMessages(),
                quality.storedFailedMessages(),
                quality.missingModelMessages(),
                quality.ambiguousProviderOperations(),
                quality.usage(),
                quality.cost(),
                problemModels
        );
    }

    private List<UsageProblemModelResponse> findProblemModels(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    ) {
        FactSql facts = organizationFacts(
                criteria,
                plan,
                false
        );

        String sql = facts.cte() + """
                select
                    model,
                    cast(coalesce(sum(
                        partial_usage_message_count
                        + missing_usage_message_count
                    ), 0) as bigint) as usage_problems,
                    cast(coalesce(sum(
                        unpriced_message_count
                        + pricing_failed_message_count
                    ), 0) as bigint) as pricing_problems
                from facts
                group by model
                having coalesce(sum(
                    partial_usage_message_count
                    + missing_usage_message_count
                    + unpriced_message_count
                    + pricing_failed_message_count
                ), 0) > 0
                order by
                    coalesce(sum(
                        partial_usage_message_count
                        + missing_usage_message_count
                        + unpriced_message_count
                        + pricing_failed_message_count
                    ), 0) desc,
                    model asc
                limit 20
                """;

        return jdbcTemplate.query(
                sql,
                facts.parameters(),
                (resultSet, rowNumber) ->
                        new UsageProblemModelResponse(
                                resultSet.getString("model"),
                                resultSet.getLong(
                                        "usage_problems"
                                ),
                                resultSet.getLong(
                                        "pricing_problems"
                                )
                        )
        );
    }

    private FactSql userFacts(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    ) {
        MapSqlParameterSource parameters =
                baseParameters(criteria);

        List<String> fragments = new ArrayList<>();

        if (plan.hasRollupRange()) {
            parameters.addValue(
                    "rollupFrom",
                    plan.rollupFrom()
            );
            parameters.addValue(
                    "rollupToExclusive",
                    plan.rollupToExclusive()
            );

            StringBuilder where = new StringBuilder("""
                    where r.usage_date >= :rollupFrom
                      and r.usage_date < :rollupToExclusive
                    """);

            appendRollupScope(
                    where,
                    criteria,
                    true
            );

            fragments.add("""
                    select
                        r.user_id,
                        u.email as current_user_email,
                        r.model,
                        r.assistant_message_count,
                        r.completed_response_count,
                        r.refused_response_count,
                        r.incomplete_response_count,
                        r.failed_message_count,
                        r.input_tokens,
                        r.output_tokens,
                        r.partial_input_tokens,
                        r.partial_output_tokens,
                        r.available_usage_message_count,
                        r.partial_usage_message_count,
                        r.missing_usage_message_count,
                        r.usage_not_applicable_message_count,
                        r.cost_usd,
                        r.priced_message_count,
                        r.free_message_count,
                        r.unpriced_message_count,
                        r.pricing_failed_message_count,
                        r.pricing_not_applicable_message_count
                    from usage_daily_user_model_rollups r
                    join users u
                      on u.id = r.user_id
                    """ + where);
        }

        appendUserLiveFragments(
                fragments,
                parameters,
                criteria,
                plan.liveRanges()
        );

        return facts(
                fragments,
                parameters
        );
    }

    private FactSql organizationFacts(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan,
            boolean includeDate
    ) {
        MapSqlParameterSource parameters =
                baseParameters(criteria);

        List<String> fragments = new ArrayList<>();

        if (plan.hasRollupRange()) {
            parameters.addValue(
                    "rollupFrom",
                    plan.rollupFrom()
            );
            parameters.addValue(
                    "rollupToExclusive",
                    plan.rollupToExclusive()
            );

            StringBuilder where = new StringBuilder("""
                    where r.usage_date >= :rollupFrom
                      and r.usage_date < :rollupToExclusive
                    """);

            appendRollupScope(
                    where,
                    criteria,
                    false
            );

            fragments.add(
                    "select "
                            + (includeDate
                            ? "r.usage_date, "
                            : "")
                            + """
                            r.model,
                            r.assistant_message_count,
                            r.completed_response_count,
                            r.refused_response_count,
                            r.incomplete_response_count,
                            r.failed_message_count,
                            r.input_tokens,
                            r.output_tokens,
                            r.partial_input_tokens,
                            r.partial_output_tokens,
                            r.available_usage_message_count,
                            r.partial_usage_message_count,
                            r.missing_usage_message_count,
                            r.usage_not_applicable_message_count,
                            r.cost_usd,
                            r.priced_message_count,
                            r.free_message_count,
                            r.unpriced_message_count,
                            r.pricing_failed_message_count,
                            r.pricing_not_applicable_message_count
                            from usage_daily_org_model_rollups r
                            """
                            + where
            );
        }

        appendOrganizationLiveFragments(
                fragments,
                parameters,
                criteria,
                plan.liveRanges(),
                includeDate
        );

        return facts(
                fragments,
                parameters
        );
    }

    private FactSql qualityFacts(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    ) {
        MapSqlParameterSource parameters =
                baseParameters(criteria);

        List<String> fragments = new ArrayList<>();

        if (plan.hasRollupRange()) {
            parameters.addValue(
                    "rollupFrom",
                    plan.rollupFrom()
            );
            parameters.addValue(
                    "rollupToExclusive",
                    plan.rollupToExclusive()
            );

            StringBuilder where = new StringBuilder("""
                    where r.usage_date >= :rollupFrom
                      and r.usage_date < :rollupToExclusive
                    """);

            if (criteria.organizationId() != null) {
                where.append(
                        " and r.organization_id = :organizationId"
                );
            }

            fragments.add("""
                    select
                        r.assistant_message_count,
                        r.stored_completed_message_count,
                        r.stored_failed_message_count,
                        r.missing_model_message_count,
                        r.ambiguous_provider_operation_count,
                        0::bigint as completed_response_count,
                        0::bigint as refused_response_count,
                        0::bigint as incomplete_response_count,
                        r.stored_failed_message_count
                            as failed_message_count,
                        r.input_tokens,
                        r.output_tokens,
                        r.partial_input_tokens,
                        r.partial_output_tokens,
                        r.available_usage_message_count,
                        r.partial_usage_message_count,
                        r.missing_usage_message_count,
                        r.usage_not_applicable_message_count,
                        r.cost_usd,
                        r.priced_message_count,
                        r.free_message_count,
                        r.unpriced_message_count,
                        r.pricing_failed_message_count,
                        r.pricing_not_applicable_message_count
                    from usage_daily_quality_rollups r
                    """ + where);
        }

        int index = 0;

        for (UsageInstantRange range : plan.liveRanges()) {
            String fromName =
                    "qualityLiveFrom" + index;

            String toName =
                    "qualityLiveTo" + index;

            parameters.addValue(
                    fromName,
                    Timestamp.from(
                            range.from()
                    )
            );
            parameters.addValue(
                    toName,
                    Timestamp.from(
                            range.to()
                    )
            );

            StringBuilder where = new StringBuilder()
                    .append("where m.role = 'ASSISTANT' ")
                    .append("and m.created_at >= :")
                    .append(fromName)
                    .append(" and m.created_at < :")
                    .append(toName);

            if (criteria.organizationId() != null) {
                where.append(
                        " and m.organization_id = :organizationId"
                );
            }

            fragments.add("""
                    select
                        count(*)::bigint
                            as assistant_message_count,
                        count(*) filter (
                            where m.status = 'COMPLETED'
                        )::bigint
                            as stored_completed_message_count,
                        count(*) filter (
                            where m.status = 'FAILED'
                        )::bigint
                            as stored_failed_message_count,
                        count(*) filter (
                            where m.model is null
                        )::bigint
                            as missing_model_message_count,
                        0::bigint
                            as ambiguous_provider_operation_count,
                    """ + LIVE_AGGREGATE_COLUMNS_WITHOUT_ASSISTANT + """
                    from chat_messages m
                    """ + where);

            StringBuilder turnWhere = new StringBuilder()
                    .append("where t.state = 'AMBIGUOUS' ")
                    .append("and t.completed_at >= :")
                    .append(fromName)
                    .append(" and t.completed_at < :")
                    .append(toName);

            if (criteria.organizationId() != null) {
                turnWhere.append(
                        " and t.organization_id = :organizationId"
                );
            }

            fragments.add("""
                    select
                        0::bigint as assistant_message_count,
                        0::bigint as stored_completed_message_count,
                        0::bigint as stored_failed_message_count,
                        0::bigint as missing_model_message_count,
                        count(*)::bigint
                            as ambiguous_provider_operation_count,
                        0::bigint as completed_response_count,
                        0::bigint as refused_response_count,
                        0::bigint as incomplete_response_count,
                        0::bigint as failed_message_count,
                        0::bigint as input_tokens,
                        0::bigint as output_tokens,
                        0::bigint as partial_input_tokens,
                        0::bigint as partial_output_tokens,
                        0::bigint
                            as available_usage_message_count,
                        0::bigint
                            as partial_usage_message_count,
                        0::bigint
                            as missing_usage_message_count,
                        0::bigint
                            as usage_not_applicable_message_count,
                        0::numeric(30, 12)
                            as known_cost_usd,
                        0::bigint as priced_message_count,
                        0::bigint as free_message_count,
                        0::bigint as unpriced_message_count,
                        0::bigint
                            as pricing_failed_message_count,
                        0::bigint
                            as pricing_not_applicable_message_count
                    from chat_turns t
                    """ + turnWhere);

            index++;
        }

        return facts(
                fragments,
                parameters
        );
    }

    private void appendUserLiveFragments(
            List<String> fragments,
            MapSqlParameterSource parameters,
            UsageQueryCriteria criteria,
            List<UsageInstantRange> ranges
    ) {
        int index = 0;

        for (UsageInstantRange range : ranges) {
            String fromName =
                    "userLiveFrom" + index;

            String toName =
                    "userLiveTo" + index;

            parameters.addValue(
                    fromName,
                    Timestamp.from(
                            range.from()
                    )
            );
            parameters.addValue(
                    toName,
                    Timestamp.from(
                            range.to()
                    )
            );

            StringBuilder where = new StringBuilder()
                    .append("where m.role = 'ASSISTANT' ")
                    .append("and m.created_at >= :")
                    .append(fromName)
                    .append(" and m.created_at < :")
                    .append(toName);

            appendLiveScope(
                    where,
                    criteria,
                    true
            );

            fragments.add("""
                    select
                        s.user_id,
                        u.email as current_user_email,
                        coalesce(
                            m.model,
                            '__unattributed__'
                        ) as model,
                    """ + LIVE_AGGREGATE_COLUMNS + """
                    from chat_messages m
                    join chat_sessions s
                      on s.id = m.session_id
                    join users u
                      on u.id = s.user_id
                    """ + where + "\n" + """
                    group by
                        s.user_id,
                        u.email,
                        coalesce(
                            m.model,
                            '__unattributed__'
                        )
                    """);

            index++;
        }
    }

    private void appendOrganizationLiveFragments(
            List<String> fragments,
            MapSqlParameterSource parameters,
            UsageQueryCriteria criteria,
            List<UsageInstantRange> ranges,
            boolean includeDate
    ) {
        int index = 0;

        for (UsageInstantRange range : ranges) {
            String fromName =
                    "orgLiveFrom" + index;

            String toName =
                    "orgLiveTo" + index;

            parameters.addValue(
                    fromName,
                    Timestamp.from(
                            range.from()
                    )
            );
            parameters.addValue(
                    toName,
                    Timestamp.from(
                            range.to()
                    )
            );

            StringBuilder where = new StringBuilder()
                    .append("where m.role = 'ASSISTANT' ")
                    .append("and m.created_at >= :")
                    .append(fromName)
                    .append(" and m.created_at < :")
                    .append(toName);

            appendLiveScope(
                    where,
                    criteria,
                    false
            );

            String usageDate = includeDate
                    ? """
                    date(
                        m.created_at at time zone 'UTC'
                    ) as usage_date,
                    """
                    : "";

            String groupBy = includeDate
                    ? """
                    group by
                        date(
                            m.created_at at time zone 'UTC'
                        ),
                        coalesce(
                            m.model,
                            '__unattributed__'
                        )
                    """
                    : """
                    group by
                        coalesce(
                            m.model,
                            '__unattributed__'
                        )
                    """;

            fragments.add(
                    "select "
                            + usageDate
                            + """
                            coalesce(
                                m.model,
                                '__unattributed__'
                            ) as model,
                            """
                            + LIVE_AGGREGATE_COLUMNS
                            + """
                             from chat_messages m
                            """
                            + where
                            + "\n"
                            + groupBy
            );

            index++;
        }
    }

    private MapSqlParameterSource baseParameters(
            UsageQueryCriteria criteria
    ) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource();

        if (criteria.organizationId() != null) {
            parameters.addValue(
                    "organizationId",
                    criteria.organizationId()
            );
        }

        if (criteria.userId() != null) {
            parameters.addValue(
                    "userId",
                    criteria.userId()
            );
        }

        if (criteria.model() != null) {
            parameters.addValue(
                    "model",
                    criteria.model()
            );
        }

        return parameters;
    }

    private void appendRollupScope(
            StringBuilder where,
            UsageQueryCriteria criteria,
            boolean includeUser
    ) {
        if (criteria.organizationId() != null) {
            where.append(
                    " and r.organization_id = :organizationId"
            );
        }

        if (includeUser
                && criteria.userId() != null) {
            where.append(
                    " and r.user_id = :userId"
            );
        }

        if (criteria.model() != null) {
            where.append(
                    " and r.model = :model"
            );
        }
    }

    private void appendLiveScope(
            StringBuilder where,
            UsageQueryCriteria criteria,
            boolean includeUser
    ) {
        if (criteria.organizationId() != null) {
            where.append(
                    " and m.organization_id = :organizationId"
            );
        }

        if (includeUser
                && criteria.userId() != null) {
            where.append(
                    " and s.user_id = :userId"
            );
        }

        if (criteria.model() != null) {
            if (UNATTRIBUTED_MODEL.equals(
                    criteria.model()
            )) {
                where.append(
                        " and m.model is null"
                );
            } else {
                where.append(
                        " and m.model = :model"
                );
            }
        }
    }

    private FactSql facts(
            List<String> fragments,
            MapSqlParameterSource parameters
    ) {
        if (fragments.isEmpty()) {
            throw new IllegalArgumentException(
                    "Usage query plan не содержит источников данных"
            );
        }

        StringJoiner joiner = new StringJoiner(
                "\nunion all\n"
        );

        fragments.forEach(joiner::add);

        return new FactSql(
                "with facts as (\n"
                        + joiner
                        + "\n)\n",
                parameters
        );
    }

    private void addPage(
            MapSqlParameterSource parameters,
            Pageable pageable
    ) {
        parameters.addValue(
                "limit",
                Math.addExact(
                        pageable.getPageSize(),
                        1
                )
        );

        parameters.addValue(
                "offset",
                pageable.getOffset()
        );
    }

    private <T> Slice<T> slice(
            String sql,
            MapSqlParameterSource parameters,
            Pageable pageable,
            RowMapper<T> rowMapper
    ) {
        List<T> rows = jdbcTemplate.query(
                sql,
                parameters,
                rowMapper
        );

        boolean hasNext =
                rows.size() > pageable.getPageSize();

        List<T> content = hasNext
                ? List.copyOf(
                        rows.subList(
                                0,
                                pageable.getPageSize()
                        )
                )
                : List.copyOf(rows);

        return new SliceImpl<>(
                content,
                pageable,
                hasNext
        );
    }

    private UsageSummaryResponse mapSummary(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new UsageSummaryResponse(
                resultSet.getObject(
                        "user_id",
                        UUID.class
                ),
                resultSet.getString(
                        "current_user_email"
                ),
                resultSet.getString(
                        "model"
                ),
                mapResponses(resultSet),
                mapUsage(resultSet),
                mapCost(resultSet)
        );
    }

    private UsageUserSummaryResponse mapUserSummary(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new UsageUserSummaryResponse(
                resultSet.getObject(
                        "user_id",
                        UUID.class
                ),
                resultSet.getString(
                        "current_user_email"
                ),
                mapResponses(resultSet),
                mapUsage(resultSet),
                mapCost(resultSet)
        );
    }

    private UsageModelSummaryResponse mapModelSummary(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new UsageModelSummaryResponse(
                resultSet.getString("model"),
                mapResponses(resultSet),
                mapUsage(resultSet),
                mapCost(resultSet)
        );
    }

    private UsageDailySummaryResponse mapDailySummary(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new UsageDailySummaryResponse(
                resultSet.getObject(
                        "usage_date",
                        LocalDate.class
                ),
                "UTC",
                mapResponses(resultSet),
                mapUsage(resultSet),
                mapCost(resultSet)
        );
    }

    private QualityAggregate mapQualityAggregate(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new QualityAggregate(
                resultSet.getLong(
                        "assistant_message_count"
                ),
                resultSet.getLong(
                        "stored_completed_message_count"
                ),
                resultSet.getLong(
                        "stored_failed_message_count"
                ),
                resultSet.getLong(
                        "missing_model_message_count"
                ),
                resultSet.getLong(
                        "ambiguous_provider_operation_count"
                ),
                mapUsage(resultSet),
                mapCost(resultSet)
        );
    }

    private UsageResponseSummary mapResponses(
            ResultSet resultSet
    ) throws SQLException {
        return new UsageResponseSummary(
                resultSet.getLong(
                        "assistant_message_count"
                ),
                resultSet.getLong(
                        "completed_response_count"
                ),
                resultSet.getLong(
                        "refused_response_count"
                ),
                resultSet.getLong(
                        "incomplete_response_count"
                ),
                resultSet.getLong(
                        "failed_message_count"
                )
        );
    }

    private UsageTokenSummary mapUsage(
            ResultSet resultSet
    ) throws SQLException {
        return new UsageTokenSummary(
                resultSet.getLong(
                        "input_tokens"
                ),
                resultSet.getLong(
                        "output_tokens"
                ),
                resultSet.getLong(
                        "partial_input_tokens"
                ),
                resultSet.getLong(
                        "partial_output_tokens"
                ),
                resultSet.getLong(
                        "available_usage_message_count"
                ),
                resultSet.getLong(
                        "partial_usage_message_count"
                ),
                resultSet.getLong(
                        "missing_usage_message_count"
                ),
                resultSet.getLong(
                        "usage_not_applicable_message_count"
                )
        );
    }

    private UsageCostSummary mapCost(
            ResultSet resultSet
    ) throws SQLException {
        BigDecimal knownCost =
                resultSet.getBigDecimal(
                        "known_cost_usd"
                );

        return new UsageCostSummary(
                knownCost,
                resultSet.getLong(
                        "priced_message_count"
                ),
                resultSet.getLong(
                        "free_message_count"
                ),
                resultSet.getLong(
                        "unpriced_message_count"
                ),
                resultSet.getLong(
                        "pricing_failed_message_count"
                ),
                resultSet.getLong(
                        "pricing_not_applicable_message_count"
                )
        );
    }

    private record FactSql(
            String cte,
            MapSqlParameterSource parameters
    ) {
    }

    private record QualityAggregate(
            long assistantMessages,
            long storedCompletedMessages,
            long storedFailedMessages,
            long missingModelMessages,
            long ambiguousProviderOperations,
            UsageTokenSummary usage,
            UsageCostSummary cost
    ) {
    }
}