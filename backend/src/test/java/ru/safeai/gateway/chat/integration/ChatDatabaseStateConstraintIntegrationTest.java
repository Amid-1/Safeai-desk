package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.chat.quota.enabled=true",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@Import(ChatIntegrationClockConfiguration.class)
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
class ChatDatabaseStateConstraintIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    @Test
    void succeededTurnWithoutRequestedAndResolvedModelIsRejected() {
        UUID clientRequestId = UUID.randomUUID();
        UUID userMessageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientRequestId,
                "Question",
                NOW.minusSeconds(2)
        );
        UUID assistantMessageId = insertCompletedAssistant(
                CHAT_ID,
                ORGANIZATION_ID,
                userMessageId,
                "Answer",
                "COMPLETED",
                NOW.minusSeconds(1)
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into public.chat_turns (
                    id, session_id, organization_id, user_id,
                    client_request_id, request_content_hash,
                    provider_operation_id, user_message_id,
                    assistant_message_id, state, processing_token,
                    lease_until, provider_call_started_at, provider,
                    requested_model, resolved_model, outcome_ambiguous,
                    created_at, updated_at, completed_at, version
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED', null,
                    null, ?, 'mock', null, null, false, ?, ?, ?, 0
                )
                """,
                UUID.randomUUID(),
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                clientRequestId,
                "1".repeat(64),
                UUID.randomUUID(),
                userMessageId,
                assistantMessageId,
                Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.minusSeconds(2)),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ambiguousTurnWithoutProviderCallMarkerIsRejected() {
        UUID clientRequestId = UUID.randomUUID();
        UUID userMessageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientRequestId,
                "Question",
                NOW.minusSeconds(2)
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into public.chat_turns (
                    id, session_id, organization_id, user_id,
                    client_request_id, request_content_hash,
                    provider_operation_id, user_message_id,
                    state, processing_token, lease_until,
                    provider_call_started_at, provider,
                    provider_error_type, failure_code,
                    outcome_ambiguous, created_at, updated_at,
                    completed_at, version
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, 'AMBIGUOUS', null, null,
                    null, 'mock', 'TIMEOUT', 'AI_PROVIDER_OUTCOME_AMBIGUOUS',
                    true, ?, ?, ?, 0
                )
                """,
                UUID.randomUUID(),
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                clientRequestId,
                "2".repeat(64),
                UUID.randomUUID(),
                userMessageId,
                Timestamp.from(NOW.minusSeconds(2)),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void settledPricedQuotaWithoutActualCostIsRejected() {
        UUID turnId = processingTurnWithUserMessage();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into public.chat_quota_reservations (
                    turn_id, organization_id, user_id, period_start,
                    state, reserved_input_tokens, reserved_output_tokens,
                    reserved_cost_usd, actual_input_tokens,
                    actual_output_tokens, actual_cost_usd,
                    usage_status, pricing_status,
                    created_at, updated_at, settled_at
                ) values (
                    ?, ?, ?, ?, 'SETTLED', 16000, 2048,
                    1.000000000000, 10, 20, null,
                    'AVAILABLE', 'PRICED', ?, ?, ?
                )
                """,
                turnId,
                ORGANIZATION_ID,
                USER_ID,
                Date.valueOf(LocalDate.of(2026, 6, 1)),
                Timestamp.from(NOW.minusSeconds(2)),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unpricedQuotaCannotMasqueradeAsKnownZeroCost() {
        UUID turnId = processingTurnWithUserMessage();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into public.chat_quota_reservations (
                    turn_id, organization_id, user_id, period_start,
                    state, reserved_input_tokens, reserved_output_tokens,
                    reserved_cost_usd, actual_input_tokens,
                    actual_output_tokens, actual_cost_usd,
                    usage_status, pricing_status,
                    created_at, updated_at, settled_at
                ) values (
                    ?, ?, ?, ?, 'UNPRICED', 16000, 2048,
                    1.000000000000, 10, 20, 0,
                    'AVAILABLE', 'UNPRICED', ?, ?, ?
                )
                """,
                turnId,
                ORGANIZATION_ID,
                USER_ID,
                Date.valueOf(LocalDate.of(2026, 6, 1)),
                Timestamp.from(NOW.minusSeconds(2)),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID processingTurnWithUserMessage() {
        UUID clientRequestId = UUID.randomUUID();
        UUID userMessageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientRequestId,
                "Question",
                NOW.minusSeconds(10)
        );
        return insertProcessingTurn(
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                clientRequestId,
                userMessageId,
                UUID.randomUUID(),
                NOW.plusSeconds(60),
                null
        );
    }
}
