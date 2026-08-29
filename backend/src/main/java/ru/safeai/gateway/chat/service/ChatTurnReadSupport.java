package ru.safeai.gateway.chat.service;

import ru.safeai.gateway.chat.dto.ChatTurnStatusResponse;
import ru.safeai.gateway.chat.dto.MessageResponse;
import ru.safeai.gateway.chat.dto.SendMessageResponse;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatTurnEntity;
import ru.safeai.gateway.chat.entity.ChatTurnState;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.ChatTurnRepository;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.AnswerPassportResponse;
import ru.safeai.gateway.knowledge.rag.AnswerPassportService;

import java.util.Objects;
import java.util.UUID;

/**
 * Read-side support for immutable/replay chat-turn evidence.
 *
 * <p>Transactions remain declared on {@link ChatTurnFinalizationService}; this
 * collaborator only centralizes tenant-safe lookups and DTO reconstruction.</p>
 */
final class ChatTurnReadSupport {

    private final ChatTurnRepository turnRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatMapper mapper;
    private final AnswerPassportService answerPassportService;

    ChatTurnReadSupport(
            ChatTurnRepository turnRepository,
            ChatMessageRepository messageRepository,
            ChatMapper mapper,
            AnswerPassportService answerPassportService
    ) {
        this.turnRepository = Objects.requireNonNull(
                turnRepository,
                "turnRepository не должен быть null"
        );
        this.messageRepository = Objects.requireNonNull(
                messageRepository,
                "messageRepository не должен быть null"
        );
        this.mapper = Objects.requireNonNull(mapper, "mapper не должен быть null");
        this.answerPassportService = Objects.requireNonNull(
                answerPassportService,
                "answerPassportService не должен быть null"
        );
    }

    SendMessageResponse replaySucceeded(
            ChatProcessingContext context,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(context, "context не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        ChatTurnEntity turn = ownedTurn(context, currentUser);

        if (turn.getState() != ChatTurnState.SUCCEEDED
                || turn.getAssistantMessageId() == null
                || turn.getCompletedAt() == null) {
            throw new IllegalStateException(
                    "Replay вызван для неуспешного turn: " + turn.getId()
            );
        }

        MessageResponse user = mapper.toMessageResponse(
                ownedMessage(
                        turn.getUserMessageId(),
                        context.chatId(),
                        currentUser.getOrganizationId()
                )
        );
        MessageResponse assistant = mapper.toMessageResponse(
                ownedMessage(
                        turn.getAssistantMessageId(),
                        context.chatId(),
                        currentUser.getOrganizationId()
                )
        );
        AnswerPassportResponse answerPassport = answerPassportService.findByTurn(
                turn.getId(),
                currentUser
        );

        return new SendMessageResponse(
                context.chatId(),
                turn.getId(),
                turn.getClientRequestId(),
                turn.getProviderOperationId(),
                turn.getState().name(),
                true,
                user,
                assistant,
                turn.getUpdatedAt(),
                turn.getCreatedAt(),
                turn.getCompletedAt(),
                answerPassport
        );
    }

    ChatTurnStatusResponse status(
            UUID chatId,
            UUID clientRequestId,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        Objects.requireNonNull(clientRequestId, "clientRequestId не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        ChatTurnEntity turn = turnRepository
                .findBySession_IdAndClientRequestIdAndUser_IdAndOrganization_Id(
                        chatId,
                        clientRequestId,
                        currentUser.getId(),
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() -> new ResourceNotFoundException("Chat turn не найден"));

        MessageResponse user = mapper.toMessageResponse(
                ownedMessage(
                        turn.getUserMessageId(),
                        chatId,
                        currentUser.getOrganizationId()
                )
        );
        MessageResponse assistant = turn.getAssistantMessageId() == null
                ? null
                : mapper.toMessageResponse(
                        ownedMessage(
                                turn.getAssistantMessageId(),
                                chatId,
                                currentUser.getOrganizationId()
                        )
                );

        return mapper.toTurnStatusResponse(turn, user, assistant);
    }

    ChatTurnEntity ownedTurn(
            ChatProcessingContext context,
            SafeAiUserPrincipal currentUser
    ) {
        return turnRepository
                .findByIdAndSession_IdAndUser_IdAndOrganization_Id(
                        context.turnId(),
                        context.chatId(),
                        currentUser.getId(),
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Chat turn не найден: " + context.turnId()
                ));
    }

    ChatMessageEntity ownedMessage(
            UUID messageId,
            UUID chatId,
            UUID organizationId
    ) {
        return messageRepository
                .findOwnedMessage(messageId, chatId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Сообщение не найдено: " + messageId
                ));
    }
}
