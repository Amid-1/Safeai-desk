package ru.safeai.gateway.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.CreateChatRequest;
import ru.safeai.gateway.chat.dto.MessageResponse;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ChatProperties.class)
public class ChatService {

    private static final Set<String> ALLOWED_CHAT_SORT =
            Set.of(
                    "updatedAt",
                    "createdAt",
                    "title",
                    "id"
            );

    private final ChatSessionRepository
            chatSessionRepository;

    private final ChatMessageRepository
            chatMessageRepository;

    private final UserRepository userRepository;
    private final AiProvider aiProvider;
    private final AuditEventService auditEventService;

    private final ChatPersistenceService
            chatPersistenceService;

    private final ChatTurnReservationService
            chatTurnReservationService;

    private final ChatMapper chatMapper;
    private final ChatLockService chatLockService;
    private final ChatProperties chatProperties;

    @Transactional
    public ChatResponse create(
            CreateChatRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        UserEntity user = userRepository
                .findByIdAndOrganizationId(
                        currentUser.getId(),
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Пользователь не найден: "
                                        + currentUser.getId()
                        )
                );

        String title =
                normalizeTitle(request.title());

        ChatSessionEntity session =
                new ChatSessionEntity();

        session.setUser(user);
        session.setOrganization(
                user.getOrganization()
        );
        session.setTitle(title);

        ChatSessionEntity saved =
                chatSessionRepository
                        .saveAndFlush(session);

        auditEventService.record(
                currentUser,
                saved.getOrganization().getId(),
                AuditEventType.CHAT_CREATED,
                Map.of(
                        "chatId",
                        saved.getId(),
                        "titleLength",
                        title.length(),
                        "defaultTitle",
                        request.title() == null
                                || request.title().isBlank()
                )
        );

        return chatMapper.toChatResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<ChatResponse> findAll(
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        Objects.requireNonNull(
                pageable,
                "pageable не должен быть null"
        );

        validateSort(pageable);

        Pageable deterministic = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                normalizeSort(pageable.getSort())
        );

        return chatSessionRepository
                .findByUser_IdAndOrganization_Id(
                        currentUser.getId(),
                        currentUser.getOrganizationId(),
                        deterministic
                )
                .map(chatMapper::toChatResponse);
    }

    @Transactional(readOnly = true)
    public ChatDetailsResponse findById(
            UUID chatId,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session =
                findOwnedSession(
                        chatId,
                        currentUser
                );

        List<MessageResponse> messages =
                chatMessageRepository
                        .findBySession_IdOrderByCreatedAtDescIdDesc(
                                session.getId(),
                                PageRequest.of(
                                        0,
                                        chatProperties
                                                .effectiveDetailsMessageLimit()
                                )
                        )
                        .stream()
                        .sorted(
                                Comparator
                                        .comparing(
                                                ChatMessageEntity::getCreatedAt
                                        )
                                        .thenComparing(
                                                ChatMessageEntity::getId
                                        )
                        )
                        .map(chatMapper::toMessageResponse)
                        .toList();

        return chatMapper.toChatDetailsResponse(
                session,
                messages
        );
    }

    public ChatDetailsResponse sendMessage(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );

        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        String content =
                validateAndNormalizeLineEndings(
                        request.content()
                );

        SendMessageRequest normalizedRequest =
                new SendMessageRequest(
                        content,
                        request.clientRequestId()
                );

        /*
         * Tenant-safe existence check выполняется до создания Redis lock,
         * чтобы чужой/несуществующий chatId не позволял расходовать lock keys.
         */
        chatPersistenceService.assertOwnedChatExists(
                chatId,
                currentUser
        );

        ChatLockService.ChatLock lock =
                chatLockService.lock(chatId);

        try {
            /*
             * Внутри отдельной короткой transaction:
             * replay -> без rate limit;
             * new turn -> reservation + rate limit до commit.
             */
            ChatProcessingContext context =
                    chatTurnReservationService
                            .reserveOrReplay(
                                    chatId,
                                    normalizedRequest,
                                    currentUser
                            );

            if (context.replay()) {
                return chatPersistenceService
                        .returnExistingResult(
                                context.chatId(),
                                context.userMessageId(),
                                currentUser
                        );
            }

            AiChatResponse aiResponse;

            try {
                aiResponse = aiProvider.sendMessage(
                        context.aiRequest()
                );
            } catch (RuntimeException providerException) {
                persistProviderFailure(
                        context,
                        providerException,
                        currentUser
                );

                throw providerException;
            }

            chatLockService.ensureValid(lock);

            try {
                return chatPersistenceService
                        .saveAssistantMessageAndReturnChat(
                                context.chatId(),
                                context.userMessageId(),
                                aiResponse,
                                currentUser
                        );
            } catch (RuntimeException persistenceException) {
                log.error(
                        "AI response received but could not be persisted: "
                                + "chatId={}, userMessageId={}",
                        context.chatId(),
                        context.userMessageId(),
                        persistenceException
                );

                throw persistenceException;
            }
        } finally {
            chatLockService.unlockQuietly(lock);
        }
    }

    @Transactional(readOnly = true)
    public Page<MessageResponse> findMessages(
            UUID chatId,
            int page,
            int size,
            SafeAiUserPrincipal currentUser
    ) {
        ChatSessionEntity session =
                findOwnedSession(
                        chatId,
                        currentUser
                );

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1,
                100
        );

        return chatMessageRepository
                .findBySession_IdOrderByCreatedAtDescIdDesc(
                        session.getId(),
                        PageRequest.of(
                                safePage,
                                safeSize
                        )
                )
                .map(chatMapper::toMessageResponse);
    }

    private void persistProviderFailure(
            ChatProcessingContext context,
            RuntimeException providerException,
            SafeAiUserPrincipal currentUser
    ) {
        try {
            chatPersistenceService
                    .saveFailedAssistantMessage(
                            context.chatId(),
                            context.userMessageId(),
                            providerException,
                            currentUser
                    );
        } catch (RuntimeException persistenceException) {
            providerException.addSuppressed(
                    persistenceException
            );

            log.error(
                    "Failed to persist failed AI response: "
                            + "chatId={}, userMessageId={}",
                    context.chatId(),
                    context.userMessageId(),
                    persistenceException
            );
        }
    }

    private ChatSessionEntity findOwnedSession(
            UUID chatId,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );

        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        return chatSessionRepository
                .findByIdAndUser_IdAndOrganization_Id(
                        chatId,
                        currentUser.getId(),
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Чат не найден: "
                                        + chatId
                        )
                );
    }

    private String normalizeTitle(
            String title
    ) {
        return title == null || title.isBlank()
                ? "Новый чат"
                : title.trim();
    }

    private String validateAndNormalizeLineEndings(
            String content
    ) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException(
                    "Сообщение не должно быть пустым"
            );
        }

        return content
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private void validateSort(
            Pageable pageable
    ) {
        for (Sort.Order order : pageable.getSort()) {
            if (!ALLOWED_CHAT_SORT.contains(
                    order.getProperty()
            )) {
                throw new BadRequestException(
                        "Сортировка по полю не разрешена: "
                                + order.getProperty()
                );
            }
        }
    }

    private Sort normalizeSort(
            Sort sort
    ) {
        Sort effective = sort.isUnsorted()
                ? Sort.by(
                        Sort.Order.desc("updatedAt")
                )
                : sort;

        boolean hasId = effective
                .stream()
                .anyMatch(order ->
                        "id".equals(
                                order.getProperty()
                        )
                );

        return hasId
                ? effective
                : effective.and(
                        Sort.by(
                                Sort.Order.desc("id")
                        )
                );
    }
}
