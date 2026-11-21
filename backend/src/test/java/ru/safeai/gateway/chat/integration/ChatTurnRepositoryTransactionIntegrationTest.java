package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.chat.repository.ChatTurnRepository;

import java.sql.Timestamp;
import java.time.Duration;
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
    void renewLeaseWithExplicitTimestampCommitsTheUpdate() {
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

    @Test
    void watchdogRenewalCommitsUsingOneDatabaseTimestamp() {
        Instant beforeRenewal = databaseNow();
        UUID processingToken = UUID.randomUUID();
        UUID turnId = insertWatchdogTurn(processingToken, beforeRenewal.plusSeconds(60));
        Duration extension = Duration.ofMinutes(3);

        assertThat(turnRepository.renewLeaseUsingDatabaseClock(turnId, processingToken, extension.toMillis()))
                .isEqualTo(1);

        WatchdogLease lease = readWatchdogLease(turnId);
        assertThat(lease.updatedAt()).isBetween(beforeRenewal, databaseNow());
        assertThat(lease.leaseUntil()).isEqualTo(lease.updatedAt().plus(extension));
        assertThat(lease.version()).isEqualTo(1);
    }

    @Test
    void watchdogRenewalLeavesLeaseUnchangedForStaleToken() {
        UUID processingToken = UUID.randomUUID();
        UUID turnId = insertWatchdogTurn(processingToken, databaseNow().plusSeconds(60));
        WatchdogLease original = readWatchdogLease(turnId);

        assertThat(turnRepository.renewLeaseUsingDatabaseClock(turnId, UUID.randomUUID(), 180_000))
                .isZero();

        assertThat(readWatchdogLease(turnId)).isEqualTo(original);
    }

    @Test
    void watchdogRenewalCannotReviveAnExpiredLease() {
        UUID processingToken = UUID.randomUUID();
        UUID turnId = insertWatchdogTurn(processingToken, databaseNow().minusSeconds(1));
        WatchdogLease original = readWatchdogLease(turnId);

        assertThat(turnRepository.renewLeaseUsingDatabaseClock(turnId, processingToken, 180_000))
                .isZero();

        assertThat(readWatchdogLease(turnId)).isEqualTo(original);
    }

    private Instant databaseNow() {
        Timestamp timestamp = jdbcTemplate.queryForObject("select clock_timestamp()", Timestamp.class);
        assertThat(timestamp).isNotNull();
        return timestamp.toInstant();
    }

    private UUID insertWatchdogTurn(UUID processingToken, Instant leaseUntil) {
        UUID clientRequestId = UUID.randomUUID();
        UUID userMessageId = insertUserMessage(CHAT_ID, ORGANIZATION_ID, clientRequestId, "Question",
                NOW.minusSeconds(10));
        return insertProcessingTurn(CHAT_ID, ORGANIZATION_ID, USER_ID, clientRequestId, userMessageId,
                processingToken, leaseUntil, null);
    }

    private WatchdogLease readWatchdogLease(UUID turnId) {
        return jdbcTemplate.queryForObject("""
                select lease_until, updated_at, version
                from public.chat_turns
                where id = ?
                """, (row, index) -> new WatchdogLease(
                row.getTimestamp("lease_until").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version")
        ), turnId);
    }

    private record WatchdogLease(Instant leaseUntil, Instant updatedAt, long version) {
    }

}