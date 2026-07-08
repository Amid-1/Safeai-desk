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
import ru.safeai.gateway.organization.entity.OrganizationEntity;
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
import static org.mockito.Mockito.*;

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
        chatPersistenceService = new ChatPersistenceService(
                chatSessionRepository,
                chatMessageRepository,
                auditEventService,
                new ChatMapper(),
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

        when(chatSessionRepository.findByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.of(session));

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
        assertThat(savedMessage.getOrganization().getId()).isEqualTo(ORGANIZATION_ID);
        assertThat(savedMessage.getCreatedAt()).isNotNull();

        verify(auditEventService).record(
                eq(USER_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.CHAT_MESSAGE_SENT),
                anyMap()
        );
    }

    @Test
    void saveAssistantMessageAndReturnChat_shouldSaveAssistantMessageAndReturnLimitedChatDetails() {
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

        assistantMessage.setCreatedAt(CREATED_AT.plusSeconds(1));

        when(chatSessionRepository.findByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.of(session));

        when(chatMessageRepository.existsByIdAndSession_IdAndSession_User_IdAndSession_Organization_IdAndRole(
                userMessage.getId(),
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID,
                ChatMessageRole.USER
        )).thenReturn(true);

        when(chatMessageRepository.save(any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> persistMessage(invocation.getArgument(0)));

        when(chatMessageRepository.findBySession_IdOrderByCreatedAtDescIdDesc(
                eq(CHAT_ID),
                any(Pageable.class)
        )).thenReturn(List.of(assistantMessage, userMessage));

        ChatDetailsResponse response =
                chatPersistenceService.saveAssistantMessageAndReturnChat(
                        CHAT_ID,
                        userMessage.getId(),
                        aiResponse,
                        currentUser
                );

        assertThat(response.id()).isEqualTo(CHAT_ID);
        assertThat(response.messages()).hasSize(2);
        assertThat(response.messages().get(0).role()).isEqualTo("USER");
        assertThat(response.messages().get(1).role()).isEqualTo("ASSISTANT");

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
        assertThat(savedMessage.getOrganization().getId()).isEqualTo(ORGANIZATION_ID);
        assertThat(savedMessage.getCreatedAt()).isNotNull();

        verify(chatMessageRepository).findBySession_IdOrderByCreatedAtDescIdDesc(
                eq(CHAT_ID),
                any(Pageable.class)
        );

        verify(auditEventService).record(
                eq(USER_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.AI_RESPONSE_RECEIVED),
                anyMap()
        );
    }

    @Test
    void saveUserMessageAndPrepareAiRequest_shouldThrowResourceNotFoundWhenChatNotOwnedByUserAndOrganization() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatSessionRepository.findByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.empty());

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
    void assertOwnedChatExists_shouldDoNothingWhenChatOwnedByUserAndOrganization() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatSessionRepository.existsByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(true);

        chatPersistenceService.assertOwnedChatExists(CHAT_ID, currentUser);

        verify(chatSessionRepository).existsByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        );
        verifyNoMoreInteractions(chatSessionRepository);
        verifyNoInteractions(chatMessageRepository, auditEventService);
    }

    @Test
    void assertOwnedChatExists_shouldThrowResourceNotFoundWhenChatNotOwnedByUserAndOrganization() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatSessionRepository.existsByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(false);

        assertThatThrownBy(() ->
                chatPersistenceService.assertOwnedChatExists(CHAT_ID, currentUser)
        )
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Чат не найден");

        verify(chatSessionRepository).existsByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        );
        verifyNoMoreInteractions(chatSessionRepository);
        verifyNoInteractions(chatMessageRepository, auditEventService);
    }

    @Test
    void saveFailedAssistantMessage_shouldSaveFailedAssistantMessageAndAuditEvent() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = chatSessionEntity();

        ChatMessageEntity userMessage = messageEntity(
                ChatMessageRole.USER,
                "Привет",
                null,
                null,
                null,
                null
        );

        RuntimeException exception = new RuntimeException("AI unavailable");

        when(chatSessionRepository.findByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.of(session));

        when(chatMessageRepository.existsByIdAndSession_IdAndSession_User_IdAndSession_Organization_IdAndRole(
                userMessage.getId(),
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID,
                ChatMessageRole.USER
        )).thenReturn(true);

        when(chatMessageRepository.save(any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> persistMessage(invocation.getArgument(0)));

        chatPersistenceService.saveFailedAssistantMessage(
                CHAT_ID,
                userMessage.getId(),
                exception,
                currentUser
        );

        ArgumentCaptor<ChatMessageEntity> captor =
                ArgumentCaptor.forClass(ChatMessageEntity.class);

        verify(chatMessageRepository).save(captor.capture());

        ChatMessageEntity saved = captor.getValue();

        assertThat(saved.getRole()).isEqualTo(ChatMessageRole.ASSISTANT);
        assertThat(saved.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(saved.getContent()).isEqualTo("Не удалось получить ответ от AI-провайдера");
        assertThat(saved.getOrganization().getId()).isEqualTo(ORGANIZATION_ID);

        verify(auditEventService).record(
                eq(USER_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.AI_RESPONSE_FAILED),
                anyMap()
        );
    }

    @Test
    void saveAssistantMessageAndReturnChat_shouldThrowWhenUserMessageIsNotUserRole() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = chatSessionEntity();

        ChatMessageEntity assistantMessage = messageEntity(
                ChatMessageRole.ASSISTANT,
                "Не user message",
                "mock-safeai",
                1,
                1,
                BigDecimal.ZERO
        );

        AiChatResponse aiResponse = new AiChatResponse(
                "Ответ",
                "mock-safeai",
                1,
                1,
                BigDecimal.ZERO
        );

        when(chatSessionRepository.findByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.of(session));

        when(chatMessageRepository.existsByIdAndSession_IdAndSession_User_IdAndSession_Organization_IdAndRole(
                assistantMessage.getId(),
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID,
                ChatMessageRole.USER
        )).thenReturn(false);

        assertThatThrownBy(() -> chatPersistenceService.saveAssistantMessageAndReturnChat(
                CHAT_ID,
                assistantMessage.getId(),
                aiResponse,
                currentUser
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Сообщение не найдено");

        verify(chatMessageRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
    }

    @Test
    void saveAssistantMessageAndReturnChat_shouldLoadOnlyDetailsMessageLimit() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = chatSessionEntity();

        ChatMessageEntity userMessage = messageEntity(
                ChatMessageRole.USER,
                "Привет",
                null,
                null,
                null,
                null
        );

        AiChatResponse aiResponse = new AiChatResponse(
                "Ответ",
                "mock-safeai",
                1,
                1,
                BigDecimal.ZERO
        );

        when(chatSessionRepository.findByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.of(session));

        when(chatMessageRepository.existsByIdAndSession_IdAndSession_User_IdAndSession_Organization_IdAndRole(
                userMessage.getId(),
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID,
                ChatMessageRole.USER
        )).thenReturn(true);

        when(chatMessageRepository.save(any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> persistMessage(invocation.getArgument(0)));

        when(chatMessageRepository.findBySession_IdOrderByCreatedAtDescIdDesc(
                eq(CHAT_ID),
                any(Pageable.class)
        )).thenReturn(List.of(userMessage));

        chatPersistenceService.saveAssistantMessageAndReturnChat(
                CHAT_ID,
                userMessage.getId(),
                aiResponse,
                currentUser
        );

        verify(chatMessageRepository).findBySession_IdOrderByCreatedAtDescIdDesc(
                eq(CHAT_ID),
                argThat(pageable ->
                        pageable.getPageNumber() == 0
                                && pageable.getPageSize() == 50
                )
        );
    }

    @Test
    void saveUserMessageAndPrepareAiRequest_shouldLoadOnlyHistoryMessageLimit() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = chatSessionEntity();

        when(chatSessionRepository.findByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.of(session));

        when(chatMessageRepository.findBySession_IdOrderByCreatedAtDescIdDesc(
                eq(CHAT_ID),
                any(Pageable.class)
        )).thenReturn(List.of());

        when(chatMessageRepository.save(any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> persistMessage(invocation.getArgument(0)));

        chatPersistenceService.saveUserMessageAndPrepareAiRequest(
                CHAT_ID,
                new SendMessageRequest("Привет"),
                currentUser
        );

        verify(chatMessageRepository).findBySession_IdOrderByCreatedAtDescIdDesc(
                eq(CHAT_ID),
                argThat(pageable ->
                        pageable.getPageNumber() == 0
                                && pageable.getPageSize() == 30
                )
        );
    }

    @Test
    void saveUserMessageAndPrepareAiRequest_shouldNotWritePromptContentToAudit() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = chatSessionEntity();

        when(chatSessionRepository.findByIdAndUser_IdAndOrganization_Id(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.of(session));

        when(chatMessageRepository.findBySession_IdOrderByCreatedAtDescIdDesc(
                eq(CHAT_ID),
                any(Pageable.class)
        )).thenReturn(List.of());

        when(chatMessageRepository.save(any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> persistMessage(invocation.getArgument(0)));

        chatPersistenceService.saveUserMessageAndPrepareAiRequest(
                CHAT_ID,
                new SendMessageRequest("Секретный prompt"),
                currentUser
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> captor =
                ArgumentCaptor.forClass(java.util.Map.class);

        verify(auditEventService).record(
                eq(USER_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.CHAT_MESSAGE_SENT),
                captor.capture()
        );

        assertThat(captor.getValue()).containsKey("messageLength");
        assertThat(captor.getValue()).doesNotContainKey("content");
        assertThat(captor.getValue()).doesNotContainValue("Секретный prompt");
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

    private OrganizationEntity organizationEntity() {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setName("Demo Company");
        organization.setEnabled(true);

        return organization;
    }

    private UserEntity userEntity() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setEmail("admin@test.com");
        user.setEnabled(true);
        user.setOrganization(organizationEntity());

        return user;
    }

    private ChatSessionEntity chatSessionEntity() {
        UserEntity user = userEntity();

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(CHAT_ID);
        session.setUser(user);
        session.setOrganization(user.getOrganization());
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
        ChatSessionEntity session = chatSessionEntity();

        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setSession(session);
        message.setOrganization(session.getOrganization());
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

        if (message.getOrganization() == null && message.getSession() != null) {
            message.setOrganization(message.getSession().getOrganization());
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