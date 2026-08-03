package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.usage.service.UsageRollupDayProcessor;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.chat.quota.enabled=true",
        "safeai.usage.rollup.enabled=false",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@Import(ChatIntegrationClockConfiguration.class)
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
class ChatUsageQualityIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    private static final LocalDate TEST_DAY =
            LocalDate.of(2026, 6, 12);

    @Autowired
    private UsageRollupDayProcessor rollupDayProcessor;

    @Test
    void ambiguousProviderOperationIsVisibleInUsageQualityRollup() {
        insertAmbiguousTurn();

        rollupDayProcessor.rebuildDay(
                TEST_DAY,
                false
        );

        Long ambiguous = jdbcTemplate.queryForObject(
                """
                select ambiguous_provider_operation_count
                from usage_daily_quality_rollups
                where usage_date = date '2026-06-12'
                  and organization_id = ?
                """,
                Long.class,
                ORGANIZATION_ID
        );

        assertThat(ambiguous)
                .isEqualTo(1L);
    }

    @Test
    void ambiguousQualityRebuildIsIdempotent() {
        insertAmbiguousTurn();

        rollupDayProcessor.rebuildDay(
                TEST_DAY,
                false
        );

        rollupDayProcessor.rebuildDay(
                TEST_DAY,
                false
        );

        Long rows = jdbcTemplate.queryForObject(
                """
                select count(*)
                from usage_daily_quality_rollups
                where usage_date = date '2026-06-12'
                  and organization_id = ?
                """,
                Long.class,
                ORGANIZATION_ID
        );

        Long ambiguous = jdbcTemplate.queryForObject(
                """
                select sum(ambiguous_provider_operation_count)
                from usage_daily_quality_rollups
                where usage_date = date '2026-06-12'
                  and organization_id = ?
                """,
                Long.class,
                ORGANIZATION_ID
        );

        assertThat(rows)
                .isEqualTo(1L);

        assertThat(ambiguous)
                .isEqualTo(1L);
    }

    @Test
    void failedPreCallTurnDoesNotInflateAmbiguousOperations() {
        insertFailedTurn();

        rollupDayProcessor.rebuildDay(
                TEST_DAY,
                false
        );

        Long ambiguous = jdbcTemplate.queryForObject(
                """
                select coalesce(
                    sum(ambiguous_provider_operation_count),
                    0
                )
                from usage_daily_quality_rollups
                where usage_date = date '2026-06-12'
                  and organization_id = ?
                """,
                Long.class,
                ORGANIZATION_ID
        );

        assertThat(ambiguous)
                .isZero();
    }

    private void insertAmbiguousTurn() {
        UUID clientRequestId = UUID.randomUUID();

        UUID userMessageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientRequestId,
                "Question",
                NOW
        );

        jdbcTemplate.update(
                """
                insert into chat_turns (
                    id,
                    session_id,
                    organization_id,
                    user_id,
                    client_request_id,
                    request_content_hash,
                    provider_operation_id,
                    user_message_id,
                    state,
                    provider_call_started_at,
                    provider,
                    provider_request_id,
                    provider_error_type,
                    failure_code,
                    outcome_ambiguous,
                    created_at,
                    updated_at,
                    completed_at,
                    version
                ) values (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    'AMBIGUOUS',
                    ?,
                    'openai',
                    'provider-request-id',
                    'TIMEOUT',
                    'AI_PROVIDER_OUTCOME_AMBIGUOUS',
                    true,
                    ?,
                    ?,
                    ?,
                    0
                )
                """,
                UUID.randomUUID(),
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                clientRequestId,
                "c".repeat(64),
                UUID.randomUUID(),
                userMessageId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(
                        NOW.plusSeconds(1)
                ),
                Timestamp.from(
                        NOW.plusSeconds(1)
                )
        );
    }

    private void insertFailedTurn() {
        UUID clientRequestId = UUID.randomUUID();

        UUID userMessageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientRequestId,
                "Question",
                NOW
        );

        jdbcTemplate.update(
                """
                insert into chat_turns (
                    id,
                    session_id,
                    organization_id,
                    user_id,
                    client_request_id,
                    request_content_hash,
                    provider_operation_id,
                    user_message_id,
                    state,
                    provider,
                    provider_error_type,
                    failure_code,
                    outcome_ambiguous,
                    created_at,
                    updated_at,
                    completed_at,
                    version
                ) values (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    'FAILED',
                    'mock',
                    'PROVIDER_CALL_NOT_STARTED',
                    'STALE_BEFORE_PROVIDER_CALL',
                    false,
                    ?,
                    ?,
                    ?,
                    0
                )
                """,
                UUID.randomUUID(),
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                clientRequestId,
                "d".repeat(64),
                UUID.randomUUID(),
                userMessageId,
                Timestamp.from(NOW),
                Timestamp.from(
                        NOW.plusSeconds(1)
                ),
                Timestamp.from(
                        NOW.plusSeconds(1)
                )
        );
    }
}