package ru.safeai.gateway.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.config.ChatRecoveryProperties;
import ru.safeai.gateway.chat.entity.ChatTurnState;
import ru.safeai.gateway.chat.observability.ChatMetrics;
import ru.safeai.gateway.chat.repository.ChatTurnRecoveryRepository;
import ru.safeai.gateway.chat.repository.RecoveredChatTurn;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatTurnRecoveryService {

    private final ChatTurnRecoveryRepository repository;
    private final ChatQuotaService quotaService;
    private final AuditEventService auditEventService;
    private final ChatRecoveryProperties properties;
    private final ChatMetrics metrics;
    private final Clock clock;

    public ChatTurnRecoveryService(
            ChatTurnRecoveryRepository repository,
            ChatQuotaService quotaService,
            AuditEventService auditEventService,
            ChatRecoveryProperties properties,
            ChatMetrics metrics,
            Clock clock
    ) {
        this.repository = repository;
        this.quotaService = quotaService;
        this.auditEventService = auditEventService;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public int recoverExpiredBatch() {
        Instant now = clock.instant();
        List<RecoveredChatTurn> recovered = repository.recoverExpired(
                now,
                properties.batchSize()
        );
        for (RecoveredChatTurn turn : recovered) {
            if (turn.state() == ChatTurnState.AMBIGUOUS) {
                quotaService.markAmbiguous(turn.turnId(), now);
            } else {
                quotaService.releaseFailure(turn.turnId(), now);
            }
        }

        Map<UUID, List<RecoveredChatTurn>> byOrganization = recovered.stream()
                .collect(Collectors.groupingBy(
                        RecoveredChatTurn::organizationId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        byOrganization.forEach((organizationId, turns) ->
                auditEventService.recordSystem(
                        organizationId,
                        AuditEventType.AI_RESPONSE_FAILED,
                        Map.of(
                                "recoveryReason", "STALE_PROCESSING_LEASE",
                                "ambiguousCount", turns.stream()
                                        .filter(RecoveredChatTurn::outcomeAmbiguous)
                                        .count(),
                                "safeFailureCount", turns.stream()
                                        .filter(turn -> !turn.outcomeAmbiguous())
                                        .count(),
                                "turnCount", turns.size(),
                                "turnIds", turns.stream()
                                        .map(RecoveredChatTurn::turnId)
                                        .limit(100)
                                        .toList()
                        )
                )
        );

        metrics.recordRecoveryBatchAfterCommit(recovered);

        if (!recovered.isEmpty()) {
            log.warn(
                    "Recovered stale PROCESSING chat turns: total={}, ambiguous={}",
                    recovered.size(),
                    recovered.stream()
                            .filter(RecoveredChatTurn::outcomeAmbiguous)
                            .count()
            );
        }
        return recovered.size();
    }
}
