package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.CreateChatRequest;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ratelimit.RedisRateLimitService;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID CHAT_ID =
            UUID.fromString("2251f787-044c-4ef8-80d7-60d3ce4d72af");

    private static final UUID USER_MESSAGE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final Instant CREATED_AT =
            Instant.parse("2026-06-12T12:00:00Z");

    private static final Instant UPDATED_AT =
            Instant.parse("2026-06-12T12:01:00Z");

    private static final ChatLockService.ChatLock CHAT_LOCK =
            new ChatLockService.ChatLock(
                    CHAT_ID,
                    "safeai:local:chat-lock:" + CHAT_ID,
                    "test-lock-token"
            );

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiProvider aiProvider;

    @Mock
    private AuditEventService auditEventService;

    @Mock
    private ChatPersistenceService chatPersistenceService;

    @Mock
    private RedisRateLimitService rateLimitService;

    @Mock
    private ChatLockService chatLockService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
                chatSessionRepository,
                chatMessageRepository,
                userRepository,
                aiProvider,
                auditEventService,
                chatPersistenceService,
                new ChatMapper(),
                rateLimitService,
                chatLockService,
                new ChatProperties(50, 30)
        );
    }

    @Test
    void create_shouldCreateChatForCurrentUser() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(userEntity()));

        when(chatSessionRepository.save(any(ChatSessionEntity.class)))
                .thenAnswer(invocation -> persistSession(invocation.getArgument(0)));

        ChatResponse response = chatService.create(
                new CreateChatRequest(" Новый тестовый чат "),
                currentUser
        );

        assertThat(response.id()).isEqualTo(CHAT_ID);
        assertThat(response.title()).isEqualTo("Новый тестовый чат");
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
        assertThat(response.updatedAt()).isEqualTo(UPDATED_AT);

        verify(userRepository).findById(USER_ID);
        verify(chatSessionRepository).save(any(ChatSessionEntity.class));

        verify(auditEventService).record(
                eq(USER_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.CHAT_CREATED),
                anyMap()
        );

        verifyNoMoreInteractions(userRepository, chatSessionRepository, auditEventService);
    }

    @Test
    void create_shouldUseDefaultTitleWhenTitleIsBlank() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(userEntity()));

        when(chatSessionRepository.save(any(ChatSessionEntity.class)))
                .thenAnswer(invocation -> persistSession(invocation.getArgument(0)));

        ChatResponse response = chatService.create(
                new CreateChatRequest("   "),
                currentUser
        );

        assertThat(response.title()).isEqualTo("Новый чат");

        verify(userRepository).findById(USER_ID);
        verify(chatSessionRepository).save(any(ChatSessionEntity.class));
    }

    @Test
    void create_shouldThrowResourceNotFoundWhenCurrentUserNotFound() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.create(
                new CreateChatRequest("Чат"),
                currentUser
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Пользователь не найден");

        verify(userRepository).findById(USER_ID);
        verifyNoInteractions(chatSessionRepository, auditEventService);
    }

    @Test
    void sendMessage_shouldCheckRateLimitCallAiProviderAndSaveAssistantMessageThroughPersistenceService() {
        SafeAiUserPrincipal currentUser = currentUser();

        AiChatRequest aiRequest = new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                "Привет",
                List.of()
        );

        ChatProcessingContext context = new ChatProcessingContext(
                CHAT_ID,
                USER_MESSAGE_ID,
                aiRequest
        );

        AiChatResponse aiResponse = new AiChatResponse(
                "Mock AI provider response: Привет",
                "mock-safeai",
                1,
                8,
                BigDecimal.ZERO
        );

        ChatDetailsResponse expectedResponse = new ChatDetailsResponse(
                CHAT_ID,
                "Первый тестовый чат",
                CREATED_AT,
                UPDATED_AT,
                List.of()
        );

        when(chatLockService.lock(CHAT_ID))
                .thenReturn(CHAT_LOCK);

        when(chatPersistenceService.saveUserMessageAndPrepareAiRequest(
                eq(CHAT_ID),
                any(SendMessageRequest.class),
                eq(currentUser)
        )).thenReturn(context);

        when(aiProvider.sendMessage(aiRequest))
                .thenReturn(aiResponse);

        when(chatPersistenceService.saveAssistantMessageAndReturnChat(
                CHAT_ID,
                USER_MESSAGE_ID,
                aiResponse,
                currentUser
        )).thenReturn(expectedResponse);

        ChatDetailsResponse response = chatService.sendMessage(
                CHAT_ID,
                new SendMessageRequest(" Привет "),
                currentUser
        );

        assertThat(response).isEqualTo(expectedResponse);

        verify(chatPersistenceService).assertOwnedChatExists(CHAT_ID, currentUser);
        verify(rateLimitService).checkAiMessageAllowed(currentUser);
        verify(chatLockService).lock(CHAT_ID);

        verify(chatPersistenceService).saveUserMessageAndPrepareAiRequest(
                eq(CHAT_ID),
                argThat(request -> request.content().equals("Привет")),
                eq(currentUser)
        );

        verify(aiProvider).sendMessage(aiRequest);

        verify(chatPersistenceService).saveAssistantMessageAndReturnChat(
                CHAT_ID,
                USER_MESSAGE_ID,
                aiResponse,
                currentUser
        );

        verify(chatLockService).unlockQuietly(CHAT_LOCK);
    }

    @Test
    void sendMessage_shouldSaveFailedAssistantMessageAndRethrowWhenAiProviderFails() {
        SafeAiUserPrincipal currentUser = currentUser();

        AiChatRequest aiRequest = new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                "Привет",
                List.of()
        );

        ChatProcessingContext context = new ChatProcessingContext(
                CHAT_ID,
                USER_MESSAGE_ID,
                aiRequest
        );

        RuntimeException exception = new RuntimeException("AI unavailable");

        when(chatLockService.lock(CHAT_ID))
                .thenReturn(CHAT_LOCK);

        when(chatPersistenceService.saveUserMessageAndPrepareAiRequest(
                eq(CHAT_ID),
                any(SendMessageRequest.class),
                eq(currentUser)
        )).thenReturn(context);

        when(aiProvider.sendMessage(aiRequest))
                .thenThrow(exception);

        assertThatThrownBy(() -> chatService.sendMessage(
                CHAT_ID,
                new SendMessageRequest("Привет"),
                currentUser
        ))
                .isSameAs(exception);

        verify(chatPersistenceService).assertOwnedChatExists(CHAT_ID, currentUser);
        verify(rateLimitService).checkAiMessageAllowed(currentUser);
        verify(chatLockService).lock(CHAT_ID);

        verify(chatPersistenceService).saveUserMessageAndPrepareAiRequest(
                eq(CHAT_ID),
                argThat(request -> request.content().equals("Привет")),
                eq(currentUser)
        );

        verify(aiProvider).sendMessage(aiRequest);

        verify(chatPersistenceService).saveFailedAssistantMessage(
                eq(CHAT_ID),
                eq(USER_MESSAGE_ID),
                same(exception),
                eq(currentUser)
        );

        verify(chatPersistenceService, never()).saveAssistantMessageAndReturnChat(
                any(),
                any(),
                any(),
                any()
        );

        verify(chatLockService).unlockQuietly(CHAT_LOCK);
    }

    @Test
    void findById_shouldThrowResourceNotFoundWhenChatDoesNotBelongToCurrentUser() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatSessionRepository.findByIdAndUser_Id(CHAT_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.findById(CHAT_ID, currentUser))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Чат не найден");

        verify(chatSessionRepository).findByIdAndUser_Id(CHAT_ID, USER_ID);
    }

    private SafeAiUserPrincipal currentUser() {
        return new SafeAiUserPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "password-hash",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private UserEntity userEntity() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setEmail("admin@test.com");
        user.setEnabled(true);

        return user;
    }

    private ChatSessionEntity persistSession(ChatSessionEntity session) {
        session.setId(CHAT_ID);

        if (session.getCreatedAt() == null) {
            session.setCreatedAt(CREATED_AT);
        }

        if (session.getUpdatedAt() == null) {
            session.setUpdatedAt(UPDATED_AT);
        }

        return session;
    }
}