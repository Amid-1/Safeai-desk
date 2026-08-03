package ru.safeai.gateway.chat.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.chat.entity.ChatTurnState;
import ru.safeai.gateway.chat.repository.RecoveredChatTurn;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMetricsTest {

    @Test
    void terminalMetricsUseOnlyBoundedStateTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatMetrics metrics = new ChatMetrics(registry);

        metrics.recordTerminalAfterCommit(
                ChatTurnState.SUCCEEDED,
                Instant.parse("2026-08-02T09:00:00Z"),
                Instant.parse("2026-08-02T09:00:02Z")
        );

        assertThat(registry.get("safeai.chat.turn.lifecycle")
                .tag("event", "terminal_succeeded")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("safeai.chat.turn.duration")
                .tag("state", "succeeded")
                .timer().totalTime(java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(2.0);
    }

    @Test
    void replayAndOwnershipMetricsDoNotContainIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatMetrics metrics = new ChatMetrics(registry);

        metrics.recordReplay(ChatTurnState.AMBIGUOUS);
        metrics.recordOwnershipLoss("redis");

        assertThat(registry.get("safeai.chat.turn.replay")
                .tag("state", "ambiguous")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("safeai.chat.processing.ownership.loss")
                .tag("layer", "redis")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void recoveryBatchRecordsTerminalStates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatMetrics metrics = new ChatMetrics(registry);
        UUID organizationId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        metrics.recordRecoveryBatchAfterCommit(List.of(
                new RecoveredChatTurn(
                        UUID.randomUUID(), organizationId, sessionId,
                        UUID.randomUUID(), ChatTurnState.FAILED,
                        "STALE_BEFORE_PROVIDER_CALL", false
                ),
                new RecoveredChatTurn(
                        UUID.randomUUID(), organizationId, sessionId,
                        UUID.randomUUID(), ChatTurnState.AMBIGUOUS,
                        "STALE_PROCESSING_LEASE", true
                )
        ));

        assertThat(registry.get("safeai.chat.recovery.batch.size")
                .summary().totalAmount()).isEqualTo(2.0);
        assertThat(registry.get("safeai.chat.recovery.turns")
                .tag("state", "failed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("safeai.chat.recovery.turns")
                .tag("state", "ambiguous").counter().count()).isEqualTo(1.0);
    }
}
