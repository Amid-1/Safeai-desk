package ru.safeai.gateway.model.domain;

/**
 * Precise monthly budget/commitment semantic used by V64 read models.
 *
 * <p>Unknown liability is never interpreted as zero. A lower-bound overrun is
 * represented independently from exactness.</p>
 */
public enum MonthlyCommitmentState {
    NOT_EVALUATED,
    KNOWN_WITHIN,
    KNOWN_EXCEEDED,
    UNKNOWN_WITHIN_LOWER_BOUND,
    UNKNOWN_EXCEEDED_LOWER_BOUND,
    UNVERIFIABLE
}
