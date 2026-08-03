package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.config.ChatRecoveryProperties;
import ru.safeai.gateway.chat.entity.ChatTurnState;
import ru.safeai.gateway.chat.observability.ChatMetrics;
import ru.safeai.gateway.chat.repository.ChatTurnRecoveryRepository;
import ru.safeai.gateway.chat.repository.RecoveredChatTurn;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatTurnRecoveryServiceTest {

    @Mock ChatTurnRecoveryRepository repository;
    @Mock ChatQuotaService quotaService;
    @Mock AuditEventService auditEventService;
    @Mock ChatMetrics metrics;

    @Test
    void recoverySeparatesSafePreCallFailureFromAmbiguousProviderOutcome() {
        UUID failedTurnId = UUID.randomUUID();
        UUID ambiguousTurnId = UUID.randomUUID();
        when(repository.recoverExpired(ChatTestFixtures.NOW, 100))
                .thenReturn(List.of(
                        new RecoveredChatTurn(
                                failedTurnId,
                                ChatTestFixtures.ORGANIZATION_ID,
                                ChatTestFixtures.CHAT_ID,
                                UUID.randomUUID(),
                                ChatTurnState.FAILED,
                                "STALE_BEFORE_PROVIDER_CALL",
                                false
                        ),
                        new RecoveredChatTurn(
                                ambiguousTurnId,
                                ChatTestFixtures.ORGANIZATION_ID,
                                ChatTestFixtures.CHAT_ID,
                                UUID.randomUUID(),
                                ChatTurnState.AMBIGUOUS,
                                "STALE_PROCESSING_LEASE",
                                true
                        )
                ));

        ChatTurnRecoveryService service = new ChatTurnRecoveryService(
                repository,
                quotaService,
                auditEventService,
                properties(),
                metrics,
                ChatTestFixtures.CLOCK
        );

        assertThat(service.recoverExpiredBatch()).isEqualTo(2);
        verify(quotaService).releaseFailure(
                failedTurnId,
                ChatTestFixtures.NOW
        );
        verify(quotaService).markAmbiguous(
                ambiguousTurnId,
                ChatTestFixtures.NOW
        );
        verify(metrics).recordRecoveryBatchAfterCommit(
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    private static ChatRecoveryProperties properties() {
        return new ChatRecoveryProperties(
                true,
                100,
                20,
                "0 * * * * *"
        );
    }
}
