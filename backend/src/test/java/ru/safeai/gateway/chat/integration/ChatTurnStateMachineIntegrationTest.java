package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatTurnState;
import ru.safeai.gateway.chat.exception.AiOutcomeAmbiguousException;
import ru.safeai.gateway.chat.exception.ChatTurnFailedException;
import ru.safeai.gateway.chat.exception.IdempotencyKeyReusedException;
import ru.safeai.gateway.chat.repository.ChatTurnRepository;
import ru.safeai.gateway.chat.service.ChatProcessingContext;
import ru.safeai.gateway.chat.service.ChatTurnFinalizationService;
import ru.safeai.gateway.chat.service.ChatTurnReservationService;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
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
class ChatTurnStateMachineIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    @Autowired
    private ChatTurnReservationService reservationService;

    @Autowired
    private ChatTurnFinalizationService finalizationService;

    @Autowired
    private ChatTurnRepository turnRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void preparePrimaryChat() {
        alignChatTimestamps();
        assertOwnedChatVisible();
    }

    @Test
    void newTurnCommitsProviderOperationIdAndQuotaBeforeProviderCall() {
        ChatProcessingContext context = reserve(
                "Question",
                UUID.randomUUID()
        );

        var turn = turnRepository
                .findById(context.turnId())
                .orElseThrow();

        assertThat(turn.getProviderOperationId())
                .isEqualTo(context.providerOperationId())
                .isEqualTo(
                        context.aiRequest().providerOperationId()
                );

        assertThat(turn.getState())
                .isEqualTo(ChatTurnState.PROCESSING);

        assertThat(turn.getProviderCallStartedAt())
                .isNull();

        assertThat(count("chat_turns"))
                .isEqualTo(1);

        assertThat(count("chat_messages"))
                .isEqualTo(1);

        assertThat(count("chat_quota_reservations"))
                .isEqualTo(1);
    }

    @Test
    void sameIdAndSameContentReplaysSinglePersistedResult() {
        UUID clientRequestId = UUID.randomUUID();

        ChatProcessingContext context = reserve(
                "Question",
                clientRequestId
        );

        finalizationService.markProviderCallStarted(
                context
        );

        finalizationService.succeed(
                context,
                ChatTestFixtures.freeResponse(),
                principal()
        );

        ChatProcessingContext replay =
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Question",
                                clientRequestId
                        ),
                        principal()
                );

        assertThat(replay.replay())
                .isTrue();

        assertThat(replay.turnId())
                .isEqualTo(context.turnId());

        assertThat(replay.aiRequest())
                .isNull();

        assertThat(count("chat_turns"))
                .isEqualTo(1);

        assertThat(count("chat_messages"))
                .isEqualTo(2);

        assertThat(count("chat_quota_reservations"))
                .isEqualTo(1);
    }

    @Test
    void sameIdWithDifferentNormalizedContentIsConflict() {
        UUID clientRequestId = UUID.randomUUID();

        reserve(
                "First question",
                clientRequestId
        );

        assertThatThrownBy(() ->
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Different question",
                                clientRequestId
                        ),
                        principal()
                )
        )
                .isInstanceOf(
                        IdempotencyKeyReusedException.class
                )
                .extracting("code")
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");

        assertThat(count("chat_turns"))
                .isEqualTo(1);

        assertThat(count("chat_messages"))
                .isEqualTo(1);
    }

    @Test
    void unambiguousFailureIsStableAndNeverReopensProviderOperation() {
        UUID clientRequestId = UUID.randomUUID();

        ChatProcessingContext context = reserve(
                "Question",
                clientRequestId
        );

        finalizationService.markProviderCallStarted(
                context
        );

        finalizationService.failUnambiguous(
                context,
                unambiguousProviderFailure(),
                principal()
        );

        assertThatThrownBy(() ->
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Question",
                                clientRequestId
                        ),
                        principal()
                )
        )
                .isInstanceOf(
                        ChatTurnFailedException.class
                )
                .extracting("code")
                .isEqualTo("AI_PROVIDER_INVALID_REQUEST");

        var turn = turnRepository
                .findById(context.turnId())
                .orElseThrow();

        assertThat(turn.getState())
                .isEqualTo(ChatTurnState.FAILED);

        assertThat(turn.isOutcomeAmbiguous())
                .isFalse();

        assertThat(quotaState(context.turnId()))
                .isEqualTo("RELEASED");
    }

    @Test
    void ambiguousProviderOutcomeIsStableAndCannotAutoRetry() {
        UUID clientRequestId = UUID.randomUUID();

        ChatProcessingContext context = reserve(
                "Question",
                clientRequestId
        );

        finalizationService.markProviderCallStarted(
                context
        );

        finalizationService.markAmbiguous(
                context,
                "openai",
                "requested-model",
                "provider-request-id",
                AiProviderErrorType.TIMEOUT.name(),
                "AI_PROVIDER_OUTCOME_AMBIGUOUS",
                principal()
        );

        assertThatThrownBy(() ->
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Question",
                                clientRequestId
                        ),
                        principal()
                )
        ).isInstanceOf(
                AiOutcomeAmbiguousException.class
        );

        var turn = turnRepository
                .findById(context.turnId())
                .orElseThrow();

        assertThat(turn.getState())
                .isEqualTo(ChatTurnState.AMBIGUOUS);

        assertThat(turn.getProviderRequestId())
                .isEqualTo("provider-request-id");

        assertThat(turn.isOutcomeAmbiguous())
                .isTrue();

        assertThat(quotaState(context.turnId()))
                .isEqualTo("AMBIGUOUS");
    }

    @Test
    void staleProcessingBeforeProviderCallBecomesRecoverableFailed() {
        UUID clientRequestId = UUID.randomUUID();

        ChatProcessingContext context = reserve(
                "Question",
                clientRequestId
        );

        expire(context.turnId());

        assertThatThrownBy(() ->
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Question",
                                clientRequestId
                        ),
                        principal()
                )
        )
                .isInstanceOf(
                        ChatTurnFailedException.class
                )
                .extracting("code")
                .isEqualTo(
                        "STALE_BEFORE_PROVIDER_CALL"
                );

        assertThat(turnState(context.turnId()))
                .isEqualTo("FAILED");

        assertThat(quotaState(context.turnId()))
                .isEqualTo("RELEASED");
    }

    @Test
    void staleProcessingAfterProviderCallBecomesAmbiguousNotBusyForever() {
        UUID clientRequestId = UUID.randomUUID();

        ChatProcessingContext context = reserve(
                "Question",
                clientRequestId
        );

        finalizationService.markProviderCallStarted(
                context
        );

        expire(context.turnId());

        assertThatThrownBy(() ->
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Question",
                                clientRequestId
                        ),
                        principal()
                )
        ).isInstanceOf(
                AiOutcomeAmbiguousException.class
        );

        assertThat(turnState(context.turnId()))
                .isEqualTo("AMBIGUOUS");

        assertThat(quotaState(context.turnId()))
                .isEqualTo("AMBIGUOUS");
    }

    @Test
    void wrongFencingTokenCannotFinalizeTurn() {
        ChatProcessingContext context = reserve(
                "Question",
                UUID.randomUUID()
        );

        finalizationService.markProviderCallStarted(
                context
        );

        int updated =
                markSucceededWithWrongFencingToken(
                        context
                );

        assertThat(updated)
                .isZero();

        assertThat(turnState(context.turnId()))
                .isEqualTo("PROCESSING");

        assertThat(count("chat_messages"))
                .isEqualTo(1);
    }

    @Test
    void refusedResponseIsPersistedAsSuccessfulProviderOutcome() {
        ChatProcessingContext context = reserve(
                "Question",
                UUID.randomUUID()
        );

        finalizationService.markProviderCallStarted(
                context
        );

        var result = finalizationService.succeed(
                context,
                response(
                        AiResponseStatus.REFUSED,
                        "refused"
                ),
                principal()
        );

        assertThat(
                result.assistantMessage()
                        .aiResponseStatus()
        ).isEqualTo("REFUSED");

        assertThat(result.state())
                .isEqualTo("SUCCEEDED");

        assertThat(turnState(context.turnId()))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void incompleteResponseIsPersistedWithUsageAndPricingMetadata() {
        ChatProcessingContext context = reserve(
                "Question",
                UUID.randomUUID()
        );

        finalizationService.markProviderCallStarted(
                context
        );

        var result = finalizationService.succeed(
                context,
                response(
                        AiResponseStatus.INCOMPLETE,
                        "max_output_tokens"
                ),
                principal()
        );

        assertThat(
                result.assistantMessage()
                        .aiResponseStatus()
        ).isEqualTo("INCOMPLETE");

        assertThat(
                result.assistantMessage()
                        .inputTokens()
        ).isEqualTo(10);

        assertThat(
                result.assistantMessage()
                        .outputTokens()
        ).isEqualTo(20);

        assertThat(
                result.assistantMessage()
                        .pricingStatus()
        ).isEqualTo("FREE");
    }

    @Test
    void providerRequestIdIsStoredOnTurnAndAssistantMessage() {
        ChatProcessingContext context = reserve(
                "Question",
                UUID.randomUUID()
        );

        finalizationService.markProviderCallStarted(
                context
        );

        finalizationService.succeed(
                context,
                response(
                        AiResponseStatus.COMPLETED,
                        "completed"
                ),
                principal()
        );

        String turnProviderRequestId =
                jdbcTemplate.queryForObject(
                        """
                        select provider_request_id
                        from chat_turns
                        where id = ?
                        """,
                        String.class,
                        context.turnId()
                );

        String messageProviderRequestId =
                jdbcTemplate.queryForObject(
                        """
                        select provider_request_id
                        from chat_messages
                        where reply_to_message_id = ?
                        """,
                        String.class,
                        context.userMessageId()
                );

        assertThat(turnProviderRequestId)
                .isEqualTo("provider-request-id");

        assertThat(messageProviderRequestId)
                .isEqualTo("provider-request-id");
    }

    private ChatProcessingContext reserve(
            String content,
            UUID clientRequestId
    ) {
        return reservationService.reserveOrReplay(
                CHAT_ID,
                new SendMessageRequest(
                        content,
                        clientRequestId
                ),
                principal()
        );
    }

    private int markSucceededWithWrongFencingToken(
            ChatProcessingContext context
    ) {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(
                        transactionManager
                );

        Integer updated =
                transactionTemplate.execute(status ->
                        turnRepository.markSucceeded(
                                context.turnId(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "requested-model",
                                "resolved-model",
                                "request-id",
                                clock.instant().plusSeconds(1)
                        )
                );

        return Objects.requireNonNull(
                updated,
                "Результат markSucceeded не должен быть null"
        );
    }

    private SafeAiUserPrincipal principal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "chat-state-machine@test.com",
                0L,
                Set.of(
                        new SimpleGrantedAuthority(
                                "ROLE_USER"
                        )
                )
        );
    }

    private void alignChatTimestamps() {
        Instant fixtureTimestamp =
                clock.instant().minusSeconds(1);

        Timestamp timestamp =
                Timestamp.from(fixtureTimestamp);

        int updated = jdbcTemplate.update(
                """
                update public.chat_sessions
                   set created_at = ?,
                       updated_at = ?
                 where id = ?
                """,
                timestamp,
                timestamp,
                CHAT_ID
        );

        assertThat(updated)
                .as(
                        "Должен существовать тестовый чат для "
                                + "синхронизации timestamp: chatId=%s",
                        CHAT_ID
                )
                .isEqualTo(1);
    }

    private void assertOwnedChatVisible() {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                select exists (
                    select 1
                    from public.chat_sessions
                    where id = ?
                      and user_id = ?
                      and organization_id = ?
                )
                """,
                Boolean.class,
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        );

        assertThat(exists)
                .as(
                        "Тестовый чат должен принадлежать principal: "
                                + "chatId=%s, userId=%s, organizationId=%s",
                        CHAT_ID,
                        USER_ID,
                        ORGANIZATION_ID
                )
                .isTrue();
    }

    private long count(
            String table
    ) {
        Long result = jdbcTemplate.queryForObject(
                "select count(*) from " + table,
                Long.class
        );

        return result == null
                ? 0L
                : result;
    }

    private String turnState(
            UUID turnId
    ) {
        return jdbcTemplate.queryForObject(
                """
                select state
                from chat_turns
                where id = ?
                """,
                String.class,
                turnId
        );
    }

    private String quotaState(
            UUID turnId
    ) {
        return jdbcTemplate.queryForObject(
                """
                select state
                from chat_quota_reservations
                where turn_id = ?
                """,
                String.class,
                turnId
        );
    }

    private void expire(
            UUID turnId
    ) {
        Instant now =
                clock.instant();

        Instant createdAt =
                now.minusSeconds(20);

        Instant updatedAt =
                now.minusSeconds(10);

        Instant providerCallStartedAt =
                now.minusSeconds(5);

        Instant leaseUntil =
                now.minusSeconds(1);

        int updated = jdbcTemplate.update(
                """
                update public.chat_turns
                   set created_at = ?,
                       updated_at = ?,
                       lease_until = ?,
                       provider_call_started_at =
                           case
                               when provider_call_started_at is null
                                   then cast(null as timestamptz)
                               else cast(? as timestamptz)
                           end
                 where id = ?
                """,
                Timestamp.from(createdAt),
                Timestamp.from(updatedAt),
                Timestamp.from(leaseUntil),
                Timestamp.from(providerCallStartedAt),
                turnId
        );

        assertThat(updated)
                .as(
                        "Должен быть состарен один chat turn: turnId=%s",
                        turnId
                )
                .isEqualTo(1);
    }

    private static AiProviderException
    unambiguousProviderFailure() {
        return new AiProviderException(
                "openai",
                "requested-model",
                400,
                "provider-request-id",
                AiProviderErrorType.INVALID_REQUEST,
                false,
                false,
                null,
                "provider failure",
                null
        );
    }

    private static AiChatResponse response(
            AiResponseStatus status,
            String finishReason
    ) {
        return new AiChatResponse(
                "Provider response",
                "requested-model",
                "resolved-model",
                "provider-message-id",
                "provider-request-id",
                status,
                finishReason,
                10,
                20,
                UsageStatus.AVAILABLE,
                BigDecimal.ZERO,
                PricingStatus.FREE,
                "USD",
                "mock-2026-01",
                NOW
        );
    }
}