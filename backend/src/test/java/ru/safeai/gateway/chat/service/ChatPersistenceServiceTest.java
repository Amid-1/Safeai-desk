package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatMessageRole;
import ru.safeai.gateway.chat.entity.ChatMessageStatus;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatPersistenceServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID CHAT_ID =
            UUID.fromString("2251f787-044c-4ef8-80d7-60d3ce4d72af");

    private static final Instant CREATED_AT =
            Instant.parse("2026-06-12T12:00:00Z");

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private AuditEventService auditEventService;

    private ChatPersistenceService chatPersistenceService;

    @BeforeEach
    void setUp() {
        ChatMapper chatMapper = new ChatMapper();

        chatPersistenceService = new ChatPersistenceService(
                chatSessionRepository,
                chatMessageRepository,
                auditEventService,
                chatMapper,
                new ChatProperties(50, 30)
        );
    }

    @Test
    void saveUserMessageAndPrepareAiRequest_shouldSaveUserMessageAndReturnAiRequest() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = chatSessionEntity();

        ChatMessageEntity oldMessage = messageEntity(
                ChatMessageRole.USER,
                "Старое сообщение",
                null,
                null,
                null,
                null
        );

        when(chatSessionRepository.findByIdAndUser_Id(CHAT_ID, USER_ID))
                .thenReturn(Optional.of(session));

        when(chatMessageRepository.findBySession_IdOrderByCreatedAtDescIdDesc(
                eq(CHAT_ID),
                any(Pageable.class)
        )).thenReturn(List.of(oldMessage));

        when(chatMessageRepository.save(any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> persistMessage(invocation.getArgument(0)));

        ChatProcessingContext context =
                chatPersistenceService.saveUserMessageAndPrepareAiRequest(
                        CHAT_ID,
                        new SendMessageRequest("Новое сообщение"),
                        currentUser
                );

        assertThat(context.chatId()).isEqualTo(CHAT_ID);
        assertThat(context.aiRequest().userId()).isEqualTo(USER_ID);
        assertThat(context.aiRequest().organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(context.aiRequest().chatId()).isEqualTo(CHAT_ID);
        assertThat(context.aiRequest().userMessage()).isEqualTo("Новое сообщение");
        assertThat(context.aiRequest().history()).hasSize(1);
        assertThat(context.aiRequest().history().getFirst().content()).isEqualTo("Старое сообщение");

        ArgumentCaptor<ChatMessageEntity> messageCaptor =
                ArgumentCaptor.forClass(ChatMessageEntity.class);

        verify(chatMessageRepository).save(messageCaptor.capture());

        ChatMessageEntity savedMessage = messageCaptor.getValue();

        assertThat(savedMessage.getRole()).isEqualTo(ChatMessageRole.USER);
        assertThat(savedMessage.getContent()).isEqualTo("Новое сообщение");
        assertThat(savedMessage.getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(savedMessage.getCreatedAt()).isNotNull();

        verify(auditEventService).record(
                eq(USER_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.CHAT_MESSAGE_SENT),
                anyMap()
        );
    }

    @Test
    void saveAssistantMessageAndReturnChat_shouldSaveAssistantMessageAndReturnChatDetails() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = chatSessionEntity();

        AiChatResponse aiResponse = new AiChatResponse(
                "Mock AI provider response: Привет",
                "mock-safeai",
                1,
                8,
                BigDecimal.ZERO
        );

        ChatMessageEntity userMessage = messageEntity(
                ChatMessageRole.USER,
                "Привет",
                null,
                null,
                null,
                null
        );

        ChatMessageEntity assistantMessage = messageEntity(
                ChatMessageRole.ASSISTANT,
                "Mock AI provider response: Привет",
                "mock-safeai",
                1,
                8,
                BigDecimal.ZERO
        );

        when(chatSessionRepository.findByIdAndUser_Id(CHAT_ID, USER_ID))
                .thenReturn(Optional.of(session));

        when(chatMessageRepository.findById(userMessage.getId()))
                .thenReturn(Optional.of(userMessage));

        when(chatMessageRepository.save(any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> persistMessage(invocation.getArgument(0)));

        when(chatMessageRepository.findBySession_IdOrderByCreatedAtAscIdAsc(CHAT_ID))
                .thenReturn(List.of(userMessage, assistantMessage));

        ChatDetailsResponse response =
                chatPersistenceService.saveAssistantMessageAndReturnChat(
                        CHAT_ID,
                        userMessage.getId(),
                        aiResponse,
                        currentUser
                );

        assertThat(response.id()).isEqualTo(CHAT_ID);
        assertThat(response.messages()).hasSize(2);

        ArgumentCaptor<ChatMessageEntity> messageCaptor =
                ArgumentCaptor.forClass(ChatMessageEntity.class);

        verify(chatMessageRepository).save(messageCaptor.capture());

        ChatMessageEntity savedMessage = messageCaptor.getValue();

        assertThat(savedMessage.getRole()).isEqualTo(ChatMessageRole.ASSISTANT);
        assertThat(savedMessage.getContent()).isEqualTo("Mock AI provider response: Привет");
        assertThat(savedMessage.getModel()).isEqualTo("mock-safeai");
        assertThat(savedMessage.getInputTokens()).isEqualTo(1);
        assertThat(savedMessage.getOutputTokens()).isEqualTo(8);
        assertThat(savedMessage.getCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(savedMessage.getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(savedMessage.getCreatedAt()).isNotNull();

        verify(auditEventService).record(
                eq(USER_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.AI_RESPONSE_RECEIVED),
                anyMap()
        );
    }

    @Test
    void saveUserMessageAndPrepareAiRequest_shouldThrowResourceNotFoundWhenChatNotOwnedByUser() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatSessionRepository.findByIdAndUser_Id(CHAT_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                chatPersistenceService.saveUserMessageAndPrepareAiRequest(
                        CHAT_ID,
                        new SendMessageRequest("Тест"),
                        currentUser
                )
        )
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Чат не найден");

        verify(chatMessageRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
    }

    @Test
    void assertOwnedChatExists_shouldDoNothingWhenChatOwnedByUser() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatSessionRepository.existsByIdAndUser_Id(CHAT_ID, USER_ID))
                .thenReturn(true);

        chatPersistenceService.assertOwnedChatExists(CHAT_ID, currentUser);

        verify(chatSessionRepository).existsByIdAndUser_Id(CHAT_ID, USER_ID);
        verifyNoMoreInteractions(chatSessionRepository);
        verifyNoInteractions(chatMessageRepository, auditEventService);
    }

    @Test
    void assertOwnedChatExists_shouldThrowResourceNotFoundWhenChatNotOwnedByUser() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatSessionRepository.existsByIdAndUser_Id(CHAT_ID, USER_ID))
                .thenReturn(false);

        assertThatThrownBy(() ->
                chatPersistenceService.assertOwnedChatExists(CHAT_ID, currentUser)
        )
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Чат не найден");

        verify(chatSessionRepository).existsByIdAndUser_Id(CHAT_ID, USER_ID);
        verifyNoMoreInteractions(chatSessionRepository);
        verifyNoInteractions(chatMessageRepository, auditEventService);
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

    private ChatSessionEntity chatSessionEntity() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(CHAT_ID);
        session.setUser(userEntity());
        session.setTitle("Первый тестовый чат");
        session.setCreatedAt(CREATED_AT);
        session.setUpdatedAt(CREATED_AT);

        return session;
    }

    private ChatMessageEntity messageEntity(
            ChatMessageRole role,
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
        message.setCreatedAt(CREATED_AT);
        message.setStatus(ChatMessageStatus.COMPLETED);

        return message;
    }

    private ChatMessageEntity persistMessage(ChatMessageEntity message) {
        if (message.getId() == null) {
            message.setId(UUID.randomUUID());
        }

        if (message.getCreatedAt() == null) {
            message.setCreatedAt(CREATED_AT);
        }

        if (message.getStatus() == null) {
            message.setStatus(ChatMessageStatus.COMPLETED);
        }

        return message;
    }
}