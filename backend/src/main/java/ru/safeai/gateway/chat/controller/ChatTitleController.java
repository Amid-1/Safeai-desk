// backend/src/main/java/ru/safeai/gateway/chat/controller/ChatTitleController.java
package ru.safeai.gateway.chat.controller;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.UpdateChatTitleRequest;
import ru.safeai.gateway.chat.title.ChatTitleService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

@RestController
@RequestMapping("/api/chats")
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class ChatTitleController {

    private final ChatTitleService chatTitleService;

    public ChatTitleController(
            ChatTitleService chatTitleService
    ) {
        this.chatTitleService = chatTitleService;
    }

    @PatchMapping("/{chatId}")
    public ChatResponse rename(
            @PathVariable UUID chatId,
            @Valid @RequestBody UpdateChatTitleRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return chatTitleService.rename(
                chatId,
                request,
                currentUser
        );
    }
}
