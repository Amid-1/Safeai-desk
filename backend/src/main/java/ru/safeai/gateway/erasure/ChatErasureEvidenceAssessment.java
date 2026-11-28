// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ChatErasureEvidenceAssessment.java
package ru.safeai.gateway.erasure;

import java.time.Instant;
import java.util.UUID;

/** Observation of the supported V86 database fields only; no receipt or full-scope proof. */
public final class ChatErasureEvidenceAssessment {
    private ChatErasureEvidenceAssessment() { }
    public enum State { UNKNOWN, NOT_TERMINAL, INCONSISTENT, V86_REDACTION_OBSERVED }
    public record Observation(UUID operationId, UUID organizationId, UUID chatId, UUID ownerId,
                              String operationState, String chatState, Instant completedAt, Instant erasedAt,
                              Boolean archived, Boolean titleRedacted, Boolean leaseCleared,
                              Long messageRemainder, Long retrievalRemainder, Boolean terminalAuditPresent) { }
    public record Assessment(State state) {
        // Even a complete V86 observation does not prove external/backup deletion,
        // manifest coverage or a managed permit chain.
        public boolean completionProven() { return false; }
        public boolean executionAvailable() { return false; }
    }
    public static Assessment assess(Observation o) {
        if (o == null || o.operationId() == null || o.organizationId() == null || o.chatId() == null
                || o.ownerId() == null || o.operationState() == null || o.chatState() == null
                || o.archived() == null || o.titleRedacted() == null || o.leaseCleared() == null
                || o.messageRemainder() == null || o.retrievalRemainder() == null
                || o.terminalAuditPresent() == null || o.messageRemainder() < 0 || o.retrievalRemainder() < 0) {
            return new Assessment(State.UNKNOWN);
        }
        boolean opErased = "ERASED".equals(o.operationState());
        boolean chatErased = "ERASED".equals(o.chatState());
        if (!opErased && !chatErased) {
            boolean knownOp = switch (o.operationState()) {
                case "ERASING", "NEEDS_REVIEW", "BLOCKED_BY_RETENTION", "CANCELLED" -> true;
                default -> false;
            };
            boolean knownChat = "AVAILABLE".equals(o.chatState()) || "ERASING".equals(o.chatState());
            if (!knownOp || !knownChat) return new Assessment(State.UNKNOWN);
            if (o.completedAt() != null || o.erasedAt() != null) return new Assessment(State.INCONSISTENT);
            return new Assessment(State.NOT_TERMINAL);
        }
        if (!opErased || !chatErased || o.completedAt() == null || o.erasedAt() == null
                || o.completedAt().isBefore(o.erasedAt()) || !o.archived() || !o.titleRedacted()
                || !o.leaseCleared() || o.messageRemainder() != 0 || o.retrievalRemainder() != 0
                || !o.terminalAuditPresent()) return new Assessment(State.INCONSISTENT);
        return new Assessment(State.V86_REDACTION_OBSERVED);
    }
}
