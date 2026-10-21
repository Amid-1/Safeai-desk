// frontend/src/entities/model/api/model/modelRouteParser.ts
// Назначение: проверка неизменяемых доказательств решения маршрутизации.

import type { ModelRouteDecision } from './modelTypes'
import { INPUT_ACCOUNTING_VERSIONS, MODEL_CAPABILITIES, BUDGET_ENFORCEMENTS, MONTHLY_COST_STATES, MODEL_ROUTE_OUTCOMES, MODEL_ROUTE_REASONS } from './modelEnums'
import { requireObject, optionalNonBlankString, requireBoolean, requireEnum, requireEnumArray, requireInteger, optionalPositiveInteger, optionalNonNegativeInteger, optionalDecimalString, optionalModelKey, requireUuid, optionalUuid, requireSha256, requireInstant, optionalEnum } from './modelWire'
import { validateRouteSemantics } from './modelSemantics'

export function parseModelRouteDecision(
    value: unknown,
    path = 'modelRouteDecision',
): ModelRouteDecision {
    const raw = requireObject(value, path)

    const outcome = requireEnum(
        raw.outcome,
        MODEL_ROUTE_OUTCOMES,
        `${path}.outcome`,
    )

    const reason = requireEnum(
        raw.reason,
        MODEL_ROUTE_REASONS,
        `${path}.reason`,
    )

    const integrity =
        requireInteger(
            raw.decisionIntegrityVersion,
            `${path}.decisionIntegrityVersion`,
        )

    if (integrity !== 1
        && integrity !== 2
        && integrity !== 3) {
        throw new Error(
            `${path}.decisionIntegrityVersion должен быть 1, 2 или 3`,
        )
    }

    const decision: ModelRouteDecision = {
        id: requireUuid(raw.id, `${path}.id`),
        organizationId: requireUuid(
            raw.organizationId,
            `${path}.organizationId`,
        ),
        userId: requireUuid(
            raw.userId,
            `${path}.userId`,
        ),
        chatId: requireUuid(
            raw.chatId,
            `${path}.chatId`,
        ),
        chatTurnId: optionalUuid(
            raw.chatTurnId,
            `${path}.chatTurnId`,
        ),
        clientRequestId: requireUuid(
            raw.clientRequestId,
            `${path}.clientRequestId`,
        ),
        requestContentHash: requireSha256(
            raw.requestContentHash,
            `${path}.requestContentHash`,
        ),
        requestedModelKey: optionalModelKey(
            raw.requestedModelKey,
            `${path}.requestedModelKey`,
        ),
        selectedCatalogEntryId: optionalUuid(
            raw.selectedCatalogEntryId,
            `${path}.selectedCatalogEntryId`,
        ),
        selectedCatalogVersion: optionalPositiveInteger(
            raw.selectedCatalogVersion,
            `${path}.selectedCatalogVersion`,
        ),
        selectedModelKey: optionalModelKey(
            raw.selectedModelKey,
            `${path}.selectedModelKey`,
        ),
        selectedProvider: optionalNonBlankString(
            raw.selectedProvider,
            `${path}.selectedProvider`,
        ),
        selectedProviderModelId: optionalNonBlankString(
            raw.selectedProviderModelId,
            `${path}.selectedProviderModelId`,
        ),
        policyId: optionalUuid(
            raw.policyId,
            `${path}.policyId`,
        ),
        policyVersion: optionalPositiveInteger(
            raw.policyVersion,
            `${path}.policyVersion`,
        ),
        requiredCapabilities: requireEnumArray(
            raw.requiredCapabilities,
            MODEL_CAPABILITIES,
            `${path}.requiredCapabilities`,
        ),
        inputAccountingVersion: optionalEnum(
            raw.inputAccountingVersion,
            INPUT_ACCOUNTING_VERSIONS,
            `${path}.inputAccountingVersion`,
        ),
        additionalInputUnitUpperBound: optionalNonNegativeInteger(
            raw.additionalInputUnitUpperBound,
            `${path}.additionalInputUnitUpperBound`,
        ),
        estimatedInputTokens: optionalNonNegativeInteger(
            raw.estimatedInputTokens,
            `${path}.estimatedInputTokens`,
        ),
        estimatedOutputTokens: optionalNonNegativeInteger(
            raw.estimatedOutputTokens,
            `${path}.estimatedOutputTokens`,
        ),
        estimatedMaxCostUsd:
            optionalDecimalString(
                raw.estimatedMaxCostUsd,
                `${path}.estimatedMaxCostUsd`,
            ),
        monthlyBudgetUsd:
            optionalDecimalString(
                raw.monthlyBudgetUsd,
                `${path}.monthlyBudgetUsd`,
            ),
        monthlySpentUsd:
            optionalDecimalString(
                raw.monthlySpentUsd,
                `${path}.monthlySpentUsd`,
            ),
        monthlyProjectedUsd:
            optionalDecimalString(
                raw.monthlyProjectedUsd,
                `${path}.monthlyProjectedUsd`,
            ),
        monthlyCostKnown: requireBoolean(
            raw.monthlyCostKnown,
            `${path}.monthlyCostKnown`,
        ),
        monthlyCostState: requireEnum(
            raw.monthlyCostState,
            MONTHLY_COST_STATES,
            `${path}.monthlyCostState`,
        ),
        budgetEnforcement:
            raw.budgetEnforcement === null
            || raw.budgetEnforcement === undefined
                ? null
                : requireEnum(
                    raw.budgetEnforcement,
                    BUDGET_ENFORCEMENTS,
                    `${path}.budgetEnforcement`,
                ),
        budgetExceeded: requireBoolean(
            raw.budgetExceeded,
            `${path}.budgetExceeded`,
        ),
        pricingComplete: requireBoolean(
            raw.pricingComplete,
            `${path}.pricingComplete`,
        ),
        outcome,
        reason,
        decisionIntegrityVersion: integrity,
        decisionSha256: requireSha256(
            raw.decisionSha256,
            `${path}.decisionSha256`,
        ),
        createdAt: requireInstant(
            raw.createdAt,
            `${path}.createdAt`,
        ),
    }

    validateRouteSemantics(
        decision,
        path,
    )

    return decision
}
