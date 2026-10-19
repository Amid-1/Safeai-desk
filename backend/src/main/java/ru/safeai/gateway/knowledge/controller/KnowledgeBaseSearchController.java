package ru.safeai.gateway.knowledge.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeBasePageResponse;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseSearchService;

/**
 * Additive endpoint: leaves GET /api/knowledge-bases and its clients intact.
 */
@Validated
@RestController
@RequestMapping("/api/knowledge-bases/search")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class KnowledgeBaseSearchController {

    private final KnowledgeBaseSearchService searchService;

    @GetMapping
    public KnowledgeBasePageResponse search(
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) KnowledgeBaseVisibility visibility,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size
    ) {
        return KnowledgeBasePageResponse.from(
                searchService.search(currentUser, query, enabled, visibility, page, size)
        );
    }
}
