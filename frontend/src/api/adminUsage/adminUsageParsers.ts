// ============================================================
// frontend/src/api/adminUsage/adminUsageParsers.ts
// ============================================================

import { contractError, expectRecord, expectString, expectUuid } from '../runtime'
import type { UsageSummary, UsageUserSummary, UsageModelSummary, UsageDailySummary, UsageCoverage } from './adminUsageTypes'
import { parseUsageCoverage } from './adminUsageCoverage'
import { parseNonNegativeIntegerString, parseNonNegativeDecimalString, parseDateOnly } from './adminUsageValidation'

export function parseUsageSummary(
    value: unknown,
    field = 'usageSummary',
): UsageSummary {
    const record = expectRecord(
        value,
        field,
    )

    return {
        userId: expectUuid(
            record.userId,
            `${field}.userId`,
        ),
        userEmail: expectString(
            record.userEmail,
            `${field}.userEmail`,
            {
                maxLength: 320,
            },
        ),
        model: expectString(
            record.model,
            `${field}.model`,
            {
                maxLength: 100,
            },
        ),
        ...parseUsageAmounts(
            record,
            field,
        ),
    }
}

export function parseUsageUserSummary(
    value: unknown,
    field = 'usageUserSummary',
): UsageUserSummary {
    const record = expectRecord(
        value,
        field,
    )

    return {
        userId: expectUuid(
            record.userId,
            `${field}.userId`,
        ),
        userEmail: expectString(
            record.userEmail,
            `${field}.userEmail`,
            {
                maxLength: 320,
            },
        ),
        ...parseUsageAmounts(
            record,
            field,
        ),
    }
}

export function parseUsageModelSummary(
    value: unknown,
    field = 'usageModelSummary',
): UsageModelSummary {
    const record = expectRecord(
        value,
        field,
    )

    return {
        model: expectString(
            record.model,
            `${field}.model`,
            {
                maxLength: 100,
            },
        ),
        ...parseUsageAmounts(
            record,
            field,
        ),
    }
}

export function parseUsageDailySummary(
    value: unknown,
    field = 'usageDailySummary',
): UsageDailySummary {
    const record = expectRecord(
        value,
        field,
    )

    return {
        usageDate:
            parseDateOnly(
                record.usageDate,
                `${field}.usageDate`,
            ),
        ...parseUsageAmounts(
            record,
            field,
        ),
    }
}

function parseUsageAmounts(
    record: Record<string, unknown>,
    field: string,
): {
    inputTokens: string
    outputTokens: string
    totalTokens: string
    costUsd: string | null
    coverage: UsageCoverage
} {
    const inputTokens =
        parseNonNegativeIntegerString(
            record.inputTokens,
            `${field}.inputTokens`,
        )

    const outputTokens =
        parseNonNegativeIntegerString(
            record.outputTokens,
            `${field}.outputTokens`,
        )

    const totalTokens =
        parseNonNegativeIntegerString(
            record.totalTokens,
            `${field}.totalTokens`,
        )

    const costUsd =
        parseNonNegativeDecimalString(
            record.costUsd ?? null,
            `${field}.costUsd`,
        )

    if (
        BigInt(inputTokens)
        + BigInt(outputTokens)
        !== BigInt(totalTokens)
    ) {
        throw contractError(
            `${field}.totalTokens не равен inputTokens + outputTokens`,
        )
    }

    const coverage =
        parseUsageCoverage(
            record,
            field,
        )

    if (
        coverage.pricingComplete === true
        && coverage.assistantMessages !== null
        && coverage.assistantMessages !== '0'
        && costUsd === null
    ) {
        throw contractError(
            `${field}.costUsd отсутствует при complete pricing coverage`,
        )
    }

    return {
        inputTokens,
        outputTokens,
        totalTokens,
        costUsd,
        coverage,
    }
}
