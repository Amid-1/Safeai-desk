package ru.safeai.gateway.audit.dto;

import java.time.Instant;

/**
 * Only authenticated, bounded operational metadata; no evidence or storage credentials.
 */
public record AuditIntegrityStatus(State state, Long checkedEvents, Long violations,
                                   Long unanchoredEvents, Instant checkedAt, Instant freshUntil,
                                   ArchiveStatus archiveStatus) {
    public enum State {
        NOT_CONFIGURED(0), VERIFIED(1), VIOLATION(2), UNAVAILABLE(3), STALE(4), INVALID(5), PENDING(6);

        private final int metricCode;

        State(int metricCode) {
            this.metricCode = metricCode;
        }

        public int metricCode() {
            return metricCode;
        }
    }

    public enum ArchiveStatus {AVAILABLE, UNKNOWN}

    public static AuditIntegrityStatus unknown(State state, Instant checkedAt) {
        return new AuditIntegrityStatus(state, null, null, null, checkedAt, null, ArchiveStatus.UNKNOWN);
    }
}
