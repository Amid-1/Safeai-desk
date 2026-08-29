package ru.safeai.gateway.model.service;

import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.ModelRouteResult;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.exception.ModelRouteDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

final class ModelRouteDecisionFactory {

    ModelRouteDecision buildDecision(
            ModelRouteRequest request,
            OrganizationModelPolicy policy,
            DecisionDraft draft,
            UUID chatTurnId,
            Instant now
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );
        Objects.requireNonNull(
                draft,
                "draft не должен быть null"
        );
        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        ModelRoutingCostPolicy.BudgetSnapshot budget =
                Objects.requireNonNull(
                        draft.budget(),
                        "draft.budget не должен быть null"
                );

        BigDecimal estimatedCost = normalizeMoney(
                draft.estimatedCost(),
                "estimatedMaxCostUsd"
        );
        BigDecimal monthlyBudgetUsd = normalizeMoney(
                budget.monthlyBudgetUsd(),
                "monthlyBudgetUsd"
        );
        BigDecimal monthlySpentUsd = normalizeMoney(
                budget.monthlySpentUsd(),
                "monthlySpentUsd"
        );
        BigDecimal monthlyProjectedUsd = normalizeMoney(
                budget.monthlyProjectedUsd(),
                "monthlyProjectedUsd"
        );
        Instant createdAt = ModelRouteDecisionIntegrity
                .normalizeDatabaseTimestamp(now);

        ModelRouteDecision unsealed = new ModelRouteDecision(
                UUID.randomUUID(),
                request.organizationId(),
                request.userId(),
                request.chatId(),
                chatTurnId,
                request.clientRequestId(),
                request.requestContentHash(),
                ModelRoutingSelectionPolicy.normalizeNullableKey(
                        request.requestedModelKey()
                ),
                draft.entry() == null ? null : draft.entry().id(),
                draft.entry() == null ? null : draft.entry().version(),
                draft.selectedModelKey(),
                draft.provider(),
                draft.providerModelId(),
                policy == null ? null : policy.id(),
                policy == null ? null : policy.version(),
                request.requiredCapabilities(),
                draft.estimatedInputTokens(),
                draft.estimatedOutputTokens(),
                estimatedCost,
                monthlyBudgetUsd,
                monthlySpentUsd,
                monthlyProjectedUsd,
                budget.monthlyCostKnown(),
                policy == null ? null : policy.budgetEnforcement(),
                budget.exceeded(),
                draft.pricingComplete(),
                draft.outcome(),
                draft.reason(),
                "",
                createdAt
        );

        return ModelRouteDecisionIntegrity.seal(unsealed);
    }

    ModelRouteResult replayDecision(
            ModelRouteDecision decision,
            ModelRouteRequest request
    ) {
        Objects.requireNonNull(
                decision,
                "decision не должен быть null"
        );
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );
        ModelRouteDecisionIntegrity.requireValid(decision);

        String normalizedRequestedModelKey =
                ModelRoutingSelectionPolicy.normalizeNullableKey(
                        request.requestedModelKey()
                );

        boolean sameRequestIdentity =
                decision.organizationId().equals(
                        request.organizationId()
                )
                        && decision.userId().equals(
                        request.userId()
                )
                        && decision.chatId().equals(
                        request.chatId()
                )
                        && decision.clientRequestId().equals(
                        request.clientRequestId()
                )
                        && decision.requestContentHash().equals(
                        request.requestContentHash()
                )
                        && Objects.equals(
                        decision.requestedModelKey(),
                        normalizedRequestedModelKey
                )
                        && decision.requiredCapabilities().equals(
                        request.requiredCapabilities()
                );

        if (!sameRequestIdentity) {
            throw new ConflictException(
                    "clientRequestId уже использован для другого "
                            + "model route request"
            );
        }

        if (decision.outcome() == ModelRouteOutcome.DENIED) {
            throw new ModelRouteDeniedException(
                    decision.id(),
                    decision.reason(),
                    publicDenialMessage(
                            decision.reason(),
                            decision.id()
                    )
            );
        }

        if (!Objects.equals(
                decision.chatTurnId(),
                request.plannedTurnId()
        )) {
            throw new IllegalStateException(
                    "ALLOWED model route decision имеет другой "
                            + "planned ChatTurn identity"
            );
        }

        return toResult(decision);
    }

    ModelRouteResult toResult(
            ModelRouteDecision decision
    ) {
        Objects.requireNonNull(
                decision,
                "decision не должен быть null"
        );

        if (decision.outcome() != ModelRouteOutcome.ALLOWED) {
            throw new IllegalArgumentException(
                    "ModelRouteResult можно построить только из ALLOWED decision"
            );
        }

        return new ModelRouteResult(
                decision.id(),
                decision.selectedCatalogEntryId(),
                decision.selectedCatalogVersion(),
                decision.policyId(),
                decision.policyVersion(),
                decision.selectedModelKey(),
                decision.selectedProvider(),
                decision.selectedProviderModelId(),
                Objects.requireNonNull(decision.estimatedInputTokens()),
                Objects.requireNonNull(decision.estimatedOutputTokens()),
                decision.estimatedMaxCostUsd(),
                decision.pricingComplete(),
                decision.budgetExceeded(),
                decision.reason()
        );
    }

    private static BigDecimal normalizeMoney(
            BigDecimal value,
            String field
    ) {
        return ModelControlPlaneNumericValidation
                .normalizeNonNegativeNumeric30Scale12(
                        value,
                        field
                );
    }

    static String publicDenialMessage(
            ModelRouteReason reason,
            UUID decisionId
    ) {
        return "AI model route отклонён политикой SafeAI: "
                + reason
                + ". decisionId="
                + decisionId;
    }

    record DecisionDraft(
            ModelCatalogEntry entry,
            String selectedModelKey,
            String provider,
            String providerModelId,
            Long estimatedInputTokens,
            Long estimatedOutputTokens,
            BigDecimal estimatedCost,
            boolean pricingComplete,
            ModelRoutingCostPolicy.BudgetSnapshot budget,
            ModelRouteOutcome outcome,
            ModelRouteReason reason
    ) {
    }
}
