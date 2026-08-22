package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.chat.config.ChatRecoveryProperties;
import ru.safeai.gateway.chat.entity.ChatTurnState;
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

    @Mock
    private ChatTurnRecoveryRepository repository;

    @Mock
    private ChatTurnRecoveryCoordinator recoveryCoordinator;

    @Test
    void recoveryDelegatesRecoveredBatchToCoordinator() {
        UUID failedTurnId =
                UUID.randomUUID();

        UUID ambiguousTurnId =
                UUID.randomUUID();

        RecoveredChatTurn failed =
                new RecoveredChatTurn(
                        failedTurnId,
                        ChatTestFixtures.ORGANIZATION_ID,
                        ChatTestFixtures.CHAT_ID,
                        UUID.randomUUID(),
                        ChatTurnState.FAILED,
                        "STALE_BEFORE_PROVIDER_CALL",
                        false
                );

        RecoveredChatTurn ambiguous =
                new RecoveredChatTurn(
                        ambiguousTurnId,
                        ChatTestFixtures.ORGANIZATION_ID,
                        ChatTestFixtures.CHAT_ID,
                        UUID.randomUUID(),
                        ChatTurnState.AMBIGUOUS,
                        "STALE_PROCESSING_LEASE",
                        true
                );

        List<RecoveredChatTurn> recovered =
                List.of(
                        failed,
                        ambiguous
                );

        when(
                repository.recoverExpired(
                        ChatTestFixtures.NOW,
                        100
                )
        ).thenReturn(
                recovered
        );

        ChatTurnRecoveryService service =
                new ChatTurnRecoveryService(
                        repository,
                        recoveryCoordinator,
                        properties(),
                        ChatTestFixtures.CLOCK
                );

        int result =
                service.recoverExpiredBatch();

        assertThat(result)
                .isEqualTo(2);

        verify(repository)
                .recoverExpired(
                        ChatTestFixtures.NOW,
                        100
                );

        verify(recoveryCoordinator)
                .completeScheduledBatch(
                        recovered,
                        ChatTestFixtures.NOW
                );
    }

    @Test
    void emptyRecoveryBatchIsStillDelegatedToCoordinator() {
        when(
                repository.recoverExpired(
                        ChatTestFixtures.NOW,
                        100
                )
        ).thenReturn(
                List.of()
        );

        ChatTurnRecoveryService service =
                new ChatTurnRecoveryService(
                        repository,
                        recoveryCoordinator,
                        properties(),
                        ChatTestFixtures.CLOCK
                );

        int result =
                service.recoverExpiredBatch();

        assertThat(result)
                .isZero();

        verify(repository)
                .recoverExpired(
                        ChatTestFixtures.NOW,
                        100
                );

        verify(recoveryCoordinator)
                .completeScheduledBatch(
                        List.of(),
                        ChatTestFixtures.NOW
                );
    }

    @Test
    void configuredBatchSizeIsPassedToRepository() {
        ChatRecoveryProperties properties =
                new ChatRecoveryProperties(
                        true,
                        37,
                        20,
                        "0 * * * * *"
                );

        when(
                repository.recoverExpired(
                        ChatTestFixtures.NOW,
                        37
                )
        ).thenReturn(
                List.of()
        );

        ChatTurnRecoveryService service =
                new ChatTurnRecoveryService(
                        repository,
                        recoveryCoordinator,
                        properties,
                        ChatTestFixtures.CLOCK
                );

        assertThat(
                service.recoverExpiredBatch()
        ).isZero();

        verify(repository)
                .recoverExpired(
                        ChatTestFixtures.NOW,
                        37
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