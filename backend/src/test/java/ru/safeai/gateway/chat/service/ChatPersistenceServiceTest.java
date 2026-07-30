package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatMessageRole;
import ru.safeai.gateway.chat.entity.ChatMessageStatus;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.ChatBusyException;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatPersistenceServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID CHAT_ID =
            UUID.fromString("2251f787-044c-4ef8-80d7-60d3ce4d72af");

    private static final UUID USER_MESSAGE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID PROVIDER_MESSAGE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Instant CREATED_AT =
            Instant.parse("2026-06-12T12:00:00Z");

    private static final Instant PRICING_CALCULATED_AT =
            Instant.parse("2026-06-12T12:00:01Z");

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private AuditEventService auditEventService;

    private ChatPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new ChatPersistenceService(
                chatSessionRepository,
                chatMessageRepository,
                auditEventService,
                new ChatMapper(),
                new ChatProperties(50, 30, 8_000),
                new AiHistoryBuilder()
        );
    }

    @Test
    void saveUserMessageSavesClientRequestIdAndUsesCompletedHistoryQuery() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = session();
        UUID clientRequestId = UUID.randomUUID();

        ChatMessageEntity historyMessage = message(
                ChatMessageRole.USER,
                "Старое сообщение"
        );

        when(chatSessionRepository
                .findByIdAndUser_IdAndOrganization_Id(
                        CHAT_ID,
                        USER_ID,
                        ORGANIZATION_ID
                ))
                .thenReturn(Optional.of(session));

        when(chatMessageRepository
                .findBySession_IdAndClientRequestIdAndRole(
                        CHAT_ID,
                        clientRequestId,
                        ChatMessageRole.USER
                ))
                .thenReturn(Optional.empty());

        when(chatMessageRepository.findCompletedHistoryForAi(
                eq(CHAT_ID),
                any(Pageable.class)
        ))
                .thenReturn(List.of(historyMessage));

        when(chatMessageRepository.saveAndFlush(
                any(ChatMessageEntity.class)
        ))
                .thenAnswer(invocation -> {
                    ChatMessageEntity savedMessage =
                            invocation.getArgument(0);

                    savedMessage.setId(USER_MESSAGE_ID);
                    savedMessage.setCreatedAt(CREATED_AT);

                    return savedMessage;
                });

        ChatProcessingContext context =
                service.saveUserMessageAndPrepareAiRequest(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Новое сообщение",
                                clientRequestId
                        ),
                        currentUser
                );

        assertThat(context.replay()).isFalse();
        assertThat(context.clientRequestId())
                .isEqualTo(clientRequestId);

        assertThat(context.aiRequest().history())
                .extracting(AiMessage::content)
                .containsExactly("Старое сообщение");

        ArgumentCaptor<ChatMessageEntity> captor =
                ArgumentCaptor.forClass(ChatMessageEntity.class);

        verify(chatMessageRepository)
                .saveAndFlush(captor.capture());

        ChatMessageEntity saved = captor.getValue();

        assertThat(saved.getClientRequestId())
                .isEqualTo(clientRequestId);

        assertThat(saved.getRole())
                .isEqualTo(ChatMessageRole.USER);

        assertThat(saved.getStatus())
                .isEqualTo(ChatMessageStatus.COMPLETED);

        verify(auditEventService).record(
        same(currentUser),
        eq(ORGANIZATION_ID),
        eq(AuditEventType.CHAT_MESSAGE_SENT),
        anyMap()
);
    }

    @Test
    void existingCompletedTurnReturnsReplayContext() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = session();
        UUID clientRequestId = UUID.randomUUID();

        ChatMessageEntity userMessage = message(
                ChatMessageRole.USER,
                "Привет"
        );
        userMessage.setId(USER_MESSAGE_ID);
        userMessage.setClientRequestId(clientRequestId);

        ChatMessageEntity assistantMessage = message(
                ChatMessageRole.ASSISTANT,
                "Ответ"
        );
        assistantMessage.setReplyToMessageId(USER_MESSAGE_ID);

        when(chatSessionRepository
                .findByIdAndUser_IdAndOrganization_Id(
                        CHAT_ID,
                        USER_ID,
                        ORGANIZATION_ID
                ))
                .thenReturn(Optional.of(session));

        when(chatMessageRepository
                .findBySession_IdAndClientRequestIdAndRole(
                        CHAT_ID,
                        clientRequestId,
                        ChatMessageRole.USER
                ))
                .thenReturn(Optional.of(userMessage));

        when(chatMessageRepository
                .findFirstBySession_IdAndReplyToMessageIdAndRoleOrderByCreatedAtDescIdDesc(
                        CHAT_ID,
                        USER_MESSAGE_ID,
                        ChatMessageRole.ASSISTANT
                ))
                .thenReturn(Optional.of(assistantMessage));

        ChatProcessingContext context =
                service.saveUserMessageAndPrepareAiRequest(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Привет",
                                clientRequestId
                        ),
                        currentUser
                );

        assertThat(context.replay()).isTrue();
        assertThat(context.aiRequest()).isNull();

        verify(chatMessageRepository, never())
                .saveAndFlush(any(ChatMessageEntity.class));
    }

    @Test
    void existingUnfinishedTurnThrowsChatBusy() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = session();
        UUID clientRequestId = UUID.randomUUID();

        ChatMessageEntity userMessage = message(
                ChatMessageRole.USER,
                "Привет"
        );
        userMessage.setId(USER_MESSAGE_ID);

        when(chatSessionRepository
                .findByIdAndUser_IdAndOrganization_Id(
                        CHAT_ID,
                        USER_ID,
                        ORGANIZATION_ID
                ))
                .thenReturn(Optional.of(session));

        when(chatMessageRepository
                .findBySession_IdAndClientRequestIdAndRole(
                        CHAT_ID,
                        clientRequestId,
                        ChatMessageRole.USER
                ))
                .thenReturn(Optional.of(userMessage));

        when(chatMessageRepository
                .findFirstBySession_IdAndReplyToMessageIdAndRoleOrderByCreatedAtDescIdDesc(
                        CHAT_ID,
                        USER_MESSAGE_ID,
                        ChatMessageRole.ASSISTANT
                ))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.saveUserMessageAndPrepareAiRequest(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Привет",
                                clientRequestId
                        ),
                        currentUser
                ))
                .isInstanceOf(ChatBusyException.class)
                .hasMessageContaining("clientRequestId");
    }

    @Test
    void saveAssistantMessageSetsReplyAndAiMetadata() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = session();

        when(chatSessionRepository
                .findByIdAndUser_IdAndOrganization_Id(
                        CHAT_ID,
                        USER_ID,
                        ORGANIZATION_ID
                ))
                .thenReturn(Optional.of(session));

        when(chatMessageRepository
                .existsByIdAndSession_IdAndSession_User_IdAndSession_Organization_IdAndRole(
                        USER_MESSAGE_ID,
                        CHAT_ID,
                        USER_ID,
                        ORGANIZATION_ID,
                        ChatMessageRole.USER
                ))
                .thenReturn(true);

        when(chatMessageRepository.saveAndFlush(
                any(ChatMessageEntity.class)
        ))
                .thenAnswer(invocation -> {
                    ChatMessageEntity savedMessage =
                            invocation.getArgument(0);

                    savedMessage.setId(UUID.randomUUID());
                    savedMessage.setCreatedAt(
                            CREATED_AT.plusSeconds(1)
                    );

                    return savedMessage;
                });

        when(chatMessageRepository
                .findBySession_IdOrderByCreatedAtDescIdDesc(
                        eq(CHAT_ID),
                        any(Pageable.class)
                ))
                .thenReturn(new PageImpl<>(List.of()));

        AiChatResponse aiResponse = completedMockResponse();

        service.saveAssistantMessageAndReturnChat(
                CHAT_ID,
                USER_MESSAGE_ID,
                aiResponse,
                currentUser
        );

        ArgumentCaptor<ChatMessageEntity> captor =
                ArgumentCaptor.forClass(ChatMessageEntity.class);

        verify(chatMessageRepository)
                .saveAndFlush(captor.capture());

        ChatMessageEntity saved = captor.getValue();

        assertThat(saved.getReplyToMessageId())
                .isEqualTo(USER_MESSAGE_ID);

        assertThat(saved.getStatus())
                .isEqualTo(ChatMessageStatus.COMPLETED);

        assertThat(saved.getModel())
                .isEqualTo("mock-safeai");

        assertThat(saved.getProviderMessageId())
                .isEqualTo(PROVIDER_MESSAGE_ID.toString());

        assertThat(saved.getAiResponseStatus())
                .isEqualTo(AiResponseStatus.COMPLETED);

        assertThat(saved.getFinishReason())
                .isEqualTo("mock_completed");

        assertThat(saved.getInputTokens()).isEqualTo(1);
        assertThat(saved.getOutputTokens()).isEqualTo(2);

        assertThat(saved.getUsageStatus())
                .isEqualTo(UsageStatus.AVAILABLE);

        assertThat(saved.getCostUsd())
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(saved.getPricingStatus())
                .isEqualTo(PricingStatus.FREE);

        assertThat(saved.getCurrency()).isEqualTo("USD");

        assertThat(saved.getPricingVersion())
                .isEqualTo("mock-2026-01");

        assertThat(saved.getPricingCalculatedAt())
                .isEqualTo(PRICING_CALCULATED_AT);
    }

    @Test
    void saveFailedAssistantMessageSetsReplyToMessageId() {
        SafeAiUserPrincipal currentUser = currentUser();
        ChatSessionEntity session = session();

        when(chatSessionRepository
                .findByIdAndUser_IdAndOrganization_Id(
                        CHAT_ID,
                        USER_ID,
                        ORGANIZATION_ID
                ))
                .thenReturn(Optional.of(session));

        when(chatMessageRepository
                .existsByIdAndSession_IdAndSession_User_IdAndSession_Organization_IdAndRole(
                        USER_MESSAGE_ID,
                        CHAT_ID,
                        USER_ID,
                        ORGANIZATION_ID,
                        ChatMessageRole.USER
                ))
                .thenReturn(true);

        when(chatMessageRepository.saveAndFlush(
                any(ChatMessageEntity.class)
        ))
                .thenAnswer(invocation -> {
                    ChatMessageEntity savedMessage =
                            invocation.getArgument(0);

                    savedMessage.setId(UUID.randomUUID());
                    savedMessage.setCreatedAt(
                            CREATED_AT.plusSeconds(1)
                    );

                    return savedMessage;
                });

        service.saveFailedAssistantMessage(
                CHAT_ID,
                USER_MESSAGE_ID,
                new RuntimeException("provider failed"),
                currentUser
        );

        ArgumentCaptor<ChatMessageEntity> captor =
                ArgumentCaptor.forClass(ChatMessageEntity.class);

        verify(chatMessageRepository)
                .saveAndFlush(captor.capture());

        ChatMessageEntity saved = captor.getValue();

        assertThat(saved.getReplyToMessageId())
                .isEqualTo(USER_MESSAGE_ID);

        assertThat(saved.getStatus())
                .isEqualTo(ChatMessageStatus.FAILED);
    }

    @Test
    void assertOwnedChatExistsThrowsWhenChatIsNotOwned() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatSessionRepository
                .existsByIdAndUser_IdAndOrganization_Id(
                        CHAT_ID,
                        USER_ID,
                        ORGANIZATION_ID
                ))
                .thenReturn(false);

        assertThatThrownBy(() ->
                service.assertOwnedChatExists(
                        CHAT_ID,
                        currentUser
                ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Чат не найден");

        verifyNoInteractions(
                chatMessageRepository,
                auditEventService
        );
    }

    private AiChatResponse completedMockResponse() {
        return new AiChatResponse(
                "Ответ",
                "mock-safeai",
                PROVIDER_MESSAGE_ID.toString(),
                AiResponseStatus.COMPLETED,
                "mock_completed",
                1,
                2,
                UsageStatus.AVAILABLE,
                BigDecimal.ZERO,
                PricingStatus.FREE,
                "USD",
                "mock-2026-01",
                PRICING_CALCULATED_AT
        );
    }

    private SafeAiUserPrincipal currentUser() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                0L,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        )
                )
        );
    }

    private ChatSessionEntity session() {
        OrganizationEntity organization =
                new OrganizationEntity();

        organization.setId(ORGANIZATION_ID);
        organization.setName("Demo Company");
        organization.setEnabled(true);

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setEmail("admin@test.com");
        user.setEnabled(true);
        user.setOrganization(organization);

        ChatSessionEntity chatSession =
                new ChatSessionEntity();

        chatSession.setId(CHAT_ID);
        chatSession.setUser(user);
        chatSession.setOrganization(organization);
        chatSession.setTitle("Чат");
        chatSession.setCreatedAt(CREATED_AT);
        chatSession.setUpdatedAt(CREATED_AT);

        return chatSession;
    }

    private ChatMessageEntity message(
            ChatMessageRole role,
            String content
    ) {
        ChatSessionEntity chatSession = session();

        ChatMessageEntity chatMessage =
                new ChatMessageEntity();

        chatMessage.setId(UUID.randomUUID());
        chatMessage.setSession(chatSession);
        chatMessage.setOrganization(
                chatSession.getOrganization()
        );
        chatMessage.setRole(role);
        chatMessage.setContent(content);
        chatMessage.setStatus(
                ChatMessageStatus.COMPLETED
        );
        chatMessage.setCreatedAt(CREATED_AT);

        return chatMessage;
    }
}
