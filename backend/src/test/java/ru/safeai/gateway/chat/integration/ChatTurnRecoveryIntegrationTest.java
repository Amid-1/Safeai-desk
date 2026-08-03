package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.chat.service.ChatTurnRecoveryService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.chat.quota.enabled=true",
        "safeai.rate-limit.ai-messages.enabled=false",
        "safeai.chat.recovery.batch-size=1"
})
@ActiveProfiles("test")
@Import(ChatIntegrationClockConfiguration.class)
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
class ChatTurnRecoveryIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    @Autowired
    private ChatTurnRecoveryService recoveryService;

    @Test
    void crashBeforeProviderCallBecomesFailedAndReleasesQuota() {
        UUID client = UUID.randomUUID();
        UUID userMessage = insertUserMessage(
                CHAT_ID, ORGANIZATION_ID, client, "Question", NOW.minusSeconds(20)
        );
        UUID turn = insertProcessingTurn(
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                client,
                userMessage,
                UUID.randomUUID(),
                NOW.minusSeconds(1),
                null
        );
        insertQuotaReservation(turn, ORGANIZATION_ID, USER_ID);

        assertThat(recoveryService.recoverExpiredBatch()).isEqualTo(1);
        assertThat(state(turn)).isEqualTo("FAILED");
        assertThat(failureCode(turn)).isEqualTo("STALE_BEFORE_PROVIDER_CALL");
        assertThat(quotaState(turn)).isEqualTo("RELEASED");
    }

    @Test
    void crashAfterProviderCallStartedBecomesAmbiguousAndKeepsQuota() {
        UUID client = UUID.randomUUID();
        UUID userMessage = insertUserMessage(
                CHAT_ID, ORGANIZATION_ID, client, "Question", NOW.minusSeconds(20)
        );
        UUID turn = insertProcessingTurn(
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                client,
                userMessage,
                UUID.randomUUID(),
                NOW.minusSeconds(1),
                NOW.minusSeconds(10)
        );
        insertQuotaReservation(turn, ORGANIZATION_ID, USER_ID);

        assertThat(recoveryService.recoverExpiredBatch()).isEqualTo(1);
        assertThat(state(turn)).isEqualTo("AMBIGUOUS");
        assertThat(outcomeAmbiguous(turn)).isTrue();
        assertThat(quotaState(turn)).isEqualTo("AMBIGUOUS");
    }

    @Test
    void recoveryIsBoundedAndIdempotent() {
        UUID secondChat = UUID.randomUUID();
        insertChatSession(secondChat, USER_ID, ORGANIZATION_ID);
        UUID first = expiredTurn(CHAT_ID, "First", null);
        UUID second = expiredTurn(
                secondChat,
                "Second",
                NOW.minusSeconds(5)
        );

        assertThat(recoveryService.recoverExpiredBatch()).isEqualTo(1);
        assertThat(recoveryService.recoverExpiredBatch()).isEqualTo(1);
        assertThat(recoveryService.recoverExpiredBatch()).isZero();

        assertThat(state(first)).isIn("FAILED", "AMBIGUOUS");
        assertThat(state(second)).isIn("FAILED", "AMBIGUOUS");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_turns where state = 'PROCESSING'",
                Long.class
        )).isZero();
    }

    @Test
    void activeLeaseIsNotRecovered() {
        UUID client = UUID.randomUUID();
        UUID userMessage = insertUserMessage(
                CHAT_ID, ORGANIZATION_ID, client, "Question", NOW.minusSeconds(20)
        );
        UUID turn = insertProcessingTurn(
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                client,
                userMessage,
                UUID.randomUUID(),
                NOW.plusSeconds(60),
                NOW.minusSeconds(10)
        );
        insertQuotaReservation(turn, ORGANIZATION_ID, USER_ID);

        assertThat(recoveryService.recoverExpiredBatch()).isZero();
        assertThat(state(turn)).isEqualTo("PROCESSING");
        assertThat(quotaState(turn)).isEqualTo("RESERVED");
    }

    private UUID expiredTurn(
            UUID chatId,
            String content,
            java.time.Instant providerStartedAt
    ) {
        UUID client = UUID.randomUUID();
        UUID userMessage = insertUserMessage(
                chatId,
                ORGANIZATION_ID,
                client,
                content,
                NOW.minusSeconds(20)
        );
        UUID turn = insertProcessingTurn(
                chatId,
                ORGANIZATION_ID,
                USER_ID,
                client,
                userMessage,
                UUID.randomUUID(),
                NOW.minusSeconds(1),
                providerStartedAt
        );
        insertQuotaReservation(turn, ORGANIZATION_ID, USER_ID);
        return turn;
    }

    private String state(UUID turnId) {
        return jdbcTemplate.queryForObject(
                "select state from chat_turns where id = ?",
                String.class,
                turnId
        );
    }

    private String failureCode(UUID turnId) {
        return jdbcTemplate.queryForObject(
                "select failure_code from chat_turns where id = ?",
                String.class,
                turnId
        );
    }

    private Boolean outcomeAmbiguous(UUID turnId) {
        return jdbcTemplate.queryForObject(
                "select outcome_ambiguous from chat_turns where id = ?",
                Boolean.class,
                turnId
        );
    }

    private String quotaState(UUID turnId) {
        return jdbcTemplate.queryForObject(
                "select state from chat_quota_reservations where turn_id = ?",
                String.class,
                turnId
        );
    }
}
