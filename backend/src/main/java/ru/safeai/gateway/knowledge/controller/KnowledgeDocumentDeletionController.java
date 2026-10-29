package ru.safeai.gateway.knowledge.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentDeletionResponse;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionAcceptanceService;

import java.util.UUID;

/**
 * Destructive endpoint only. Read-only deletion history is mapped separately
 * so existing operations remain observable while deletion flags are OFF.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}")
@PreAuthorize("hasAnyRole('ADMIN','USER')")
@ConditionalOnProperty(prefix = "safeai.knowledge.deletion", name = {"accept-enabled", "executor-enabled"},
        havingValue = "true")
public class KnowledgeDocumentDeletionController {
    private final KnowledgeDocumentDeletionAcceptanceService acceptance;

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeDocumentDeletionResponse delete(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID documentId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal user) {
        return acceptance.accept(knowledgeBaseId, documentId,
                parseExpectedVersion(ifMatch), idempotencyKey, user);
    }

    private static long parseExpectedVersion(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"(0|[1-9][0-9]*)\"")) {
            throw new BadRequestException("Требуется If-Match с версией документа в кавычках.");
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Некорректная версия документа.");
        }
    }
}
