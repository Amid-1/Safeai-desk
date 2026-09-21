package ru.safeai.gateway.ai.execution;

import java.util.Objects;
import java.util.UUID;

/**
 * A physical provider response was observed,
 * but terminal evidence outcome is unverified.
 */
public final class PhysicalAttemptReconciliationRequiredException
        extends IllegalStateException {

    private final UUID providerAttemptId;

    public PhysicalAttemptReconciliationRequiredException(
            UUID providerAttemptId,
            RuntimeException cause
    ) {
        super(
                "Physical response observed; terminal evidence requires reconciliation: attemptId="
                        + Objects.requireNonNull(
                                providerAttemptId,
                                "providerAttemptId"
                        ),
                cause
        );

        this.providerAttemptId = providerAttemptId;
    }

    public UUID providerAttemptId() {
        return providerAttemptId;
    }
}
