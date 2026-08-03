package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.chat.repository.ChatTurnRepository;

import java.sql.Timestamp;
import java.time.Instant;
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
class ChatTurnRepositoryTransactionIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    @Autowired
    private ChatTurnRepository turnRepository;

    @Test
    void renewLeaseStartsItsOwnTransactionForWatchdogThreadCalls() {
        UUID clientRequestId =
                UUID.randomUUID();

        UUID processingToken =
                UUID.randomUUID();

        UUID userMessageId =
                insertUserMessage(
                        CHAT_ID,
                        ORGANIZATION_ID,
                        clientRequestId,
                        "Question",
                        NOW.minusSeconds(10)
                );

        UUID turnId =
                insertProcessingTurn(
                        CHAT_ID,
                        ORGANIZATION_ID,
                        USER_ID,
                        clientRequestId,
                        userMessageId,
                        processingToken,
                        NOW.plusSeconds(60),
                        null
                );

        Instant observedAt =
                NOW;

        Instant renewedUntil =
                NOW.plusSeconds(180);

        int updated =
                turnRepository.renewLease(
                        turnId,
                        processingToken,
                        observedAt,
                        renewedUntil
                );

        assertThat(updated)
                .isEqualTo(1);

        Timestamp storedLease =
                jdbcTemplate.queryForObject(
                        """
                        select lease_until
                        from public.chat_turns
                        where id = ?
                        """,
                        Timestamp.class,
                        turnId
                );

        Timestamp storedUpdatedAt =
                jdbcTemplate.queryForObject(
                        """
                        select updated_at
                        from public.chat_turns
                        where id = ?
                        """,
                        Timestamp.class,
                        turnId
                );

        assertThat(storedLease)
                .isNotNull();

        assertThat(storedLease.toInstant())
                .isEqualTo(renewedUntil);

        assertThat(storedUpdatedAt)
                .isNotNull();

        assertThat(storedUpdatedAt.toInstant())
                .isEqualTo(observedAt);
    }

    @Test
    void renewLeaseRejectsStaleFencingToken() {
        UUID clientRequestId =
                UUID.randomUUID();

        UUID processingToken =
                UUID.randomUUID();

        UUID userMessageId =
                insertUserMessage(
                        CHAT_ID,
                        ORGANIZATION_ID,
                        clientRequestId,
                        "Question",
                        NOW.minusSeconds(10)
                );

        UUID turnId =
                insertProcessingTurn(
                        CHAT_ID,
                        ORGANIZATION_ID,
                        USER_ID,
                        clientRequestId,
                        userMessageId,
                        processingToken,
                        NOW.plusSeconds(60),
                        null
                );

        int updated =
                turnRepository.renewLease(
                        turnId,
                        UUID.randomUUID(),
                        NOW,
                        NOW.plusSeconds(180)
                );

        assertThat(updated)
                .isZero();
    }
}