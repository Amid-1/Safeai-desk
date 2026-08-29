package ru.safeai.gateway.usage.repository;

import org.springframework.jdbc.core.RowMapper;
import ru.safeai.gateway.usage.dto.UsageCostSummary;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageProblemModelResponse;
import ru.safeai.gateway.usage.dto.UsageResponseSummary;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageTokenSummary;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

final class UsageRowMapperSupport {

    private static final RowMapper<UsageSummaryResponse>
            SUMMARY_MAPPER = rowMapper(
            UsageRowMapperSupport::mapSummary
    );

    private static final RowMapper<UsageUserSummaryResponse>
            USER_SUMMARY_MAPPER = rowMapper(
            UsageRowMapperSupport::mapUserSummary
    );

    private static final RowMapper<UsageModelSummaryResponse>
            MODEL_SUMMARY_MAPPER = rowMapper(
            UsageRowMapperSupport::mapModelSummary
    );

    private static final RowMapper<UsageDailySummaryResponse>
            DAILY_SUMMARY_MAPPER = rowMapper(
            UsageRowMapperSupport::mapDailySummary
    );

    private static final RowMapper<UsageProblemModelResponse>
            PROBLEM_MODEL_MAPPER = rowMapper(
            UsageRowMapperSupport::mapProblemModel
    );

    private static final RowMapper<QualityAggregate>
            QUALITY_AGGREGATE_MAPPER = rowMapper(
            UsageRowMapperSupport::mapQualityAggregate
    );

    private UsageRowMapperSupport() {
    }

    static RowMapper<UsageSummaryResponse> summaryMapper() {
        return SUMMARY_MAPPER;
    }

    static RowMapper<UsageUserSummaryResponse> userSummaryMapper() {
        return USER_SUMMARY_MAPPER;
    }

    static RowMapper<UsageModelSummaryResponse> modelSummaryMapper() {
        return MODEL_SUMMARY_MAPPER;
    }

    static RowMapper<UsageDailySummaryResponse> dailySummaryMapper() {
        return DAILY_SUMMARY_MAPPER;
    }

    static RowMapper<UsageProblemModelResponse> problemModelMapper() {
        return PROBLEM_MODEL_MAPPER;
    }

    static RowMapper<QualityAggregate> qualityAggregateMapper() {
        return QUALITY_AGGREGATE_MAPPER;
    }

    private static UsageSummaryResponse mapSummary(
            ResultSet resultSet
    ) throws SQLException {
        return new UsageSummaryResponse(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("current_user_email"),
                resultSet.getString("model"),
                mapResponses(resultSet),
                mapUsage(resultSet),
                mapCost(resultSet)
        );
    }

    private static UsageUserSummaryResponse mapUserSummary(
            ResultSet resultSet
    ) throws SQLException {
        return new UsageUserSummaryResponse(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("current_user_email"),
                mapResponses(resultSet),
                mapUsage(resultSet),
                mapCost(resultSet)
        );
    }

    private static UsageModelSummaryResponse mapModelSummary(
            ResultSet resultSet
    ) throws SQLException {
        return new UsageModelSummaryResponse(
                resultSet.getString("model"),
                mapResponses(resultSet),
                mapUsage(resultSet),
                mapCost(resultSet)
        );
    }

    private static UsageDailySummaryResponse mapDailySummary(
            ResultSet resultSet
    ) throws SQLException {
        return new UsageDailySummaryResponse(
                resultSet.getObject("usage_date", LocalDate.class),
                "UTC",
                mapResponses(resultSet),
                mapUsage(resultSet),
                mapCost(resultSet)
        );
    }

    private static UsageProblemModelResponse mapProblemModel(
            ResultSet resultSet
    ) throws SQLException {
        return new UsageProblemModelResponse(
                resultSet.getString("model"),
                resultSet.getLong("usage_problems"),
                resultSet.getLong("pricing_problems")
        );
    }

    private static QualityAggregate mapQualityAggregate(
            ResultSet resultSet
    ) throws SQLException {
        return new QualityAggregate(
                resultSet.getLong("assistant_message_count"),
                resultSet.getLong("stored_completed_message_count"),
                resultSet.getLong("stored_failed_message_count"),
                resultSet.getLong("missing_model_message_count"),
                resultSet.getLong("ambiguous_provider_operation_count"),
                mapUsage(resultSet),
                mapCost(resultSet)
        );
    }

    private static UsageResponseSummary mapResponses(
            ResultSet resultSet
    ) throws SQLException {
        return new UsageResponseSummary(
                resultSet.getLong("assistant_message_count"),
                resultSet.getLong("completed_response_count"),
                resultSet.getLong("refused_response_count"),
                resultSet.getLong("incomplete_response_count"),
                resultSet.getLong("failed_message_count")
        );
    }

    private static UsageTokenSummary mapUsage(
            ResultSet resultSet
    ) throws SQLException {
        return new UsageTokenSummary(
                resultSet.getLong("input_tokens"),
                resultSet.getLong("output_tokens"),
                resultSet.getLong("partial_input_tokens"),
                resultSet.getLong("partial_output_tokens"),
                resultSet.getLong("available_usage_message_count"),
                resultSet.getLong("partial_usage_message_count"),
                resultSet.getLong("missing_usage_message_count"),
                resultSet.getLong("usage_not_applicable_message_count")
        );
    }

    private static UsageCostSummary mapCost(
            ResultSet resultSet
    ) throws SQLException {
        BigDecimal knownCost = resultSet.getBigDecimal(
                "known_cost_usd"
        );

        return new UsageCostSummary(
                knownCost,
                resultSet.getLong("priced_message_count"),
                resultSet.getLong("free_message_count"),
                resultSet.getLong("unpriced_message_count"),
                resultSet.getLong("pricing_failed_message_count"),
                resultSet.getLong("pricing_not_applicable_message_count")
        );
    }

    private static <T> RowMapper<T> rowMapper(
            ResultSetMapper<T> delegate
    ) {
        return (resultSet, ignoredRowNumber) ->
                delegate.map(resultSet);
    }

    @FunctionalInterface
    private interface ResultSetMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }

    record QualityAggregate(
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
