package ru.safeai.gateway.chat.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.entity.ChatTurnEntity;
import ru.safeai.gateway.chat.entity.ChatTurnState;
import ru.safeai.gateway.chat.observability.ChatMetrics;
import ru.safeai.gateway.chat.repository.ChatTurnRepository;
import ru.safeai.gateway.chat.repository.RecoveredChatTurn;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Unifies stale-turn recovery side effects for inline and background paths.
 *
 * <p>The caller must already own a transaction. Turn transition, quota
 * transition and durable audit intent therefore commit or roll back as one
 * unit. ChatMetrics emits transactional metrics only after commit.</p>
 */
@Service
public class ChatTurnRecoveryCoordinator {

    private static final String INLINE_TRIGGER =
            "INLINE";

    private static final String SCHEDULER_TRIGGER =
            "SCHEDULER";

    private static final String STALE_RECOVERY_REASON =
            "STALE_PROCESSING_LEASE";

    private static final String STALE_PROCESSING_FAILURE_CODE =
            "STALE_PROCESSING_LEASE";

    private static final String STALE_BEFORE_PROVIDER_FAILURE_CODE =
            "STALE_BEFORE_PROVIDER_CALL";

    private final ChatTurnRepository turnRepository;
    private final ChatQuotaService quotaService;
    private final AuditEventService auditEventService;
    private final ChatMetrics metrics;

    public ChatTurnRecoveryCoordinator(
            ChatTurnRepository turnRepository,
            ChatQuotaService quotaService,
            AuditEventService auditEventService,
            ChatMetrics metrics
    ) {
        this.turnRepository = Objects.requireNonNull(
                turnRepository,
                "turnRepository не должен быть null"
        );

        this.quotaService = Objects.requireNonNull(
                quotaService,
                "quotaService не должен быть null"
        );

        this.auditEventService = Objects.requireNonNull(
                auditEventService,
                "auditEventService не должен быть null"
        );

        this.metrics = Objects.requireNonNull(
                metrics,
                "metrics не должен быть null"
        );
    }

    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public void recoverInline(
            ChatTurnEntity turn,
            Instant now
    ) {
        Objects.requireNonNull(
                turn,
                "turn не должен быть null"
        );

        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        validateInlineRecoveryCandidate(
                turn,
                now
        );

        RecoveredChatTurn recovered =
                recoverInlineTurn(
                        turn,
                        now
                );

        completeRecovery(
                List.of(recovered),
                now,
                INLINE_TRIGGER
        );
    }

    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public void completeScheduledBatch(
            List<RecoveredChatTurn> recovered,
            Instant now
    ) {
        Objects.requireNonNull(
                recovered,
                "recovered не должен быть null"
        );

        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        completeRecovery(
                List.copyOf(recovered),
                now,
                SCHEDULER_TRIGGER
        );
    }

    private void validateInlineRecoveryCandidate(
            ChatTurnEntity turn,
            Instant now
    ) {
        if (turn.getState()
                != ChatTurnState.PROCESSING) {

            throw new IllegalStateException(
                    "Inline recovery требует PROCESSING turn"
            );
        }

        Instant leaseUntil =
                Objects.requireNonNull(
                        turn.getLeaseUntil(),
                        "PROCESSING turn должен иметь leaseUntil"
                );

        if (leaseUntil.isAfter(now)) {
            throw new IllegalStateException(
                    "Нельзя recover активный PROCESSING turn"
            );
        }
    }

    private RecoveredChatTurn recoverInlineTurn(
            ChatTurnEntity turn,
            Instant now
    ) {
        boolean ambiguous =
                turn.getProviderCallStartedAt() != null;

        int updated =
                ambiguous
                        ? turnRepository
                                .markExpiredProcessingAmbiguous(
                                        turn.getId(),
                                        now
                                )
                        : turnRepository
                                .markExpiredBeforeProviderFailed(
                                        turn.getId(),
                                        now
                                );

        requireExactlyOneRecoveryUpdate(
                updated,
                turn.getId()
        );

        ChatTurnState terminalState =
                ambiguous
                        ? ChatTurnState.AMBIGUOUS
                        : ChatTurnState.FAILED;

        String failureCode =
                firstNonBlank(
                        turn.getFailureCode(),
                        ambiguous
                                ? STALE_PROCESSING_FAILURE_CODE
                                : STALE_BEFORE_PROVIDER_FAILURE_CODE
                );

        return new RecoveredChatTurn(
                turn.getId(),
                turn.getOrganization()
                        .getId(),
                turn.getSession()
                        .getId(),
                turn.getClientRequestId(),
                terminalState,
                failureCode,
                ambiguous
        );
    }

    private void completeRecovery(
            List<RecoveredChatTurn> recovered,
            Instant now,
            String trigger
    ) {
        Objects.requireNonNull(
                recovered,
                "recovered не должен быть null"
        );

        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        Objects.requireNonNull(
                trigger,
                "trigger не должен быть null"
        );

        if (recovered.isEmpty()) {
            return;
        }

        for (RecoveredChatTurn turn : recovered) {
            completeRecoveredTurn(
                    Objects.requireNonNull(
                            turn,
                            "recovered turn не должен быть null"
                    ),
                    now
            );
        }

        recordRecoveryAudit(
                recovered,
                trigger
        );

        metrics.recordRecoveryBatchAfterCommit(
                recovered
        );
    }

    private void completeRecoveredTurn(
            RecoveredChatTurn turn,
            Instant now
    ) {
        ChatTurnState state =
                Objects.requireNonNull(
                        turn.state(),
                        "recovered turn state не должен быть null"
                );

        switch (state) {
            case AMBIGUOUS ->
                    quotaService.markAmbiguous(
                            turn.turnId(),
                            now
                    );

            case FAILED ->
                    quotaService.releaseFailure(
                            turn.turnId(),
                            now
                    );

            default ->
                    throw new IllegalStateException(
                            "Recovery вернул недопустимое "
                                    + "terminal state: "
                                    + state
                    );
        }

        metrics.recordTerminalAfterCommit(
                state
        );
    }

    private void recordRecoveryAudit(
            List<RecoveredChatTurn> recovered,
            String trigger
    ) {
        Map<UUID, List<RecoveredChatTurn>>
                byOrganization =
                recovered.stream()
                        .collect(
                                Collectors.groupingBy(
                                        RecoveredChatTurn::
                                                organizationId,
                                        LinkedHashMap::new,
                                        Collectors.toList()
                                )
                        );

        byOrganization.forEach(
                (organizationId, turns) ->
                        auditEventService.recordSystem(
                                organizationId,
                                AuditEventType.AI_RESPONSE_FAILED,
                                recoveryAuditDetails(
                                        turns,
                                        trigger
                                )
                        )
        );
    }

    private Map<String, Object> recoveryAuditDetails(
            List<RecoveredChatTurn> turns,
            String trigger
    ) {
        long ambiguousCount =
                turns.stream()
                        .filter(
                                RecoveredChatTurn::
                                        outcomeAmbiguous
                        )
                        .count();

        long safeFailureCount =
                turns.size()
                        - ambiguousCount;

        return Map.of(
                "recoveryReason",
                STALE_RECOVERY_REASON,

                "recoveryTrigger",
                trigger,

                "ambiguousCount",
                ambiguousCount,

                "safeFailureCount",
                safeFailureCount,

                "turnCount",
                turns.size(),

                "turnIds",
                turns.stream()
                        .map(
                                RecoveredChatTurn::
                                        turnId
                        )
                        .limit(100)
                        .toList()
        );
    }

    private static void requireExactlyOneRecoveryUpdate(
            int updated,
            UUID turnId
    ) {
        Objects.requireNonNull(
                turnId,
                "turnId не должен быть null"
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Recovery ожидал изменение одного chat turn: "
                            + "turnId="
                            + turnId
                            + ", updated="
                            + updated
            );
        }
    }

    private static String firstNonBlank(
            String value,
            String fallback
    ) {
        Objects.requireNonNull(
                fallback,
                "fallback не должен быть null"
        );

        return value == null || value.isBlank()
                ? fallback
                : value;
    }
}