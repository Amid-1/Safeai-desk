package ru.safeai.gateway.usage.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Physical-call evidence, NOT a total invoice or a reconciliation of message
 * revenue. UNKNOWN costs remain unknown, never zero-valued estimates.
 * All counts are per physical attempt, not per saved chat message.
 */
public record UsagePhysicalSpendResponse(
        Instant from,
        Instant to,
        UUID organizationId,
        String aggregationBasis,
        String currency,
        long physicalAttempts,
        long startedAttempts,
        long ambiguousAttempts,
        long knownExecutedAttempts,
        long knownNotExecutedAttempts,
        long knownRejectedAttempts,
        long costUnverifiableAttempts,
        long usageUnverifiableAttempts,
        long knownInputTokens,
        long knownOutputTokens,
        BigDecimal knownCostUsd,
        boolean costComplete,
        boolean usageComplete
) {
    public UsagePhysicalSpendResponse {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from должен быть раньше to");
        }
        if (!"PHYSICAL_ATTEMPT_STARTED_AT".equals(aggregationBasis)) {
            throw new IllegalArgumentException("Некорректная база физической агрегации");
        }
        if (!"USD".equals(currency)) {
            throw new IllegalArgumentException("Physical spend поддерживает только USD");
        }
        long[] counts = {physicalAttempts, startedAttempts, ambiguousAttempts,
                knownExecutedAttempts, knownNotExecutedAttempts, knownRejectedAttempts,
                costUnverifiableAttempts, usageUnverifiableAttempts,
                knownInputTokens, knownOutputTokens};
        for (long count : counts) {
            if (count < 0) {
                throw new IllegalArgumentException("Physical spend counters не могут быть отрицательными");
            }
        }
        Objects.requireNonNull(knownCostUsd, "knownCostUsd");
        if (knownCostUsd.signum() < 0) {
            throw new IllegalArgumentException("knownCostUsd не может быть отрицательным");
        }
        if (startedAttempts > physicalAttempts || ambiguousAttempts > physicalAttempts
                || costUnverifiableAttempts > physicalAttempts
                || usageUnverifiableAttempts > physicalAttempts) {
            throw new IllegalArgumentException("Физические счётчики выходят за границы общего числа попыток");
        }
        long classifiedAttempts = Math.addExact(
                Math.addExact(startedAttempts, ambiguousAttempts),
                Math.addExact(Math.addExact(knownExecutedAttempts, knownNotExecutedAttempts),
                        knownRejectedAttempts));
        if (classifiedAttempts != physicalAttempts) {
            throw new IllegalArgumentException("Physical attempt outcome counts must equal total");
        }
        long definitelyUnverifiable = Math.addExact(startedAttempts, ambiguousAttempts);
        if (costUnverifiableAttempts < definitelyUnverifiable
                || usageUnverifiableAttempts < definitelyUnverifiable) {
            throw new IllegalArgumentException("STARTED/AMBIGUOUS физические вызовы не могут быть достоверно оценены");
        }
        // The public flags are part of the wire contract: never permit a caller
        // to declare incomplete physical evidence complete (or the reverse).
        if (costComplete != (costUnverifiableAttempts == 0L)) {
            throw new IllegalArgumentException(
                    "costComplete противоречит costUnverifiableAttempts");
        }
        if (usageComplete != (usageUnverifiableAttempts == 0L)) {
            throw new IllegalArgumentException(
                    "usageComplete противоречит usageUnverifiableAttempts");
        }
    }

    /**
     * Preferred construction path: compute completeness from the evidence.
     * The canonical record constructor remains available and rejects
     * inconsistent flags rather than silently accepting them.
     */
    public UsagePhysicalSpendResponse(
            Instant from, Instant to, UUID organizationId,
            String aggregationBasis, String currency,
            long physicalAttempts, long startedAttempts, long ambiguousAttempts,
            long knownExecutedAttempts, long knownNotExecutedAttempts,
            long knownRejectedAttempts, long costUnverifiableAttempts,
            long usageUnverifiableAttempts, long knownInputTokens,
            long knownOutputTokens, BigDecimal knownCostUsd
    ) {
        this(from, to, organizationId, aggregationBasis, currency,
                physicalAttempts, startedAttempts, ambiguousAttempts,
                knownExecutedAttempts, knownNotExecutedAttempts,
                knownRejectedAttempts, costUnverifiableAttempts,
                usageUnverifiableAttempts, knownInputTokens, knownOutputTokens,
                knownCostUsd, costUnverifiableAttempts == 0L,
                usageUnverifiableAttempts == 0L);
    }
}
