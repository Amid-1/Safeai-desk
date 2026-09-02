package ru.safeai.gateway.model.domain;

/**
 * Human/API semantic for the V45 monthly budget snapshot.
 *
 * <p>The persisted V45 boolean remains backward compatible. This state is
 * derived from snapshot presence and therefore does not rewrite immutable
 * historical evidence.</p>
 */
public enum MonthlyCostState {
    NOT_EVALUATED,
    KNOWN,
    UNKNOWN
}
