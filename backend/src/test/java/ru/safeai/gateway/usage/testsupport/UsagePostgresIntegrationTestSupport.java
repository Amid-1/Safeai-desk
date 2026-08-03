package ru.safeai.gateway.usage.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;
import ru.safeai.gateway.usage.repository.UsageInstantRange;
import ru.safeai.gateway.usage.repository.UsageQueryCriteria;
import ru.safeai.gateway.usage.repository.UsageQueryPlan;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
public abstract class UsagePostgresIntegrationTestSupport
        extends AbstractPostgresIntegrationTest {

    protected static final UUID ORGANIZATION_A_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
            );

    protected static final UUID ORGANIZATION_B_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
            );

    protected static final UUID USER_A_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1"
            );

    protected static final UUID USER_B_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2"
            );

    protected static final UUID SESSION_A_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-ccccccccccc1"
            );

    protected static final UUID SESSION_B_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-ccccccccccc2"
            );

    protected static final Instant RANGE_FROM =
            Instant.parse(
                    "2026-06-01T00:00:00Z"
            );

    protected static final Instant RANGE_TO =
            Instant.parse(
                    "2026-07-01T00:00:00Z"
            );

    protected static final Pageable PAGEABLE =
            PageRequest.of(0, 50);

    @BeforeEach
    void prepareUsageFixtures() {
        truncateUsageRollupTables();
        resetRollupWatermark();
        insertUsageOrganizations();
        insertUsageUsers();
        insertUsageSessions();
    }

    protected UUID insertCompletedAssistant(
            UUID sessionId,
            UUID organizationId,
            String model,
            String aiResponseStatus,
            Integer inputTokens,
            Integer outputTokens,
            String usageStatus,
            BigDecimal costUsd,
            String pricingStatus,
            Instant createdAt
    ) {
        UUID messageId = UUID.randomUUID();

        String currency = switch (pricingStatus) {
            case "PRICED", "FREE" -> "USD";
            default -> null;
        };

        String pricingVersion = switch (pricingStatus) {
            case "PRICED", "FREE" -> "test-pricing-v1";
            default -> null;
        };

        Timestamp pricingCalculatedAt =
                "NOT_APPLICABLE".equals(pricingStatus)
                        ? null
                        : Timestamp.from(createdAt);

        jdbcTemplate.update(
                """
                insert into public.chat_messages (
                    id,
                    session_id,
                    organization_id,
                    role,
                    content,
                    model,
                    input_tokens,
                    output_tokens,
                    cost_usd,
                    status,
                    created_at,
                    usage_status,
                    pricing_status,
                    currency,
                    pricing_version,
                    pricing_calculated_at,
                    provider_message_id,
                    ai_response_status,
                    finish_reason
                ) values (
                    ?,
                    ?,
                    ?,
                    'ASSISTANT',
                    'Usage integration response',
                    ?,
                    ?,
                    ?,
                    ?,
                    'COMPLETED',
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?
                )
                """,
                messageId,
                sessionId,
                organizationId,
                model,
                inputTokens,
                outputTokens,
                costUsd,
                Timestamp.from(createdAt),
                usageStatus,
                pricingStatus,
                currency,
                pricingVersion,
                pricingCalculatedAt,
                UUID.randomUUID().toString(),
                aiResponseStatus,
                aiResponseStatus.toLowerCase()
        );

        return messageId;
    }

    protected void insertFailedAssistant(
            Instant createdAt
    ) {
        jdbcTemplate.update(
                """
                insert into public.chat_messages (
                    id,
                    session_id,
                    organization_id,
                    role,
                    content,
                    model,
                    input_tokens,
                    output_tokens,
                    cost_usd,
                    status,
                    created_at,
                    usage_status,
                    pricing_status,
                    currency,
                    pricing_version,
                    pricing_calculated_at,
                    provider_message_id,
                    ai_response_status,
                    finish_reason
                ) values (
                    ?,
                    ?,
                    ?,
                    'ASSISTANT',
                    'Provider call failed',
                    null,
                    null,
                    null,
                    null,
                    'FAILED',
                    ?,
                    'NOT_APPLICABLE',
                    'NOT_APPLICABLE',
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
                )
                """,
                UUID.randomUUID(),
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                Timestamp.from(createdAt)
        );
    }

    protected UsageQueryCriteria criteria(
            UUID organizationId,
            UUID userId,
            String model,
            Instant from,
            Instant to
    ) {
        return new UsageQueryCriteria(
                from,
                to,
                organizationId,
                userId,
                model
        );
    }

    protected UsageQueryPlan livePlan(
            Instant from,
            Instant to
    ) {
        return new UsageQueryPlan(
                null,
                null,
                List.of(
                        new UsageInstantRange(
                                from,
                                to
                        )
                )
        );
    }

    protected UsageQueryPlan rollupPlan(
            LocalDate date
    ) {
        return new UsageQueryPlan(
                date,
                date.plusDays(1),
                List.of()
        );
    }

    protected UsageQueryPlan hybridPlan(
            LocalDate rollupFrom,
            LocalDate rollupToExclusive,
            Instant liveFrom,
            Instant liveTo
    ) {
        return new UsageQueryPlan(
                rollupFrom,
                rollupToExclusive,
                List.of(
                        new UsageInstantRange(
                                liveFrom,
                                liveTo
                        )
                )
        );
    }

    protected Instant utcStart(
            LocalDate date
    ) {
        return date.atStartOfDay()
                .toInstant(ZoneOffset.UTC);
    }

    protected long countRows(
            String tableName
    ) {
        Long count = switch (tableName) {
            case "usage_daily_quality_rollups" ->
                    jdbcTemplate.queryForObject(
                            """
                            select count(*)
                            from public.usage_daily_quality_rollups
                            """,
                            Long.class
                    );

            case "usage_daily_user_model_rollups" ->
                    jdbcTemplate.queryForObject(
                            """
                            select count(*)
                            from public.usage_daily_user_model_rollups
                            """,
                            Long.class
                    );

            case "usage_daily_org_model_rollups" ->
                    jdbcTemplate.queryForObject(
                            """
                            select count(*)
                            from public.usage_daily_org_model_rollups
                            """,
                            Long.class
                    );

            case "usage_rollup_state" ->
                    jdbcTemplate.queryForObject(
                            """
                            select count(*)
                            from public.usage_rollup_state
                            """,
                            Long.class
                    );

            case "chat_messages" ->
                    jdbcTemplate.queryForObject(
                            """
                            select count(*)
                            from public.chat_messages
                            """,
                            Long.class
                    );

            case "chat_turns" ->
                    jdbcTemplate.queryForObject(
                            """
                            select count(*)
                            from public.chat_turns
                            """,
                            Long.class
                    );

            default -> throw new IllegalArgumentException(
                    "Недопустимое имя таблицы: "
                            + tableName
            );
        };

        return count == null
                ? 0L
                : count;
    }

    private void truncateUsageRollupTables() {
        jdbcTemplate.execute(
                """
                truncate table
                    public.usage_daily_quality_rollups,
                    public.usage_daily_user_model_rollups,
                    public.usage_daily_org_model_rollups
                cascade
                """
        );
    }

    private void resetRollupWatermark() {
        jdbcTemplate.update(
                """
                update public.usage_rollup_state
                set last_completed_date = null,
                    updated_at = current_timestamp
                where job_name = 'usage-daily-rollup'
                """
        );
    }

    private void insertUsageOrganizations() {
        insertOrganization(
                ORGANIZATION_A_ID,
                "Usage Organization A",
                true
        );

        insertOrganization(
                ORGANIZATION_B_ID,
                "Usage Organization B",
                true
        );
    }

    private void insertUsageUsers() {
        insertUser(
                USER_A_ID,
                ORGANIZATION_A_ID,
                "usage-a@test.com",
                true,
                "USER",
                RANGE_FROM
        );

        insertUser(
                USER_B_ID,
                ORGANIZATION_B_ID,
                "usage-b@test.com",
                true,
                "USER",
                RANGE_FROM
        );
    }

    private void insertUsageSessions() {
        insertChatSession(
                SESSION_A_ID,
                USER_A_ID,
                ORGANIZATION_A_ID
        );

        insertChatSession(
                SESSION_B_ID,
                USER_B_ID,
                ORGANIZATION_B_ID
        );
    }
}