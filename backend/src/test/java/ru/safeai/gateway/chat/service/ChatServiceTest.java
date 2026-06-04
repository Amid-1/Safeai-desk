package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.ai.AiChatRequest;
import ru.safeai.gateway.ai.AiChatResponse;
import ru.safeai.gateway.ai.AiProvider;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.CreateChatRequest;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    private ChatService chatService;

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

        ArgumentCaptor<ChatSessionEntity> sessionCaptor =
                ArgumentCaptor.forClass(ChatSessionEntity.class);

        verify(chatSessionRepository).save(sessionCaptor.capture());

        ChatSessionEntity savedSession = sessionCaptor.getValue();

        assertThat(savedSession.getId()).isNotNull();
        assertThat(savedSession.getUser()).isEqualTo(user);
        assertThat(savedSession.getTitle()).isEqualTo("Новый тестовый чат");
        assertThat(savedSession.getCreatedAt()).isNotNull();
    }

    @Test
    void sendMessage_shouldSaveUserMessageCallAiProviderAndSaveAssistantMessage() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = chatSessionEntity();

        ChatMessageEntity userHistoryMessage = messageEntity(
                "USER",
                "Проверь, что ответ теперь приходит через AiProvider",
                null,
                null,
                null,
                null
        );

        ChatMessageEntity assistantHistoryMessage = messageEntity(
                "ASSISTANT",
                "Mock AI provider response: Проверь, что ответ теперь приходит через AiProvider",
                "mock-safeai",
                12,
                19,
                BigDecimal.ZERO
        );

        AiChatResponse aiResponse = new AiChatResponse(
                "Mock AI provider response: Проверь, что ответ теперь приходит через AiProvider",
                "mock-safeai",
                12,
                19,
                BigDecimal.ZERO
        );

        when(chatSessionRepository.findByIdAndUser_Id(CHAT_ID, USER_ID))
                .thenReturn(Optional.of(session));

        when(chatMessageRepository.save(any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(chatMessageRepository.findBySession_IdOrderByCreatedAtAsc(CHAT_ID))
                .thenReturn(List.of(userHistoryMessage))
                .thenReturn(List.of(userHistoryMessage, assistantHistoryMessage));

        when(aiProvider.sendMessage(any(AiChatRequest.class)))
                .thenReturn(aiResponse);

        ChatDetailsResponse response = chatService.sendMessage(
                CHAT_ID,
                new SendMessageRequest("Проверь, что ответ теперь приходит через AiProvider"),
                currentUser
        );

        ArgumentCaptor<AiChatRequest> aiRequestCaptor =
                ArgumentCaptor.forClass(AiChatRequest.class);

        verify(aiProvider).sendMessage(aiRequestCaptor.capture());

        AiChatRequest aiRequest = aiRequestCaptor.getValue();

        assertThat(aiRequest.userId()).isEqualTo(USER_ID);
        assertThat(aiRequest.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(aiRequest.chatId()).isEqualTo(CHAT_ID);
        assertThat(aiRequest.userMessage())
                .isEqualTo("Проверь, что ответ теперь приходит через AiProvider");
        assertThat(aiRequest.history()).hasSize(1);

        ArgumentCaptor<ChatMessageEntity> messageCaptor =
                ArgumentCaptor.forClass(ChatMessageEntity.class);

        verify(chatMessageRepository, times(2)).save(messageCaptor.capture());

        List<ChatMessageEntity> savedMessages = messageCaptor.getAllValues();

        ChatMessageEntity savedUserMessage = savedMessages.get(0);
        ChatMessageEntity savedAssistantMessage = savedMessages.get(1);

        assertThat(savedUserMessage.getRole()).isEqualTo("USER");
        assertThat(savedUserMessage.getContent())
                .isEqualTo("Проверь, что ответ теперь приходит через AiProvider");

        assertThat(savedAssistantMessage.getRole()).isEqualTo("ASSISTANT");
        assertThat(savedAssistantMessage.getContent())
                .isEqualTo("Mock AI provider response: Проверь, что ответ теперь приходит через AiProvider");
        assertThat(savedAssistantMessage.getModel()).isEqualTo("mock-safeai");
        assertThat(savedAssistantMessage.getInputTokens()).isEqualTo(12);
        assertThat(savedAssistantMessage.getOutputTokens()).isEqualTo(19);
        assertThat(savedAssistantMessage.getCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(response.id()).isEqualTo(CHAT_ID);
        assertThat(response.messages()).hasSize(2);
    }

    @Test
    void sendMessage_shouldThrowResourceNotFoundWhenChatDoesNotBelongToCurrentUser() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatSessionRepository.findByIdAndUser_Id(CHAT_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.sendMessage(
                CHAT_ID,
                new SendMessageRequest("Тестовое сообщение"),
                currentUser
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Чат не найден");

        verifyNoInteractions(aiProvider);
        verify(chatMessageRepository, never()).save(any());
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

    private ChatSessionEntity chatSessionEntity() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(CHAT_ID);
        session.setUser(userEntity());
        session.setTitle("Первый тестовый чат");
        session.setCreatedAt(LocalDateTime.now());

        return session;
    }

    private ChatMessageEntity messageEntity(
            String role,
            String content,
            String model,
            Integer inputTokens,
            Integer outputTokens,
            BigDecimal costUsd
    ) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setSession(chatSessionEntity());
        message.setRole(role);
        message.setContent(content);
        message.setModel(model);
        message.setInputTokens(inputTokens);
        message.setOutputTokens(outputTokens);
        message.setCostUsd(costUsd);
        message.setCreatedAt(LocalDateTime.now());

        return message;
    }
}