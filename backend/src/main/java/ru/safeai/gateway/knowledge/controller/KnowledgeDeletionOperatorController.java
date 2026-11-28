package ru.safeai.gateway.knowledge.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.service.KnowledgeDeletionOperatorService;

import java.util.UUID;

/** Admin-only operator boundary. Diagnostics are read-only even when recovery is OFF. */
@RestController
@RequestMapping("/api/knowledge-bases/{baseId}")
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeDeletionOperatorController {
    private final KnowledgeDeletionOperatorService service;

    public KnowledgeDeletionOperatorController(KnowledgeDeletionOperatorService service) {
        this.service = service;
    }

    @GetMapping("/document-deletions/{operationId}/diagnostics")
    public KnowledgeDeletionOperatorService.Diagnostics documentDiagnostics(
            @PathVariable UUID baseId, @PathVariable UUID operationId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.documentDiagnostics(baseId, operationId, actor);
    }

    @GetMapping("/base-deletions/{operationId}/diagnostics")
    public KnowledgeDeletionOperatorService.Diagnostics baseDiagnostics(
            @PathVariable UUID baseId, @PathVariable UUID operationId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.baseDiagnostics(baseId, operationId, actor);
    }

    @PostMapping("/document-deletions/{operationId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeDeletionOperatorService.Diagnostics resumeDocument(
            @PathVariable UUID baseId, @PathVariable UUID operationId,
            @RequestBody ResumeRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.resumeDocument(baseId, operationId, request.expectedUpdatedAt(),
                request.reasonCode(), actor);
    }

    @PostMapping("/base-deletions/{operationId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeDeletionOperatorService.Diagnostics resumeBase(
            @PathVariable UUID baseId, @PathVariable UUID operationId,
            @RequestBody ResumeRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.resumeBase(baseId, operationId, request.expectedUpdatedAt(),
                request.reasonCode(), actor);
    }

    public record ResumeRequest(String expectedUpdatedAt, String reasonCode) { }
}
