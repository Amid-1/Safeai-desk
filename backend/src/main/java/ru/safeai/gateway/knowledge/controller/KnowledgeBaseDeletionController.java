package ru.safeai.gateway.knowledge.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseDeletionResponse;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseDeletionService;

import java.util.UUID;

/** Capability/status remain readable while actual erasure is disabled. */
@RestController
@RequestMapping("/api/knowledge-bases")
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeBaseDeletionController {
    private final KnowledgeBaseDeletionService service;

    public KnowledgeBaseDeletionController(KnowledgeBaseDeletionService service) {
        this.service = service;
    }

    @GetMapping("/{baseId}/base-deletions/capability")
    public CapabilityResponse capability(@PathVariable UUID baseId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        boolean enabled = service.capability(baseId, actor);
        return new CapabilityResponse(enabled, enabled ? "ENABLED" : "FEATURE_DISABLED");
    }

    @DeleteMapping("/{baseId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeBaseDeletionResponse delete(@PathVariable UUID baseId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.accept(baseId, parseVersion(ifMatch), idempotencyKey, actor);
    }

    @GetMapping("/base-deletions")
    public java.util.List<KnowledgeBaseDeletionResponse> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.list(actor);
    }

    @GetMapping("/{baseId}/base-deletions/current")
    public KnowledgeBaseDeletionResponse current(@PathVariable UUID baseId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.current(baseId, actor);
    }

    @GetMapping("/{baseId}/base-deletions/{operationId}")
    public KnowledgeBaseDeletionResponse status(@PathVariable UUID baseId,
            @PathVariable UUID operationId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.status(baseId, operationId, actor);
    }

    private static long parseVersion(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"(0|[1-9][0-9]*)\"")) {
            throw new BadRequestException("If-Match должен содержать версию базы в кавычках.");
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Недопустимая версия базы знаний.");
        }
    }

    public record CapabilityResponse(boolean allowed, String reason) { }
}
