package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.chat.repository.ChatHistoryRepository;
import ru.safeai.gateway.chat.service.AiHistoryBuilder;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.chat.quota.enabled=false",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@Import(ChatIntegrationClockConfiguration.class)
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
class ChatHistoryIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    @Autowired
    private ChatHistoryRepository historyRepository;

    @Autowired
    private AiHistoryBuilder historyBuilder;

    @Test
    void failedAndOrphanUserMessagesNeverEnterAiHistory() {
        insertSucceededTurn(
                "Successful question",
                "Successful answer",
                1
        );

        insertFailedTurn();

        insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                UUID.randomUUID(),
                "Orphan question",
                NOW.plusSeconds(3)
        );

        var rows = historyRepository.findNewestSucceededTurns(
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                50
        );

        var history = historyBuilder.build(rows);

        assertThat(rows).hasSize(1);

        assertThat(history)
                .extracting(AiMessage::content)
                .containsExactly(
                        "Successful question",
                        "Successful answer"
                );
    }

    @Test
    void historyContainsOnlyCompleteChronologicalTurnPairs() {
        insertSucceededTurn(
                "Question 1",
                "Answer 1",
                1
        );

        insertSucceededTurn(
                "Question 2",
                "Answer 2",
                2
        );

        insertSucceededTurn(
                "Question 3",
                "Answer 3",
                3
        );

        var history = historyBuilder.build(
                historyRepository.findNewestSucceededTurns(
                        CHAT_ID,
                        ORGANIZATION_ID,
                        USER_ID,
                        50
                )
        );

        assertThat(history)
                .extracting(AiMessage::content)
                .containsExactly(
                        "Question 1",
                        "Answer 1",
                        "Question 2",
                        "Answer 2",
                        "Question 3",
                        "Answer 3"
                );
    }

    @Test
    void historyLimitIsAppliedByCompleteTurnsNotIndividualMessages() {
        insertSucceededTurn(
                "Question 1",
                "Answer 1",
                1
        );

        insertSucceededTurn(
                "Question 2",
                "Answer 2",
                2
        );

        insertSucceededTurn(
                "Question 3",
                "Answer 3",
                3
        );

        var history = historyBuilder.build(
                historyRepository.findNewestSucceededTurns(
                        CHAT_ID,
                        ORGANIZATION_ID,
                        USER_ID,
                        2
                )
        );

        assertThat(history)
                .extracting(AiMessage::content)
                .containsExactly(
                        "Question 2",
                        "Answer 2",
                        "Question 3",
                        "Answer 3"
                );
    }

    @Test
    void refusedAndIncompleteSavedResponsesRemainValidConversationTurns() {
        insertSucceededTurn(
                "Unsafe question",
                "I cannot help with that",
                1,
                "REFUSED"
        );

        insertSucceededTurn(
                "Long question",
                "Partial provider answer",
                2,
                "INCOMPLETE"
        );

        var history = historyBuilder.build(
                historyRepository.findNewestSucceededTurns(
                        CHAT_ID,
                        ORGANIZATION_ID,
                        USER_ID,
                        50
                )
        );

        assertThat(history)
                .extracting(AiMessage::content)
                .containsExactly(
                        "Unsafe question",
                        "I cannot help with that",
                        "Long question",
                        "Partial provider answer"
                );
    }

    private void insertSucceededTurn(
            String question,
            String answer,
            int sequence
    ) {
        insertSucceededTurn(
                question,
                answer,
                sequence,
                "COMPLETED"
        );
    }

    private void insertSucceededTurn(
            String question,
            String answer,
            int sequence,
            String aiStatus
    ) {
        UUID clientRequestId = UUID.randomUUID();

        UUID userMessageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientRequestId,
                question,
                NOW.plusSeconds(sequence * 10L)
        );

        UUID assistantMessageId = insertCompletedAssistant(
                CHAT_ID,
                ORGANIZATION_ID,
                userMessageId,
                answer,
                aiStatus,
                NOW.plusSeconds(sequence * 10L + 1)
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
                    assistant_message_id,
                    state,
                    provider_call_started_at,
                    provider,
                    requested_model,
                    resolved_model,
                    provider_request_id,
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
                    ?,
                    'SUCCEEDED',
                    ?,
                    'mock',
                    'mock-safeai',
                    'mock-safeai',
                    ?,
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
                "a".repeat(64),
                UUID.randomUUID(),
                userMessageId,
                assistantMessageId,
                Timestamp.from(
                        NOW.plusSeconds(sequence * 10L)
                ),
                "provider-request-" + sequence,
                Timestamp.from(
                        NOW.plusSeconds(sequence * 10L)
                ),
                Timestamp.from(
                        NOW.plusSeconds(sequence * 10L + 1)
                ),
                Timestamp.from(
                        NOW.plusSeconds(sequence * 10L + 1)
                )
        );
    }

    private void insertFailedTurn() {
        int sequence = 2;

        UUID clientRequestId = UUID.randomUUID();

        UUID userMessageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientRequestId,
                "Failed question",
                NOW.plusSeconds(sequence * 10L)
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
                    'INVALID_REQUEST',
                    'AI_PROVIDER_INVALID_REQUEST',
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
                "b".repeat(64),
                UUID.randomUUID(),
                userMessageId,
                Timestamp.from(
                        NOW.plusSeconds(sequence * 10L)
                ),
                Timestamp.from(
                        NOW.plusSeconds(sequence * 10L + 1)
                ),
                Timestamp.from(
                        NOW.plusSeconds(sequence * 10L + 1)
                )
        );
    }
}
