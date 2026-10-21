// frontend/src/features/usage/api/usage/usageParsers.ts
// ============================================================
// frontend/src/features/usage/api/usage/usageParsers.ts
// ============================================================

import { contractError, expectRecord, expectString, expectUuid } from '../../../../shared/api/runtime'
import type { UsageAmounts, UsageCoverage, UsageSummary, UsageUserSummary, UsageModelSummary, UsageDailySummary } from './usageTypes'
import { deriveUsageComplete, derivePricingComplete, validateCoverage, assertSum, addCounts, count, optionalCount, nonNegativeDecimal, optionalBoolean, optionalString, isRecord } from './usageValidation'

export function parseUsageSummary(
    value: unknown,
    field = 'usageSummary',
): UsageSummary {
    const record = expectRecord(value, field)

    return {
        userId: expectUuid(
            record.userId,
            `${field}.userId`,
        ),
        userEmail: expectString(
            record.currentUserEmail
                ?? record.userEmail,
            `${field}.userEmail`,
            { maxLength: 320 },
        ),
        model: expectString(
            record.model,
            `${field}.model`,
            { maxLength: 100 },
        ),
        ...parseUsageAmounts(record, field),
    }
}

export function parseUsageUserSummary(
    value: unknown,
    field = 'usageUserSummary',
): UsageUserSummary {
    const record = expectRecord(value, field)

    return {
        userId: expectUuid(
            record.userId,
            `${field}.userId`,
        ),
        userEmail: expectString(
            record.currentUserEmail
                ?? record.userEmail,
            `${field}.userEmail`,
            { maxLength: 320 },
        ),
        ...parseUsageAmounts(record, field),
    }
}

export function parseUsageModelSummary(
    value: unknown,
    field = 'usageModelSummary',
): UsageModelSummary {
    const record = expectRecord(value, field)

    return {
        model: expectString(
            record.model,
            `${field}.model`,
            { maxLength: 100 },
        ),
        ...parseUsageAmounts(record, field),
    }
}

export function parseUsageDailySummary(
    value: unknown,
    field = 'usageDailySummary',
): UsageDailySummary {
    const record = expectRecord(value, field)
    const usageDate = expectString(
        record.usageDate,
        `${field}.usageDate`,
        { maxLength: 10 },
    )

    if (!/^\d{4}-\d{2}-\d{2}$/.test(usageDate)) {
        throw contractError(
            `${field}.usageDate должен быть ISO LocalDate`,
        )
    }

    const aggregationZone =
        typeof record.aggregationZone === 'string'
            ? record.aggregationZone
            : 'UTC'

    if (aggregationZone !== 'UTC') {
        throw contractError(
            `${field}.aggregationZone должен быть UTC`,
        )
    }

    return {
        usageDate,
        aggregationZone,
        ...parseUsageAmounts(record, field),
    }
}

function parseUsageAmounts(
    record: Record<string, unknown>,
    field: string,
): UsageAmounts {
    // Актуальный контракт: вложенные структуры учёта.
    // { responses: {...}, usage: {...}, cost: {...} }.
    if (
        'usage' in record
        || 'cost' in record
        || 'responses' in record
    ) {
        return parseNestedUsageAmounts(
            record,
            field,
        )
    }

    // Совместимость со старым плоским DTO на время поэтапного развёртывания.
    return parseLegacyUsageAmounts(
        record,
        field,
    )
}

function parseNestedUsageAmounts(
    root: Record<string, unknown>,
    field: string,
): UsageAmounts {
    const usage = expectRecord(
        root.usage,
        `${field}.usage`,
    )
    const cost = expectRecord(
        root.cost,
        `${field}.cost`,
    )
    const responses = isRecord(root.responses)
        ? root.responses
        : {}

    const inputTokens = count(
        usage.confirmedInputTokens
            ?? usage.inputTokens,
        `${field}.usage.confirmedInputTokens`,
    )
    const outputTokens = count(
        usage.confirmedOutputTokens
            ?? usage.outputTokens,
        `${field}.usage.confirmedOutputTokens`,
    )
    const totalTokens = optionalCount(
        usage.confirmedTotalTokens
            ?? usage.totalTokens,
        `${field}.usage.confirmedTotalTokens`,
    ) ?? addCounts(inputTokens, outputTokens)

    assertSum(
        inputTokens,
        outputTokens,
        totalTokens,
        `${field}.usage.confirmedTotalTokens`,
    )

    const partialInputTokens = optionalCount(
        usage.partialKnownInputTokens
            ?? usage.partialInputTokens,
        `${field}.usage.partialKnownInputTokens`,
    ) ?? '0'
    const partialOutputTokens = optionalCount(
        usage.partialKnownOutputTokens
            ?? usage.partialOutputTokens,
        `${field}.usage.partialKnownOutputTokens`,
    ) ?? '0'
    const partialTotalTokens = optionalCount(
        usage.partialKnownTotalTokens
            ?? usage.partialTotalTokens,
        `${field}.usage.partialKnownTotalTokens`,
    ) ?? addCounts(
        partialInputTokens,
        partialOutputTokens,
    )

    assertSum(
        partialInputTokens,
        partialOutputTokens,
        partialTotalTokens,
        `${field}.usage.partialKnownTotalTokens`,
    )

    const costUsd = nonNegativeDecimal(
        cost.knownCostUsd
            ?? cost.costUsd
            ?? null,
        `${field}.cost.knownCostUsd`,
    )

    const currency = optionalString(
        cost.currency,
    ) ?? 'USD'

    const coverage: UsageCoverage = {
        assistantMessages: optionalCount(
            responses.assistantMessages
                ?? responses.assistantMessageCount,
            `${field}.responses.assistantMessages`,
        ),
        completedResponses: optionalCount(
            responses.completedResponses,
            `${field}.responses.completedResponses`,
        ),
        refusedResponses: optionalCount(
            responses.refusedResponses,
            `${field}.responses.refusedResponses`,
        ),
        incompleteResponses: optionalCount(
            responses.incompleteResponses,
            `${field}.responses.incompleteResponses`,
        ),
        failedMessages: optionalCount(
            responses.failedMessages,
            `${field}.responses.failedMessages`,
        ),
        unclassifiedResponses: optionalCount(
            responses.unclassifiedMessages
                ?? responses.unclassifiedResponses,
            `${field}.responses.unclassifiedMessages`,
        ),

        availableUsageMessages: optionalCount(
            usage.availableUsageMessages,
            `${field}.usage.availableUsageMessages`,
        ),
        partialUsageMessages: optionalCount(
            usage.partialUsageMessages,
            `${field}.usage.partialUsageMessages`,
        ),
        missingUsageMessages: optionalCount(
            usage.missingUsageMessages,
            `${field}.usage.missingUsageMessages`,
        ),
        usageNotApplicableMessages: optionalCount(
            usage.usageNotApplicableMessages,
            `${field}.usage.usageNotApplicableMessages`,
        ),

        pricedMessages: optionalCount(
            cost.pricedMessages,
            `${field}.cost.pricedMessages`,
        ),
        freeMessages: optionalCount(
            cost.freeMessages,
            `${field}.cost.freeMessages`,
        ),
        unpricedMessages: optionalCount(
            cost.unpricedMessages,
            `${field}.cost.unpricedMessages`,
        ),
        pricingFailedMessages: optionalCount(
            cost.pricingFailedMessages,
            `${field}.cost.pricingFailedMessages`,
        ),
        pricingNotApplicableMessages: optionalCount(
            cost.pricingNotApplicableMessages,
            `${field}.cost.pricingNotApplicableMessages`,
        ),

        usageComplete: optionalBoolean(
            usage.usageComplete,
            `${field}.usage.usageComplete`,
        ) ?? deriveUsageComplete(usage),
        pricingComplete: optionalBoolean(
            cost.pricingComplete,
            `${field}.cost.pricingComplete`,
        ) ?? derivePricingComplete(cost),
    }

    validateCoverage(
        coverage,
        costUsd,
        field,
    )

    return {
        inputTokens,
        outputTokens,
        totalTokens,
        partialInputTokens,
        partialOutputTokens,
        partialTotalTokens,
        costUsd,
        currency,
        coverage,
    }
}

function parseLegacyUsageAmounts(
    record: Record<string, unknown>,
    field: string,
): UsageAmounts {
    const inputTokens = count(
        record.inputTokens,
        `${field}.inputTokens`,
    )
    const outputTokens = count(
        record.outputTokens,
        `${field}.outputTokens`,
    )
    const totalTokens = count(
        record.totalTokens,
        `${field}.totalTokens`,
    )

    assertSum(
        inputTokens,
        outputTokens,
        totalTokens,
        `${field}.totalTokens`,
    )

    const costUsd = nonNegativeDecimal(
        record.costUsd ?? null,
        `${field}.costUsd`,
    )

    const coverage: UsageCoverage = {
        assistantMessages: optionalCount(
            record.assistantMessageCount
                ?? record.assistantMessages,
            `${field}.assistantMessageCount`,
        ),
        completedResponses: optionalCount(
            record.completedResponses,
            `${field}.completedResponses`,
        ),
        refusedResponses: optionalCount(
            record.refusedResponses,
            `${field}.refusedResponses`,
        ),
        incompleteResponses: optionalCount(
            record.incompleteResponses,
            `${field}.incompleteResponses`,
        ),
        failedMessages: optionalCount(
            record.failedMessages,
            `${field}.failedMessages`,
        ),
        unclassifiedResponses: optionalCount(
            record.unclassifiedMessages,
            `${field}.unclassifiedMessages`,
        ),

        availableUsageMessages: optionalCount(
            record.availableUsageMessageCount
                ?? record.availableUsageMessages,
            `${field}.availableUsageMessageCount`,
        ),
        partialUsageMessages: optionalCount(
            record.partialUsageMessageCount
                ?? record.partialUsageMessages,
            `${field}.partialUsageMessageCount`,
        ),
        missingUsageMessages: optionalCount(
            record.missingUsageMessageCount
                ?? record.missingUsageMessages,
            `${field}.missingUsageMessageCount`,
        ),
        usageNotApplicableMessages: optionalCount(
            record.usageNotApplicableMessageCount
                ?? record.usageNotApplicableMessages,
            `${field}.usageNotApplicableMessageCount`,
        ),

        pricedMessages: optionalCount(
            record.pricedMessageCount
                ?? record.pricedMessages,
            `${field}.pricedMessageCount`,
        ),
        freeMessages: optionalCount(
            record.freeMessageCount
                ?? record.freeMessages,
            `${field}.freeMessageCount`,
        ),
        unpricedMessages: optionalCount(
            record.unpricedMessageCount
                ?? record.unpricedMessages,
            `${field}.unpricedMessageCount`,
        ),
        pricingFailedMessages: optionalCount(
            record.pricingFailedMessageCount
                ?? record.pricingFailedMessages,
            `${field}.pricingFailedMessageCount`,
        ),
        pricingNotApplicableMessages: optionalCount(
            record.pricingNotApplicableMessageCount
                ?? record.pricingNotApplicableMessages,
            `${field}.pricingNotApplicableMessageCount`,
        ),

        usageComplete: optionalBoolean(
            record.usageComplete,
            `${field}.usageComplete`,
        ) ?? deriveUsageComplete(record),
        pricingComplete: optionalBoolean(
            record.pricingComplete,
            `${field}.pricingComplete`,
        ) ?? derivePricingComplete(record),
    }

    validateCoverage(coverage, costUsd, field)

    return {
        inputTokens,
        outputTokens,
        totalTokens,
        partialInputTokens: optionalCount(
            record.partialKnownInputTokens,
            `${field}.partialKnownInputTokens`,
        ) ?? '0',
        partialOutputTokens: optionalCount(
            record.partialKnownOutputTokens,
            `${field}.partialKnownOutputTokens`,
        ) ?? '0',
        partialTotalTokens: optionalCount(
            record.partialKnownTotalTokens,
            `${field}.partialKnownTotalTokens`,
        ) ?? '0',
        costUsd,
        currency: optionalString(
            record.currency,
        ) ?? 'USD',
        coverage,
    }
}
