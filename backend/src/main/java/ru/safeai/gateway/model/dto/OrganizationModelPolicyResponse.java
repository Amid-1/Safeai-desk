package ru.safeai.gateway.model.dto;

import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Public organization model-policy DTO.
 *
 * <p>Money values are exposed as exact decimal strings, so the browser never
 * has to represent governance money through IEEE-754 numbers.</p>
 *
 * <p>If no policy has been persisted yet, the synthetic read model is
 * deliberately disabled. Creating and enabling policy version 1 must be an
 * explicit administrator action.</p>
 */
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
                false,
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
