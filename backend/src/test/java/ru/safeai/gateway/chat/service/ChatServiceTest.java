package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.CreateChatRequest;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.ratelimit.RedisRateLimitService;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private static final UUID PROVIDER_MESSAGE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Instant PRICING_CALCULATED_AT =
            Instant.parse("2026-06-12T12:00:01Z");

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

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(
                chatSessionRepository,
                chatMessageRepository,
                userRepository,
                aiProvider,
                auditEventService,
                chatPersistenceService,
                new ChatMapper(),
                rateLimitService,
                chatLockService,
                new ChatProperties(50, 30, 8_000)
        );
    }

    @Test
    void createUsesTenantSafeUserLookupAndSaveAndFlush() {
        SafeAiUserPrincipal currentUser = currentUser();

        when(userRepository.findByIdAndOrganizationId(
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.of(user()));

        when(chatSessionRepository.saveAndFlush(
                any(ChatSessionEntity.class)
        )).thenAnswer(invocation -> {
            ChatSessionEntity session = invocation.getArgument(0);
            session.setId(CHAT_ID);
            session.setCreatedAt(CREATED_AT);
            session.setUpdatedAt(CREATED_AT);
            return session;
        });

        var response = service.create(
                new CreateChatRequest(" Новый чат "),
                currentUser
        );

        assertThat(response.title()).isEqualTo("Новый чат");

        verify(userRepository).findByIdAndOrganizationId(
                USER_ID,
                ORGANIZATION_ID
        );

        verify(chatSessionRepository)
                .saveAndFlush(any(ChatSessionEntity.class));
    }

    @Test
    void sendMessagePreservesSpacesAndNormalizesLineEndings() {
        SafeAiUserPrincipal currentUser = currentUser();
        UUID clientRequestId = UUID.randomUUID();

        ChatLockService.ChatLock lock =
                org.mockito.Mockito.mock(
                        ChatLockService.ChatLock.class
                );

        AiChatRequest aiRequest = new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                "  line1\nline2\n  ",
                List.of()
        );

        ChatProcessingContext context =
                new ChatProcessingContext(
                        CHAT_ID,
                        USER_MESSAGE_ID,
                        clientRequestId,
                        aiRequest,
                        false
                );

        AiChatResponse aiResponse = completedMockResponse();

        ChatDetailsResponse expected =
                new ChatDetailsResponse(
                        CHAT_ID,
                        "Чат",
                        CREATED_AT,
                        CREATED_AT,
                        List.of()
                );

        when(chatLockService.lock(CHAT_ID))
                .thenReturn(lock);

        when(chatPersistenceService
                .saveUserMessageAndPrepareAiRequest(
                        eq(CHAT_ID),
                        any(SendMessageRequest.class),
                        eq(currentUser)
                )).thenReturn(context);

        when(aiProvider.sendMessage(aiRequest))
                .thenReturn(aiResponse);

        when(chatPersistenceService
                .saveAssistantMessageAndReturnChat(
                        CHAT_ID,
                        USER_MESSAGE_ID,
                        aiResponse,
                        currentUser
                )).thenReturn(expected);

        ChatDetailsResponse result = service.sendMessage(
                CHAT_ID,
                new SendMessageRequest(
                        "  line1\r\nline2\r  ",
                        clientRequestId
                ),
                currentUser
        );

        assertThat(result).isSameAs(expected);

        verify(chatPersistenceService)
                .saveUserMessageAndPrepareAiRequest(
                        eq(CHAT_ID),
                        argThat(request ->
                                "  line1\nline2\n  "
                                        .equals(request.content())
                                        && clientRequestId.equals(
                                        request.clientRequestId()
                                )
                        ),
                        eq(currentUser)
                );

        verify(chatLockService).ensureValid(lock);
        verify(chatLockService).unlockQuietly(lock);
    }

    @Test
    void replayDoesNotCallProvider() {
        SafeAiUserPrincipal currentUser = currentUser();
        UUID clientRequestId = UUID.randomUUID();

        ChatLockService.ChatLock lock =
                org.mockito.Mockito.mock(
                        ChatLockService.ChatLock.class
                );

        ChatProcessingContext context =
                new ChatProcessingContext(
                        CHAT_ID,
                        USER_MESSAGE_ID,
                        clientRequestId,
                        null,
                        true
                );

        ChatDetailsResponse expected =
                new ChatDetailsResponse(
                        CHAT_ID,
                        "Чат",
                        CREATED_AT,
                        CREATED_AT,
                        List.of()
                );

        when(chatLockService.lock(CHAT_ID))
                .thenReturn(lock);

        when(chatPersistenceService
                .saveUserMessageAndPrepareAiRequest(
                        eq(CHAT_ID),
                        any(),
                        eq(currentUser)
                )).thenReturn(context);

        when(chatPersistenceService.returnExistingResult(
                CHAT_ID,
                USER_MESSAGE_ID,
                currentUser
        )).thenReturn(expected);

        ChatDetailsResponse result = service.sendMessage(
                CHAT_ID,
                new SendMessageRequest(
                        "Привет",
                        clientRequestId
                ),
                currentUser
        );

        assertThat(result).isSameAs(expected);

        verify(aiProvider, never()).sendMessage(any());
        verify(chatLockService, never()).ensureValid(lock);
        verify(chatLockService).unlockQuietly(lock);
    }

    @Test
    void providerFailureRemainsPrimaryWhenFailurePersistenceAlsoFails() {
        SafeAiUserPrincipal currentUser = currentUser();
        RuntimeException providerException =
                new RuntimeException("provider failed");
        RuntimeException persistenceException =
                new RuntimeException("database failed");

        ChatLockService.ChatLock lock =
                org.mockito.Mockito.mock(
                        ChatLockService.ChatLock.class
                );

        AiChatRequest aiRequest = new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                "Привет",
                List.of()
        );

        ChatProcessingContext context =
                new ChatProcessingContext(
                        CHAT_ID,
                        USER_MESSAGE_ID,
                        UUID.randomUUID(),
                        aiRequest,
                        false
                );

        when(chatLockService.lock(CHAT_ID))
                .thenReturn(lock);

        when(chatPersistenceService
                .saveUserMessageAndPrepareAiRequest(
                        eq(CHAT_ID),
                        any(),
                        eq(currentUser)
                )).thenReturn(context);

        when(aiProvider.sendMessage(aiRequest))
                .thenThrow(providerException);

        doThrow(persistenceException)
                .when(chatPersistenceService)
                .saveFailedAssistantMessage(
                        CHAT_ID,
                        USER_MESSAGE_ID,
                        providerException,
                        currentUser
                );

        assertThatThrownBy(() -> service.sendMessage(
                CHAT_ID,
                new SendMessageRequest("Привет", null),
                currentUser
        ))
                .isSameAs(providerException)
                .satisfies(exception ->
                        assertThat(exception.getSuppressed())
                                .containsExactly(persistenceException)
                );

        verify(chatLockService).unlockQuietly(lock);
    }

    @Test
    void persistenceFailureAfterSuccessfulProviderDoesNotCreateFailedRow() {
        SafeAiUserPrincipal currentUser = currentUser();
        RuntimeException persistenceException =
                new RuntimeException("database failed");

        ChatLockService.ChatLock lock =
                org.mockito.Mockito.mock(
                        ChatLockService.ChatLock.class
                );

        AiChatRequest aiRequest = new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                "Привет",
                List.of()
        );

        ChatProcessingContext context =
                new ChatProcessingContext(
                        CHAT_ID,
                        USER_MESSAGE_ID,
                        UUID.randomUUID(),
                        aiRequest,
                        false
                );

        AiChatResponse aiResponse = completedMockResponse();

        when(chatLockService.lock(CHAT_ID))
                .thenReturn(lock);

        when(chatPersistenceService
                .saveUserMessageAndPrepareAiRequest(
                        eq(CHAT_ID),
                        any(),
                        eq(currentUser)
                )).thenReturn(context);

        when(aiProvider.sendMessage(aiRequest))
                .thenReturn(aiResponse);

        when(chatPersistenceService
                .saveAssistantMessageAndReturnChat(
                        CHAT_ID,
                        USER_MESSAGE_ID,
                        aiResponse,
                        currentUser
                )).thenThrow(persistenceException);

        assertThatThrownBy(() -> service.sendMessage(
                CHAT_ID,
                new SendMessageRequest("Привет", null),
                currentUser
        )).isSameAs(persistenceException);

        verify(chatPersistenceService, never())
                .saveFailedAssistantMessage(
                        any(),
                        any(),
                        any(),
                        any()
                );

        verify(chatLockService).ensureValid(lock);
        verify(chatLockService).unlockQuietly(lock);
    }

    @Test
    void findAllAddsDeterministicIdSort() {
        SafeAiUserPrincipal currentUser = currentUser();
        PageRequest requested = PageRequest.of(
                0,
                20,
                org.springframework.data.domain.Sort
                        .by("updatedAt")
                        .descending()
        );

        when(chatSessionRepository
                .findByUser_IdAndOrganization_Id(
                        eq(USER_ID),
                        eq(ORGANIZATION_ID),
                        any(Pageable.class)
                )).thenReturn(new PageImpl<>(
                List.of(),
                requested,
                0
        ));

        service.findAll(currentUser, requested);

        verify(chatSessionRepository)
                .findByUser_IdAndOrganization_Id(
                        eq(USER_ID),
                        eq(ORGANIZATION_ID),
                        argThat(pageable ->
                                pageable.getSort()
                                        .getOrderFor("updatedAt")
                                        != null
                                        && pageable.getSort()
                                        .getOrderFor("id")
                                        != null
                        )
                );
    }

    @Test
    void findAllRejectsUnsupportedSort() {
        Pageable pageable = PageRequest.of(
                0,
                20,
                org.springframework.data.domain.Sort.by("user.passwordHash")
        );

        assertThatThrownBy(() ->
                service.findAll(currentUser(), pageable)
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(
                        "Сортировка по полю не разрешена"
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

    private UserEntity user() {
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

        return user;
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
}