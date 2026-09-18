package ru.safeai.gateway.model.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable model-routing governance evidence.
 *
 * <p>Integrity versions 1 and 2 remain readable for historical V45/V46
 * evidence. New decisions use version 3 with explicit input-accounting
 * provenance.</p>
 */
public record ModelRouteDecision(
        UUID id,
        UUID organizationId,
        UUID userId,
        UUID chatId,
        UUID chatTurnId,
        UUID clientRequestId,
        String requestContentHash,
        String requestedModelKey,
        UUID selectedCatalogEntryId,
        Integer selectedCatalogVersion,
        String selectedModelKey,
        String selectedProvider,
        String selectedProviderModelId,
        UUID policyId,
        Integer policyVersion,
        Set<ModelCapability> requiredCapabilities,
        String inputAccountingVersion,
        Long additionalInputUnitUpperBound,
        Long estimatedInputTokens,
        Long estimatedOutputTokens,
        BigDecimal estimatedMaxCostUsd,
        BigDecimal monthlyBudgetUsd,
        BigDecimal monthlySpentUsd,
        BigDecimal monthlyProjectedUsd,
        boolean monthlyCostKnown,
        BudgetEnforcement budgetEnforcement,
        boolean budgetExceeded,
        boolean pricingComplete,
        ModelRouteOutcome outcome,
        ModelRouteReason reason,
        short decisionIntegrityVersion,
        String decisionSha256,
        Instant createdAt
) {

    public ModelRouteDecision {
        if (requiredCapabilities == null
                || requiredCapabilities.isEmpty()) {
            requiredCapabilities = Set.of();
        } else {
            EnumSet<ModelCapability> copy =
                    EnumSet.noneOf(ModelCapability.class);

            copy.addAll(requiredCapabilities);

            requiredCapabilities =
                    Collections.unmodifiableSet(copy);
        }

        if (decisionIntegrityVersion < 1
                || decisionIntegrityVersion > 3) {
            throw new IllegalArgumentException(
                    "decisionIntegrityVersion должен быть 1, 2 или 3"
            );
        }

        if (decisionIntegrityVersion < 3) {
            if (inputAccountingVersion != null
                    || additionalInputUnitUpperBound != null) {
                throw new IllegalArgumentException(
                        "V1/V2 evidence не должно содержать V3 accounting provenance"
                );
            }
        } else {
            if (inputAccountingVersion == null
                    || inputAccountingVersion.isBlank()
                    || !inputAccountingVersion.equals(inputAccountingVersion.trim())
                    || inputAccountingVersion.length() > 64
                    || inputAccountingVersion.codePoints()
                    .anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "V3 inputAccountingVersion должен быть непустым безопасным идентификатором"
                );
            }
            if (additionalInputUnitUpperBound == null
                    || additionalInputUnitUpperBound < 0L) {
                throw new IllegalArgumentException(
                        "V3 additionalInputUnitUpperBound обязателен и не может быть отрицательным"
                );
            }
        }
    }

    /**
     * Monthly budget state exposed to audit/admin read models.
     *
     * <p>Budget enforcement mode can exist independently of an actual monthly
     * budget. Therefore absence of {@code monthlyBudgetUsd} means that monthly
     * budget evaluation was not applicable for this decision.</p>
     */
    public MonthlyCostState monthlyCostState() {
        if (monthlyBudgetUsd == null) {
            return MonthlyCostState.NOT_EVALUATED;
        }

        return monthlyCostKnown
                ? MonthlyCostState.KNOWN
                : MonthlyCostState.UNKNOWN;
    }
}
