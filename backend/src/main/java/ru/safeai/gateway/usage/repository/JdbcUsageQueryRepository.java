package ru.safeai.gateway.usage.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.usage.config.UsageJdbcClients;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageDataQualityResponse;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageProblemModelResponse;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;

import java.util.List;
import java.util.Objects;

import static ru.safeai.gateway.usage.repository.UsageSqlFragments.AGGREGATE_COLUMNS;
import static ru.safeai.gateway.usage.repository.UsageSqlFragments.AGGREGATE_COLUMNS_WITHOUT_ASSISTANT;

@Repository
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
public class JdbcUsageQueryRepository
        implements UsageQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final UsageFactQueryBuilder factQueryBuilder =
            new UsageFactQueryBuilder();

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
        UsageFactSql facts = factQueryBuilder.userFacts(
                criteria,
                plan
        );
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
                UsageRowMapperSupport.summaryMapper()
        );
    }

    @Override
    public Slice<UsageUserSummaryResponse> findUsers(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan,
            Pageable pageable
    ) {
        UsageFactSql facts = factQueryBuilder.userFacts(
                criteria,
                plan
        );
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
                UsageRowMapperSupport.userSummaryMapper()
        );
    }

    @Override
    public List<UsageModelSummaryResponse> findModels(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    ) {
        UsageFactSql facts = factQueryBuilder.organizationFacts(
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

        return executeInternallyGeneratedQuery(
                sql,
                facts.parameters(),
                UsageRowMapperSupport.modelSummaryMapper()
        );
    }

    @Override
    public List<UsageDailySummaryResponse> findDaily(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    ) {
        UsageFactSql facts = factQueryBuilder.organizationFacts(
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

        return executeInternallyGeneratedQuery(
                sql,
                facts.parameters(),
                UsageRowMapperSupport.dailySummaryMapper()
        );
    }

    @Override
    public UsageDataQualityResponse findDataQuality(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    ) {
        UsageFactSql qualityFacts = factQueryBuilder.qualityFacts(
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

        UsageRowMapperSupport.QualityAggregate quality =
                executeRequiredInternallyGeneratedQueryForObject(
                        qualitySql,
                        qualityFacts.parameters(),
                        UsageRowMapperSupport.qualityAggregateMapper()
                );

        return new UsageDataQualityResponse(
                quality.assistantMessages(),
                quality.storedCompletedMessages(),
                quality.storedFailedMessages(),
                quality.missingModelMessages(),
                quality.ambiguousProviderOperations(),
                quality.usage(),
                quality.cost(),
                findProblemModels(criteria, plan)
        );
    }

    private List<UsageProblemModelResponse> findProblemModels(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    ) {
        UsageFactSql facts = factQueryBuilder.organizationFacts(
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

        return executeInternallyGeneratedQuery(
                sql,
                facts.parameters(),
                UsageRowMapperSupport.problemModelMapper()
        );
    }

    private static void addPage(
            MapSqlParameterSource parameters,
            Pageable pageable
    ) {
        parameters.addValue(
                "limit",
                Math.addExact(pageable.getPageSize(), 1)
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
            RowMapper<T> mapper
    ) {
        List<T> rows = executeInternallyGeneratedQuery(
                sql,
                parameters,
                mapper
        );

        boolean hasNext = rows.size() > pageable.getPageSize();
        List<T> content = hasNext
                ? List.copyOf(
                        rows.subList(0, pageable.getPageSize())
                )
                : List.copyOf(rows);

        return new SliceImpl<>(
                content,
                pageable,
                hasNext
        );
    }

    /**
     * SQL passed to this boundary is assembled only from static repository
     * fragments and structural choices made by {@link UsageFactQueryBuilder}.
     * User-controlled filter values are never concatenated into the SQL text;
     * they are carried exclusively through {@link MapSqlParameterSource}.
     * <p>
     * The boundary exists to keep internally generated SQL execution in one
     * place without weakening SQL inspections for unrelated repository code.
     */
    private <T> List<T> executeInternallyGeneratedQuery(
            String sql,
            MapSqlParameterSource parameters,
            RowMapper<T> mapper
    ) {
        return jdbcTemplate.query(
                sql,
                parameters,
                mapper
        );
    }

    /**
     * Single-row counterpart of the trusted query execution boundary with
     * the same internally-generated-SQL invariant.
     */
    private <T> T executeRequiredInternallyGeneratedQueryForObject(
            String sql,
            MapSqlParameterSource parameters,
            RowMapper<T> mapper
    ) {
        return Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        sql,
                        parameters,
                        mapper
                ),
                "Required usage aggregate query returned null"
        );
    }
}
