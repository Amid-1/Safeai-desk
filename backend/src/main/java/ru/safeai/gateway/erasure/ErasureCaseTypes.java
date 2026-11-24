// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ErasureCaseTypes.java
package ru.safeai.gateway.erasure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTOs shared by the R1a controller, services and repositories. */
public final class ErasureCaseTypes {

    private ErasureCaseTypes() { }

    public enum ResourceType {
        CHAT,
        USER_ACCOUNT,
        KNOWLEDGE_DOCUMENT,
        KNOWLEDGE_BASE,
        ORGANIZATION
    }

    public enum Operation {
        REDACT_CONTENT,
        DELETE_ACCOUNT,
        DELETE_OBJECT,
        OFFBOARD_TENANT
    }

    public record Target(
            ResourceType resourceType,
            UUID resourceId,
            Operation operation,
            String scope
    ) { }

    // POST /api/erasure/previews accepts Target directly; no unused PreviewCreate wrapper.
    public record Preview(
            UUID previewId,
            String previewDigest,
            long expectedResourceVersion,
            Instant expiresAt,
            boolean executionAvailable
    ) { }

    public record DraftCreate(
            Target target,
            UUID previewId,
            String previewDigest,
            long expectedResourceVersion,
            String reasonCode
    ) { }

    public record CaseView(
            UUID requestId,
            ResourceType resourceType,
            UUID resourceId,
            Operation operation,
            String scope,
            String state,
            long version,
            Instant createdAt,
            boolean executionStarted,
            boolean completionProven
    ) { }

    public record CasePage(List<CaseView> items) { }
}
