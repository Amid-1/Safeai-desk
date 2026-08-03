package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.chat.config.ChatRecoveryProperties;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTurnRecoverySchedulerTest {

    @Test
    void recoveryStopsAtConfiguredBatchLimit() {
        ChatTurnRecoveryService recoveryService =
                mock(ChatTurnRecoveryService.class);
        when(recoveryService.recoverExpiredBatch()).thenReturn(100);

        ChatTurnRecoveryScheduler scheduler = new ChatTurnRecoveryScheduler(
                recoveryService,
                new ChatRecoveryProperties(true, 100, 2, "0 * * * * *")
        );

        scheduler.recover();

        verify(recoveryService, times(2)).recoverExpiredBatch();
    }

    @Test
    void recoveryStopsWhenQueueIsEmpty() {
        ChatTurnRecoveryService recoveryService =
                mock(ChatTurnRecoveryService.class);
        when(recoveryService.recoverExpiredBatch()).thenReturn(1, 0);

        ChatTurnRecoveryScheduler scheduler = new ChatTurnRecoveryScheduler(
                recoveryService,
                new ChatRecoveryProperties(true, 100, 20, "0 * * * * *")
        );

        scheduler.recover();

        verify(recoveryService, times(2)).recoverExpiredBatch();
    }
}
