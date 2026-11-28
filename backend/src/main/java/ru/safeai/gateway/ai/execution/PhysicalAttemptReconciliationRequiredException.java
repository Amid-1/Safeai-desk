package ru.safeai.gateway.ai.execution;

import java.util.Objects;
import java.util.UUID;

/**
 * Physical dispatch or terminal evidence is unverified.
 */
public final class PhysicalAttemptReconciliationRequiredException
        extends IllegalStateException {

    private final UUID providerAttemptId;

    public PhysicalAttemptReconciliationRequiredException(
            UUID providerAttemptId,
            RuntimeException cause
    ) {
        super(
                "Physical attempt evidence requires reconciliation: attemptId="
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
