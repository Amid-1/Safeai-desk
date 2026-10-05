package ru.safeai.gateway.model.domain;

/**
 * Backward-compatible high-level monthly cost exactness state.
 *
 * <p>Use {@link MonthlyCommitmentState} when callers need to distinguish
 * actual/reserved commitments, a proven lower-bound overrun, or an
 * unverifiable projection.</p>
 */
public enum MonthlyCostState {
    NOT_EVALUATED,
    KNOWN,
    UNKNOWN
}
