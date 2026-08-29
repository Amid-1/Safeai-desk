package ru.safeai.gateway.model.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public record OrganizationModelPolicy(
        UUID id,
        UUID organizationId,
        int version,
        boolean enabled,
        Set<String> allowModelKeys,
        Set<String> denyModelKeys,
        String defaultModelKey,
        Integer maxInputTokens,
        Integer maxOutputTokens,
        BigDecimal maxRequestCostUsd,
        BigDecimal monthlyBudgetUsd,
        BudgetEnforcement budgetEnforcement,
        boolean requireCompletePricing,
        boolean requireNoTraining,
        boolean requireZeroDataRetention,
        UUID createdByUserId,
        Instant createdAt
) {
    public OrganizationModelPolicy {
        allowModelKeys = immutableSortedSet(allowModelKeys);
        denyModelKeys = immutableSortedSet(denyModelKeys);
    }

    private static Set<String> immutableSortedSet(
            Set<String> values
    ) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(
                new TreeSet<>(values)
        );
    }
}
