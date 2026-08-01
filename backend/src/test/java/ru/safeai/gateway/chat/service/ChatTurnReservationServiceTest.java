package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ratelimit.RedisRateLimitService;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatTurnReservationServiceTest {

    private static final UUID CHAT_ID =
            UUID.fromString(
                    "2251f787-044c-4ef8-80d7-60d3ce4d72af"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID USER_MESSAGE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID CLIENT_REQUEST_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    @Mock
    private ChatPersistenceService
            chatPersistenceService;

    @Mock
    private RedisRateLimitService
            rateLimitService;

    @Mock
    private SafeAiUserPrincipal currentUser;

    private ChatTurnReservationService service;

    @BeforeEach
    void setUp() {
        service = new ChatTurnReservationService(
                chatPersistenceService,
                rateLimitService
        );
    }

    @Test
    void replayOfSameClientRequestIdDoesNotConsumeSecondSlot() {
        SendMessageRequest request =
                request();

        ChatProcessingContext replay =
                new ChatProcessingContext(
                        CHAT_ID,
                        USER_MESSAGE_ID,
                        CLIENT_REQUEST_ID,
                        null,
                        true
                );

        when(chatPersistenceService
                .saveUserMessageAndPrepareAiRequest(
                        CHAT_ID,
                        request,
                        currentUser
                ))
                .thenReturn(replay);

        ChatProcessingContext result =
                service.reserveOrReplay(
                        CHAT_ID,
                        request,
                        currentUser
                );

        assertThat(result).isSameAs(replay);

        verify(rateLimitService, never())
                .checkAiMessageAllowed(
                        currentUser
                );
    }

    @Test
    void newTurnIsReservedBeforeRateLimitCheck() {
        SendMessageRequest request =
                request();

        ChatProcessingContext created =
                createdContext();

        when(chatPersistenceService
                .saveUserMessageAndPrepareAiRequest(
                        CHAT_ID,
                        request,
                        currentUser
                ))
                .thenReturn(created);

        ChatProcessingContext result =
                service.reserveOrReplay(
                        CHAT_ID,
                        request,
                        currentUser
                );

        assertThat(result).isSameAs(created);

        InOrder order = inOrder(
                chatPersistenceService,
                rateLimitService
        );

        order.verify(chatPersistenceService)
                .saveUserMessageAndPrepareAiRequest(
                        CHAT_ID,
                        request,
                        currentUser
                );

        order.verify(rateLimitService)
                .checkAiMessageAllowed(
                        currentUser
                );
    }

    @Test
    void rateLimitFailureIsPropagatedSoTransactionCanRollbackReservation() {
        SendMessageRequest request =
                request();

        when(chatPersistenceService
                .saveUserMessageAndPrepareAiRequest(
                        CHAT_ID,
                        request,
                        currentUser
                ))
                .thenReturn(createdContext());

        RateLimitExceededException exception =
                new RateLimitExceededException(
                        "Превышен лимит",
                        Duration.ofMinutes(1)
                );

        org.mockito.Mockito.doThrow(exception)
                .when(rateLimitService)
                .checkAiMessageAllowed(
                        currentUser
                );

        assertThatThrownBy(() ->
                service.reserveOrReplay(
                        CHAT_ID,
                        request,
                        currentUser
                )
        ).isSameAs(exception);
    }

    private SendMessageRequest request() {
        return new SendMessageRequest(
                "Привет",
                CLIENT_REQUEST_ID
        );
    }

    private ChatProcessingContext createdContext() {
        return new ChatProcessingContext(
                CHAT_ID,
                USER_MESSAGE_ID,
                CLIENT_REQUEST_ID,
                new AiChatRequest(
                        USER_ID,
                        ORGANIZATION_ID,
                        CHAT_ID,
                        "Привет",
                        List.of()
                ),
                false
        );
    }
}
