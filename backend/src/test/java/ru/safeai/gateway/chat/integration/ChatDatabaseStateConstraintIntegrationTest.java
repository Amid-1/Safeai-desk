package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void startedExecutionAttemptCannotBeDeleted() {
        ExecutionIds execution = insertStartedExecutionAttempt();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from public.model_execution_attempts where id = ?",
                execution.attemptId()
        )).isInstanceOf(DataAccessException.class);

        Integer remaining = jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.model_execution_attempts
                where id = ?
                """,
                Integer.class,
                execution.attemptId()
        );

        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void terminalTransitionCannotChangeAttemptIdentity() {
        ExecutionIds execution = insertStartedExecutionAttempt();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                update public.model_execution_attempts
                set outcome = 'AMBIGUOUS',
                    outcome_certainty = 'AMBIGUOUS',
                    finished_at = ?,
                    failure_type = 'UNCLASSIFIED_FAILURE',
                    deployment_ref = 'tampered:deployment'
                where id = ?
                """,
                Timestamp.from(NOW),
                execution.attemptId()
        )).isInstanceOf(DataAccessException.class);

        Map<String, Object> persisted = jdbcTemplate.queryForMap(
                """
                select outcome, deployment_ref
                from public.model_execution_attempts
                where id = ?
                """,
                execution.attemptId()
        );

        assertThat(persisted.get("outcome"))
                .isEqualTo("STARTED");
        assertThat(persisted.get("deployment_ref"))
                .isEqualTo("static:mock:mock-safeai");
    }

    @Test
    void successfulAttemptPersistsCompleteCacheAndPricingEvidence() {
        ExecutionIds execution = insertStartedExecutionAttempt();

        int updated = jdbcTemplate.update(
                """
                update public.model_execution_attempts
                set outcome = 'SUCCEEDED',
                    outcome_certainty = 'KNOWN_EXECUTED',
                    resolved_physical_model = 'mock-safeai',
                    provider_request_id = 'provider-request-1',
                    provider_message_id = 'provider-message-1',
                    usage_status = 'AVAILABLE',
                    input_tokens = 100,
                    cached_input_tokens = 80,
                    cache_write_input_tokens = 10,
                    output_tokens = 20,
                    specialized_dimensions_present = true,
                    specialized_dimensions_valid = true,
                    pricing_status = 'FREE',
                    cost_usd = 0,
                    currency = 'USD',
                    price_book_version = 'mock-2026-01',
                    finished_at = ?
                where id = ?
                """,
                Timestamp.from(NOW),
                execution.attemptId()
        );

        assertThat(updated).isEqualTo(1);

        Map<String, Object> evidence = jdbcTemplate.queryForMap(
                """
                select outcome, input_tokens, cached_input_tokens,
                       cache_write_input_tokens, output_tokens,
                       pricing_status, cost_usd, price_book_version
                from public.model_execution_attempts
                where id = ?
                """,
                execution.attemptId()
        );

        assertThat(evidence.get("outcome"))
                .isEqualTo("SUCCEEDED");
        assertThat(evidence.get("input_tokens"))
                .isEqualTo(100);
        assertThat(evidence.get("cached_input_tokens"))
                .isEqualTo(80);
        assertThat(evidence.get("cache_write_input_tokens"))
                .isEqualTo(10);
        assertThat(evidence.get("output_tokens"))
                .isEqualTo(20);
        assertThat(evidence.get("pricing_status"))
                .isEqualTo("FREE");
        assertThat(evidence.get("price_book_version"))
                .isEqualTo("mock-2026-01");
    }

    private ExecutionIds insertStartedExecutionAttempt() {
        UUID turnId = processingTurnWithUserMessage();
        UUID providerOperationId = jdbcTemplate.queryForObject(
                """
                select provider_operation_id
                from public.chat_turns
                where id = ?
                """,
                UUID.class,
                turnId
        );
        UUID planId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                insert into public.model_execution_plans (
                    id, provider_operation_id, chat_turn_id,
                    organization_id, model_route_decision_id,
                    requested_model, created_at
                ) values (?, ?, ?, ?, null, 'mock-safeai', ?)
                """,
                planId,
                providerOperationId,
                turnId,
                ORGANIZATION_ID,
                Timestamp.from(NOW.minusSeconds(2))
        );

        jdbcTemplate.update(
                """
                insert into public.model_execution_attempts (
                    id, execution_plan_id, attempt_number,
                    provider_attempt_id, provider_type,
                    provider_configuration_ref,
                    provider_configuration_version,
                    deployment_ref, deployment_version,
                    requested_physical_model, started_at,
                    outcome, outcome_certainty,
                    retry_safety, fallback_safety, created_at
                ) values (
                    ?, ?, 1, ?, 'mock', 'static:mock', 'static-v1',
                    'static:mock:mock-safeai', 'static-v1',
                    'mock-safeai', ?, 'STARTED', 'AMBIGUOUS',
                    'SAME_TARGET_RETRY_FORBIDDEN',
                    'FALLBACK_FORBIDDEN', ?
                )
                """,
                attemptId,
                planId,
                UUID.randomUUID(),
                Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.minusSeconds(1))
        );

        return new ExecutionIds(planId, attemptId);
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

    private record ExecutionIds(
            UUID planId,
            UUID attemptId
    ) {
    }
}
