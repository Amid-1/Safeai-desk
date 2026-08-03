package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@SpringBootTest(properties = "safeai.usage.rollup.enabled=false")
@ActiveProfiles("test")
class UsageMigrationIntegrityIntegrationTest
        extends AbstractPostgresIntegrationTest {

    @Test
    void usageAnalyticsTablesAndColumnsExist() {
        Integer qualityTable = jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.tables
                where table_schema = 'public'
                  and table_name = 'usage_daily_quality_rollups'
                """,
                Integer.class
        );
        Integer stateTable = jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.tables
                where table_schema = 'public'
                  and table_name = 'usage_rollup_state'
                """,
                Integer.class
        );

        assertThat(qualityTable).isEqualTo(1);
        assertThat(stateTable).isEqualTo(1);

        List<String> userColumns = jdbcTemplate.queryForList(
                """
                select column_name
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'usage_daily_user_model_rollups'
                """,
                String.class
        );

        assertThat(userColumns).contains(
                "completed_response_count",
                "refused_response_count",
                "incomplete_response_count",
                "partial_input_tokens",
                "partial_output_tokens",
                "available_usage_message_count",
                "partial_usage_message_count",
                "missing_usage_message_count",
                "usage_not_applicable_message_count",
                "priced_message_count",
                "free_message_count",
                "unpriced_message_count",
                "pricing_failed_message_count",
                "pricing_not_applicable_message_count"
        );
    }

    @Test
    void sourceAndRollupCostColumnsPreserveScaleTwelve() {
        List<String> precisionRows = jdbcTemplate.queryForList(
                """
                select table_name || ':'
                    || numeric_precision || ':'
                    || numeric_scale
                from information_schema.columns
                where table_schema = 'public'
                  and column_name = 'cost_usd'
                  and table_name in (
                      'chat_messages',
                      'usage_daily_org_model_rollups',
                      'usage_daily_user_model_rollups',
                      'usage_daily_quality_rollups'
                  )
                order by table_name
                """,
                String.class
        );

        assertThat(precisionRows).containsExactly(
                "chat_messages:30:12",
                "usage_daily_org_model_rollups:30:12",
                "usage_daily_quality_rollups:30:12",
                "usage_daily_user_model_rollups:30:12"
        );
    }

    @Test
    void usageConstraintsExistAndAreValidated() {
        List<String> validatedConstraints = jdbcTemplate.queryForList(
                """
                select constraint_row.conname
                from pg_constraint constraint_row
                join pg_class table_row
                  on table_row.oid = constraint_row.conrelid
                join pg_namespace namespace_row
                  on namespace_row.oid = table_row.relnamespace
                where namespace_row.nspname = 'public'
                  and table_row.relname = 'chat_messages'
                  and constraint_row.convalidated = true
                """,
                String.class
        );

        assertThat(validatedConstraints).contains(
                "chk_chat_messages_pricing_currency_usd",
                "chk_chat_messages_reserved_usage_model"
        );
    }

    @Test
    void productionIndexesExist() {
        List<String> indexes = jdbcTemplate.queryForList(
                """
                select indexname
                from pg_indexes
                where schemaname = 'public'
                """,
                String.class
        );

        assertThat(indexes).contains(
                "idx_chat_messages_usage_live_org_date_model",
                "idx_chat_messages_usage_live_date_model",
                "idx_usage_daily_quality_org_date",
                "idx_usage_daily_quality_date",
                "idx_chat_messages_usage_live_session_date_model"
        );
    }

    @Test
    void rollupCounterConstraintsRejectInconsistentRows() {
        org.springframework.dao.DataIntegrityViolationException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        () -> jdbcTemplate.update("""
                                insert into public.usage_daily_org_model_rollups (
                                    usage_date,
                                    organization_id,
                                    model,
                                    assistant_message_count,
                                    available_usage_message_count,
                                    priced_message_count,
                                    created_at,
                                    updated_at
                                ) values (
                                    current_date,
                                    ?,
                                    'invalid-rollup',
                                    2,
                                    1,
                                    2,
                                    current_timestamp,
                                    current_timestamp
                                )
                                """,
                                PLATFORM_ORGANIZATION_ID
                        )
                );

        assertThat(exception.getMessage())
                .contains("chk_usage_daily_org_model_usage_counts");
    }

    @Test
    void latestUsageMigrationsAreRecordedByFlyway() {
        List<String> versions = jdbcTemplate.queryForList(
                """
                select version
                from public.flyway_schema_history
                where success = true
                order by installed_rank
                """,
                String.class
        );

        assertThat(versions).contains("28", "29", "30", "31");
    }

}
