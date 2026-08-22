package ru.safeai.gateway.knowledge.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.AnswerPassportResponse;
import ru.safeai.gateway.knowledge.rag.AnswerPassportService;

import java.util.UUID;

@RestController
@RequestMapping("/api/chats/{chatId}/turns/{turnId}/answer-passport")
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class AnswerPassportController {

    private final AnswerPassportService service;

    public AnswerPassportController(AnswerPassportService service) {
        this.service = service;
    }

    @GetMapping
    public AnswerPassportResponse get(
            @PathVariable UUID chatId,
            @PathVariable UUID turnId,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal user
    ) {
        return service.requireByTurn(
                chatId,
                turnId,
                user.getOrganizationId(),
                user.getId()
        );
    }
}
