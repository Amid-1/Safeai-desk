package ru.safeai.gateway.chat.controller;

import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.dto.*;
import ru.safeai.gateway.chat.service.ChatLifecycleService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Objects;
import java.util.UUID;

/** All routes are tenant-scoped in the service, never by a user-supplied organization ID. */
@RestController
@RequestMapping("/api/chats")
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class ChatLifecycleController {
    private final ChatLifecycleService service;
    private final ChatProperties properties;

    public ChatLifecycleController(ChatLifecycleService service, ChatProperties properties) {
        this.service = Objects.requireNonNull(service);
        this.properties = Objects.requireNonNull(properties);
    }

    @GetMapping("/archived")
    public ChatPageResponse<ChatResponse> archived(
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal principal,
            @Valid @ModelAttribute ChatPageRequest page) {
        Slice<ChatResponse> slice = service.archived(principal,
                page.toPageable(properties.maxChatPageSize()));
        return ChatPageResponse.from(slice);
    }

    @GetMapping("/{chatId}/archived")
    public ChatDetailsResponse archivedDetails(
            @PathVariable UUID chatId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal principal) {
        return service.archivedDetails(chatId, principal);
    }

    @PostMapping("/{chatId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID chatId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal principal) {
        service.archive(chatId, principal);
    }

    @PostMapping("/{chatId}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID chatId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal principal) {
        service.restore(chatId, principal);
    }

    @GetMapping("/{chatId}/erasure/capability")
    @PreAuthorize("hasRole('ADMIN')")
    public ChatErasureCapabilityResponse erasureCapability(@PathVariable UUID chatId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal principal) {
        return service.capability(chatId, principal);
    }

    @DeleteMapping("/{chatId}/erasure")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    public ChatErasureOperationResponse erase(@PathVariable UUID chatId,
            @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ChatErasureRequest confirmation,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal principal) {
        return service.acceptErasure(chatId, parseVersion(ifMatch), idempotencyKey,
                confirmation, principal);
    }

    @GetMapping("/erasure-operations")
    @PreAuthorize("hasRole('ADMIN')")
    public java.util.List<ChatErasureOperationResponse> erasureOperations(
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal principal) {
        return service.listErasureOperations(principal);
    }

    @GetMapping("/{chatId}/erasure")
    @PreAuthorize("hasRole('ADMIN')")
    public ChatErasureOperationResponse erasureStatus(@PathVariable UUID chatId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal principal) {
        return service.erasureStatus(chatId, principal);
    }

    static long parseVersion(@Nullable String ifMatch) {
        if (ifMatch == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.PRECONDITION_REQUIRED,"Для удаления обязателен If-Match");
        }
        // Strong, single, quoted decimal ETag only. A signed long has at most
        // 19 decimal digits, plus two quotes. Fail early on oversized headers.
        if (ifMatch.length() < 3 || ifMatch.length() > 21
                || !ifMatch.matches("\"(0|[1-9][0-9]*)\"")) {
            throw new BadRequestException("If-Match должен содержать версию чата в кавычках.");
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Недопустимая версия чата.");
        }
    }
}
