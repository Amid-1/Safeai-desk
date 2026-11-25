package ru.safeai.gateway.erasure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only review projection. Approval never grants execution in R1b. */
public final class ErasureReviewTypes {
    private ErasureReviewTypes() { }

    public record ReviewView(
            UUID requestId,
            UUID requesterUserId,
            String resourceType,
            UUID resourceId,
            String operation,
            String scope,
            String baseState,
            String reviewState,
            long version,
            boolean holdActive,
            String reasonCode,
            Instant createdAt,
            Instant updatedAt,
            boolean executionAvailable,
            boolean completionProven
    ) { }

    public record ReviewPage(List<ReviewView> items) { }
    public record ReviewAccess(boolean reviewer, boolean legal) { }
    public record ReviewSummary(long total, long pending, long held, long needsInformation) { }
    public record DecisionBody(String decision, String reasonCode) { }
    public record HoldBody(String reasonCode) { }
    public record ReviewEvent(long version, String eventCode, String reasonCode, Instant createdAt) { }
    public record ReviewHistory(List<ReviewEvent> items) { }
}
