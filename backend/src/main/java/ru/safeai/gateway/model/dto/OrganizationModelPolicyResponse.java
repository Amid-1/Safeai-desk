package ru.safeai.gateway.model.dto;

import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Public policy DTO with exact decimal strings for all money fields. */
public record OrganizationModelPolicyResponse(
        boolean configured,
        UUID id,
        UUID organizationId,
        int version,
        boolean enabled,
        Set<String> allowModelKeys,
        Set<String> denyModelKeys,
        String defaultModelKey,
        Integer maxInputTokens,
        Integer maxOutputTokens,
        String maxRequestCostUsd,
        String monthlyBudgetUsd,
        BudgetEnforcement budgetEnforcement,
        boolean requireCompletePricing,
        boolean requireNoTraining,
        boolean requireZeroDataRetention,
        UUID createdByUserId,
        Instant createdAt
) {
    public static OrganizationModelPolicyResponse from(
            OrganizationModelPolicy policy
    ) {
        return new OrganizationModelPolicyResponse(
                true,
                policy.id(),
                policy.organizationId(),
                policy.version(),
                policy.enabled(),
                policy.allowModelKeys(),
                policy.denyModelKeys(),
                policy.defaultModelKey(),
                policy.maxInputTokens(),
                policy.maxOutputTokens(),
                decimal(policy.maxRequestCostUsd()),
                decimal(policy.monthlyBudgetUsd()),
                policy.budgetEnforcement(),
                policy.requireCompletePricing(),
                policy.requireNoTraining(),
                policy.requireZeroDataRetention(),
                policy.createdByUserId(),
                policy.createdAt()
        );
    }

    public static OrganizationModelPolicyResponse unconfigured(
            UUID organizationId
    ) {
        return new OrganizationModelPolicyResponse(
                false,
                null,
                organizationId,
                0,
                true,
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                null,
                null,
                BudgetEnforcement.SOFT,
                false,
                false,
                false,
                null,
                null
        );
    }

    private static String decimal(
            BigDecimal value
    ) {
        return value == null
                ? null
                : value.toPlainString();
    }
}
