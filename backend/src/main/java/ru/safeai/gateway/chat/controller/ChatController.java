package ru.safeai.gateway.chat.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.chat.dto.*;
import ru.safeai.gateway.chat.service.ChatService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatController {
    private final ChatService chatService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChatResponse create(@Valid @RequestBody CreateChatRequest request,
                               @AuthenticationPrincipal SafeAiUserPrincipal currentUser) {
        return chatService.create(request, currentUser);
    }

    @GetMapping
    public Page<ChatResponse> findAll(
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser,
            @PageableDefault(size = 20, sort = {"updatedAt", "id"}, direction = Sort.Direction.DESC)
            Pageable pageable) {
        return chatService.findAll(currentUser, pageable);
    }

    @GetMapping("/{id}")
    public ChatDetailsResponse findById(@PathVariable UUID id,
                                        @AuthenticationPrincipal SafeAiUserPrincipal currentUser) {
        return chatService.findById(id, currentUser);
    }

    @PostMapping("/{id}/messages")
    public ChatDetailsResponse sendMessage(@PathVariable UUID id,
                                           @Valid @RequestBody SendMessageRequest request,
                                           @AuthenticationPrincipal SafeAiUserPrincipal currentUser) {
        return chatService.sendMessage(id, request, currentUser);
    }

    @GetMapping("/{id}/messages")
    public Page<MessageResponse> findMessages(@PathVariable UUID id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size,
                                              @AuthenticationPrincipal SafeAiUserPrincipal currentUser) {
        return chatService.findMessages(id, page, size, currentUser);
    }
}
