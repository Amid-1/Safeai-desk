package ru.safeai.gateway.model.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Executable V45 route. The provider/model must equal the active runtime. */
public record ModelRouteResult(
        UUID decisionId,
        UUID catalogEntryId,
        Integer catalogVersion,
        UUID policyId,
        Integer policyVersion,
        String modelKey,
        String provider,
        String providerModelId,
        long estimatedInputTokens,
        long estimatedOutputTokens,
        BigDecimal estimatedMaxCostUsd,
        boolean pricingComplete,
        boolean budgetExceeded,
        ModelRouteReason reason
) {
}
