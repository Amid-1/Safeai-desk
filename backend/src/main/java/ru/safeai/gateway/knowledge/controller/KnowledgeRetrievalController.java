package ru.safeai.gateway.knowledge.controller;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalResponse;
import ru.safeai.gateway.knowledge.service.KnowledgeRetrievalService;

import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/retrieval")
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class KnowledgeRetrievalController {

    private final KnowledgeRetrievalService service;

    public KnowledgeRetrievalController(KnowledgeRetrievalService service) {
        this.service = service;
    }

    @PostMapping
    public KnowledgeRetrievalResponse retrieve(
            @PathVariable UUID knowledgeBaseId,
            @Valid @RequestBody KnowledgeRetrievalRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal user
    ) {
        return service.retrieve(knowledgeBaseId, request, user);
    }
}
