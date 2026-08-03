package ru.safeai.gateway.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.chat.config.ChatRecoveryProperties;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "safeai.chat.recovery",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ChatTurnRecoveryScheduler {

    private final ChatTurnRecoveryService recoveryService;
    private final ChatRecoveryProperties properties;

    public ChatTurnRecoveryScheduler(
            ChatTurnRecoveryService recoveryService,
            ChatRecoveryProperties properties
    ) {
        this.recoveryService = recoveryService;
        this.properties = properties;
    }

    @Scheduled(
            cron = "${safeai.chat.recovery.cron:0 * * * * *}",
            zone = "UTC"
    )
    public void recover() {
        try {
            int batch = 0;
            int recovered;
            do {
                recovered = recoveryService.recoverExpiredBatch();
                batch++;
            } while (recovered > 0
                    && batch < properties.maxBatchesPerRun());

            if (recovered > 0) {
                log.warn(
                        "Chat turn recovery stopped at configured batch limit: {}",
                        properties.maxBatchesPerRun()
                );
            }
        } catch (RuntimeException exception) {
            log.error("Chat turn recovery run failed", exception);
        }
    }
}
