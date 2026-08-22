package ru.safeai.gateway.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.chat.config.ChatRecoveryProperties;
import ru.safeai.gateway.chat.repository.ChatTurnRecoveryRepository;
import ru.safeai.gateway.chat.repository.RecoveredChatTurn;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class ChatTurnRecoveryService {

    private final ChatTurnRecoveryRepository repository;
    private final ChatTurnRecoveryCoordinator
            recoveryCoordinator;
    private final ChatRecoveryProperties properties;
    private final Clock clock;

    public ChatTurnRecoveryService(
            ChatTurnRecoveryRepository repository,
            ChatTurnRecoveryCoordinator recoveryCoordinator,
            ChatRecoveryProperties properties,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository не должен быть null"
        );
        this.recoveryCoordinator = Objects.requireNonNull(
                recoveryCoordinator,
                "recoveryCoordinator не должен быть null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock не должен быть null"
        );
    }

    @Transactional
    public int recoverExpiredBatch() {
        Instant now = clock.instant();

        List<RecoveredChatTurn> recovered =
                repository.recoverExpired(
                        now,
                        properties.batchSize()
                );

        recoveryCoordinator.completeScheduledBatch(
                recovered,
                now
        );

        if (!recovered.isEmpty()) {
            long ambiguousCount =
                    recovered.stream()
                            .filter(
                                    RecoveredChatTurn::
                                            outcomeAmbiguous
                            )
                            .count();

            log.warn(
                    "Recovered stale PROCESSING chat turns: "
                            + "total={}, ambiguous={}",
                    recovered.size(),
                    ambiguousCount
            );
        }

        return recovered.size();
    }
}
