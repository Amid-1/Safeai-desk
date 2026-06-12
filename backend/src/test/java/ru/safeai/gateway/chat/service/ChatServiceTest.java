package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.ai.AiChatRequest;
import ru.safeai.gateway.ai.AiChatResponse;
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
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.ai.AiProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID ORGANIZATION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CHAT_ID = UUID.fromString("2251f787-044c-4ef8-80d7-60d3ce4d72af");

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

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
                chatSessionRepository,
                chatMessageRepository,
                userRepository,
                aiProvider,
                auditEventService,
                chatPersistenceService
        );
    }

    @Test
    void create_shouldCreateChatForCurrentUser() {
        SafeAiUserPrincipal currentUser = currentUser();
        UserEntity user = userEntity();

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user));

        when(chatSessionRepository.save(any(ChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ChatResponse response = chatService.create(
                new CreateChatRequest(" Новый тестовый чат "),
                currentUser
        );

        assertThat(response.id()).isNotNull();
        assertThat(response.title()).isEqualTo("Новый тестовый чат");
        assertThat(response.createdAt()).isNotNull();

        verify(chatSessionRepository).save(any(ChatSessionEntity.class));

        verify(auditEventService).record(
                eq(USER_ID),
                eq(AuditEventType.CHAT_CREATED),
                anyMap()
        );
    }

    @Test
    void sendMessage_shouldSaveUserMessageCallAiProviderAndSaveAssistantMessageThroughPersistenceService() {
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
                Instant.parse("2026-06-12T12:00:00Z"),
                List.of()
        );

        when(chatPersistenceService.saveUserMessageAndPrepareAiRequest(
                eq(CHAT_ID),
                any(SendMessageRequest.class),
                eq(currentUser)
        )).thenReturn(context);

        when(aiProvider.sendMessage(aiRequest))
                .thenReturn(aiResponse);

        when(chatPersistenceService.saveAssistantMessageAndReturnChat(
                CHAT_ID,
                aiResponse,
                currentUser
        )).thenReturn(expectedResponse);

        ChatDetailsResponse response = chatService.sendMessage(
                CHAT_ID,
                new SendMessageRequest("Привет"),
                currentUser
        );

        assertThat(response).isEqualTo(expectedResponse);

        verify(chatPersistenceService).saveUserMessageAndPrepareAiRequest(
                eq(CHAT_ID),
                any(SendMessageRequest.class),
                eq(currentUser)
        );

        verify(aiProvider).sendMessage(aiRequest);

        verify(chatPersistenceService).saveAssistantMessageAndReturnChat(
                CHAT_ID,
                aiResponse,
                currentUser
        );
    }

    @Test
    void findById_shouldThrowResourceNotFoundWhenChatDoesNotBelongToCurrentUser() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatSessionRepository.findByIdAndUser_Id(CHAT_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.findById(CHAT_ID, currentUser))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Чат не найден");
    }

    private SafeAiUserPrincipal currentUser() {
        return new SafeAiUserPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "password-hash",
                true,
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
}