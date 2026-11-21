package ru.safeai.gateway.knowledge.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentDeletionResponse;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionAcceptanceService;

import java.util.List;
import java.util.UUID;

/** Read-only operation history is available even when destructive features are OFF. */
@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}")
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class KnowledgeDocumentDeletionReadController {
    private final KnowledgeDocumentDeletionAcceptanceService acceptance;

    public KnowledgeDocumentDeletionReadController(KnowledgeDocumentDeletionAcceptanceService acceptance) {
        this.acceptance = acceptance;
    }

    @GetMapping("/document-deletions/{operationId}")
    public KnowledgeDocumentDeletionResponse status(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID operationId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal user) {
        return acceptance.status(knowledgeBaseId, operationId, user);
    }

    @GetMapping("/document-deletions")
    public List<KnowledgeDocumentDeletionResponse> list(
            @PathVariable UUID knowledgeBaseId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal user) {
        return acceptance.list(knowledgeBaseId, user);
    }
}
