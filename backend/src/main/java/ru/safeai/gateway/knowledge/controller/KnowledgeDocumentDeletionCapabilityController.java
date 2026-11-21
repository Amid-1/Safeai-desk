package ru.safeai.gateway.knowledge.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.service.KnowledgeAccessService;

import java.util.UUID;

/**
 * Read-only capability endpoint. It is intentionally mapped even when hard deletion
 * is disabled, so the UI does not mistake an absent controller for a network error.
 * The destructive DELETE endpoint remains separately gated by BOTH feature flags.
 */
@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}")
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class KnowledgeDocumentDeletionCapabilityController {

    private final KnowledgeAccessService access;
    private final boolean acceptEnabled;
    private final boolean executorEnabled;

    public KnowledgeDocumentDeletionCapabilityController(
            KnowledgeAccessService access,
            @Value("${safeai.knowledge.deletion.accept-enabled:false}") boolean acceptEnabled,
            @Value("${safeai.knowledge.deletion.executor-enabled:false}") boolean executorEnabled) {
        this.access = access;
        this.acceptEnabled = acceptEnabled;
        this.executorEnabled = executorEnabled;
    }

    @GetMapping("/document-deletions/capability")
    @Transactional(readOnly = true)
    public CapabilityResponse capability(
            @PathVariable UUID knowledgeBaseId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal user) {
        // Server-side tenant and OWNER checks apply even if the feature is disabled.
        access.requireAccess(knowledgeBaseId, user, KnowledgeBaseAccessLevel.OWNER);
        boolean enabled = acceptEnabled && executorEnabled;
        return new CapabilityResponse(enabled, enabled ? "ENABLED" : "FEATURE_DISABLED");
    }

    public record CapabilityResponse(boolean allowed, String reason) { }
}
