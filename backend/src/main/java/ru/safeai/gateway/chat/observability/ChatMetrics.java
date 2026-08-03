package ru.safeai.gateway.chat.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.safeai.gateway.chat.entity.ChatTurnState;
import ru.safeai.gateway.chat.repository.RecoveredChatTurn;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Low-cardinality production metrics. No user/chat/tenant IDs are used as tags. */
@Component
public class ChatMetrics {

    private static final String LIFECYCLE_COUNTER =
            "safeai.chat.turn.lifecycle";
    private static final String TURN_DURATION =
            "safeai.chat.turn.duration";
    private static final String RECOVERY_BATCH_SIZE =
            "safeai.chat.recovery.batch.size";

    private final MeterRegistry registry;

    public ChatMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordReservedAfterCommit() {
        afterCommit(() -> increment("reserved"));
    }

    public void recordReplay(ChatTurnState state) {
        Objects.requireNonNull(state, "state не должен быть null");
        registry.counter(
                "safeai.chat.turn.replay",
                "state",
                state.name().toLowerCase(java.util.Locale.ROOT)
        ).increment();
    }

    public void recordTerminalAfterCommit(ChatTurnState state) {
        afterCommit(() -> increment(
                "terminal_" + state.name().toLowerCase(java.util.Locale.ROOT)
        ));
    }

    public void recordTerminalAfterCommit(
            ChatTurnState state,
            Instant createdAt,
            Instant completedAt
    ) {
        Objects.requireNonNull(createdAt, "createdAt не должен быть null");
        Objects.requireNonNull(completedAt, "completedAt не должен быть null");
        afterCommit(() -> {
            String normalizedState =
                    state.name().toLowerCase(java.util.Locale.ROOT);
            increment("terminal_" + normalizedState);
            Duration duration = Duration.between(createdAt, completedAt);
            if (duration.isNegative()) {
                duration = Duration.ZERO;
            }
            Timer.builder(TURN_DURATION)
                    .description("End-to-end duration of a persisted chat turn")
                    .tag("state", normalizedState)
                    .register(registry)
                    .record(duration);
        });
    }

    public void recordRecoveryBatchAfterCommit(
            List<RecoveredChatTurn> recovered
    ) {
        List<RecoveredChatTurn> snapshot = List.copyOf(recovered);
        afterCommit(() -> {
            DistributionSummary.builder(RECOVERY_BATCH_SIZE)
                    .description("Number of stale chat turns recovered per batch")
                    .register(registry)
                    .record(snapshot.size());
            snapshot.forEach(turn -> registry.counter(
                    "safeai.chat.recovery.turns",
                    "state",
                    turn.state().name().toLowerCase(java.util.Locale.ROOT)
            ).increment());
        });
    }

    public void recordOwnershipLoss(String layer) {
        String safeLayer = switch (layer) {
            case "redis", "database" -> layer;
            default -> "unknown";
        };
        registry.counter(
                "safeai.chat.processing.ownership.loss",
                "layer",
                safeLayer
        ).increment();
    }

    void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    }
            );
            return;
        }
        action.run();
    }

    private void increment(String event) {
        registry.counter(
                LIFECYCLE_COUNTER,
                "event",
                event
        ).increment();
    }
}
