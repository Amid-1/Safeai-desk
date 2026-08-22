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
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationResponse;
import ru.safeai.gateway.knowledge.service.KnowledgeEvaluationService;

import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/evaluations")
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeEvaluationController {

    private final KnowledgeEvaluationService service;

    public KnowledgeEvaluationController(KnowledgeEvaluationService service) {
        this.service = service;
    }

    @PostMapping
    public KnowledgeEvaluationResponse evaluate(
            @PathVariable UUID knowledgeBaseId,
            @Valid @RequestBody KnowledgeEvaluationRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal user
    ) {
        return service.evaluate(knowledgeBaseId, request, user);
    }
}
