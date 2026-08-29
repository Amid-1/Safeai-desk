package ru.safeai.gateway.usage.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import static ru.safeai.gateway.usage.repository.UsageSqlFragments.*;

final class UsageFactQueryBuilder {

    UsageFactSql userFacts(
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

    UsageFactSql organizationFacts(
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

    UsageFactSql qualityFacts(
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

    private UsageFactSql facts(
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

        return new UsageFactSql(
                "with facts as (\n"
                        + joiner
                        + "\n)\n",
                parameters
        );
    }

}
