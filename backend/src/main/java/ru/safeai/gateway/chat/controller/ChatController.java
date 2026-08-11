package ru.safeai.gateway.chat.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.dto.ChatCapabilitiesResponse;
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.ChatPageRequest;
import ru.safeai.gateway.chat.dto.ChatPageResponse;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.ChatTurnStatusResponse;
import ru.safeai.gateway.chat.dto.CreateChatRequest;
import ru.safeai.gateway.chat.dto.MessagePageRequest;
import ru.safeai.gateway.chat.dto.MessageResponse;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.dto.SendMessageResponse;
import ru.safeai.gateway.chat.service.ChatService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

@RestController
@RequestMapping("/api/chats")
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class ChatController {

    private final ChatService chatService;
    private final ChatProperties properties;

    public ChatController(
            ChatService chatService,
            ChatProperties properties
    ) {
        this.chatService = chatService;
        this.properties = properties;
    }

    /**
     * Runtime limits for the current deployment.
     * The endpoint is authenticated by the class-level @PreAuthorize rule.
     */
    @GetMapping("/capabilities")
    public ChatCapabilitiesResponse capabilities() {
        return new ChatCapabilitiesResponse(
                properties.maxMessageChars(),
                properties.maxChatPageSize(),
                properties.maxMessagePageSize(),
                properties.detailsMessageLimit()
        );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChatResponse create(
            @Valid @RequestBody CreateChatRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return chatService.create(request, currentUser);
    }

    @GetMapping
    public ChatPageResponse<ChatResponse> findAll(
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser,
            @Valid @ModelAttribute ChatPageRequest pageRequest
    ) {
        return ChatPageResponse.from(
                chatService.findAll(
                        currentUser,
                        pageRequest.toPageable(
                                properties.maxChatPageSize()
                        )
                )
        );
    }

    @GetMapping("/{chatId}")
    public ChatDetailsResponse findById(
            @PathVariable UUID chatId,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return chatService.findById(chatId, currentUser);
    }

    @PostMapping("/{chatId}/messages")
    public SendMessageResponse sendMessage(
            @PathVariable UUID chatId,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return chatService.sendMessage(chatId, request, currentUser);
    }

    @GetMapping("/{chatId}/messages")
    public ChatPageResponse<MessageResponse> findMessages(
            @PathVariable UUID chatId,
            @Valid @ModelAttribute MessagePageRequest pageRequest,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return ChatPageResponse.from(
                chatService.findMessages(
                        chatId,
                        pageRequest.toPageable(
                                properties.maxMessagePageSize()
                        ),
                        currentUser
                )
        );
    }

    /**
     * Canonical route is /turns/by-client-request/{clientRequestId}.
     * The legacy short route is intentionally kept during rolling deployments
     * so an already loaded frontend bundle remains compatible with a newer
     * backend and vice versa.
     */
    @GetMapping({
            "/{chatId}/turns/by-client-request/{clientRequestId}",
            "/{chatId}/turns/{clientRequestId}"
    })
    public ChatTurnStatusResponse findTurnStatus(
            @PathVariable UUID chatId,
            @PathVariable UUID clientRequestId,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return chatService.findTurnStatus(
                chatId,
                clientRequestId,
                currentUser
        );
    }
}
