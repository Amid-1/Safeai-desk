package ru.safeai.gateway.chat.controller;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.chat.dto.AutomaticChatTitleRequest;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.UpdateChatTitleRequest;
import ru.safeai.gateway.chat.title.ChatTitleService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Objects;
import java.util.UUID;

/**
 * Explicit user-driven title changes. Authorization, tenant scope, chat mutex,
 * title provenance and mandatory audit are enforced in ChatTitleService.
 */
@RestController
@RequestMapping("/api/chats")
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class ChatTitleController {

    private final ChatTitleService chatTitleService;

    public ChatTitleController(ChatTitleService chatTitleService) {
        this.chatTitleService = Objects.requireNonNull(chatTitleService);
    }

    /**
     * Assign a deterministic title on first user submission, independently
     * of AI routing/provider availability. Not an AI request or a turn.
     * Owner/tenant checks and concurrency protection live in the service.
     */
    @PostMapping("/{chatId}/automatic-title")
    public ChatResponse assignAutomaticTitle(
            @PathVariable UUID chatId,
            @Valid @RequestBody AutomaticChatTitleRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return chatTitleService.assignAutomaticTitle(chatId, request, currentUser);
    }

    @PatchMapping("/{chatId}")
    public ChatResponse rename(
            @PathVariable UUID chatId,
            @Valid @RequestBody UpdateChatTitleRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return chatTitleService.rename(chatId, request, currentUser);
    }
}
