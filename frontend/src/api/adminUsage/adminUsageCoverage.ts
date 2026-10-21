// ============================================================
// frontend/src/api/adminUsage/adminUsageCoverage.ts
// ============================================================

import { contractError } from '../runtime'
import type { UsageCoverage } from './adminUsageTypes'
import { parseOptionalBoolean, parseOptionalCount } from './adminUsageValidation'

export function parseUsageCoverage(
    record: Record<string, unknown>,
    field: string,
): UsageCoverage {
    const assistantMessages =
        parseOptionalCount(
            record.assistantMessageCount
                ?? record.assistantMessages,
            `${field}.assistantMessageCount`,
        )

    const availableUsageMessages =
        parseOptionalCount(
            record.availableUsageMessageCount
                ?? record.availableUsageMessages,
            `${field}.availableUsageMessageCount`,
        )

    const partialUsageMessages =
        parseOptionalCount(
            record.partialUsageMessageCount
                ?? record.partialUsageMessages,
            `${field}.partialUsageMessageCount`,
        )

    const missingUsageMessages =
        parseOptionalCount(
            record.missingUsageMessageCount
                ?? record.missingUsageMessages,
            `${field}.missingUsageMessageCount`,
        )

    const usageNotApplicableMessages =
        parseOptionalCount(
            record.usageNotApplicableMessageCount
                ?? record.usageNotApplicableMessages,
            `${field}.usageNotApplicableMessageCount`,
        )

    const pricedMessages =
        parseOptionalCount(
            record.pricedMessageCount
                ?? record.pricedMessages,
            `${field}.pricedMessageCount`,
        )

    const freeMessages =
        parseOptionalCount(
            record.freeMessageCount
                ?? record.freeMessages,
            `${field}.freeMessageCount`,
        )

    const unpricedMessages =
        parseOptionalCount(
            record.unpricedMessageCount
                ?? record.unpricedMessages,
            `${field}.unpricedMessageCount`,
        )

    const pricingFailedMessages =
        parseOptionalCount(
            record.pricingFailedMessageCount
                ?? record.pricingFailedMessages,
            `${field}.pricingFailedMessageCount`,
        )

    const pricingNotApplicableMessages =
        parseOptionalCount(
            record.pricingNotApplicableMessageCount
                ?? record.pricingNotApplicableMessages,
            `${field}.pricingNotApplicableMessageCount`,
        )

    const ambiguousProviderOperations =
        parseOptionalCount(
            record.ambiguousProviderOperationCount
                ?? record.ambiguousProviderOperations,
            `${field}.ambiguousProviderOperationCount`,
        )

    const explicitUsageComplete =
        parseOptionalBoolean(
            record.usageComplete,
            `${field}.usageComplete`,
        )

    const explicitPricingComplete =
        parseOptionalBoolean(
            record.pricingComplete,
            `${field}.pricingComplete`,
        )

    assertCoverageTotal(
        assistantMessages,
        [
            availableUsageMessages,
            partialUsageMessages,
            missingUsageMessages,
            usageNotApplicableMessages,
        ],
        `${field}.usageCoverage`,
    )

    assertCoverageTotal(
        assistantMessages,
        [
            pricedMessages,
            freeMessages,
            unpricedMessages,
            pricingFailedMessages,
            pricingNotApplicableMessages,
        ],
        `${field}.pricingCoverage`,
    )

    const inferredUsageComplete =
        inferUsageComplete({
            assistantMessages,
            availableUsageMessages,
            partialUsageMessages,
            missingUsageMessages,
            usageNotApplicableMessages,
        })

    const inferredPricingComplete =
        inferPricingComplete({
            assistantMessages,
            pricedMessages,
            freeMessages,
            unpricedMessages,
            pricingFailedMessages,
            pricingNotApplicableMessages,
        })

    if (
        explicitUsageComplete !== null
        && inferredUsageComplete !== null
        && explicitUsageComplete
            !== inferredUsageComplete
    ) {
        throw contractError(
            `${field}.usageComplete противоречит coverage counters`,
        )
    }

    if (
        explicitPricingComplete !== null
        && inferredPricingComplete !== null
        && explicitPricingComplete
            !== inferredPricingComplete
    ) {
        throw contractError(
            `${field}.pricingComplete противоречит coverage counters`,
        )
    }

    const usageComplete =
        explicitUsageComplete
        ?? inferredUsageComplete

    const pricingComplete =
        explicitPricingComplete
        ?? inferredPricingComplete

    return {
        assistantMessages,

        availableUsageMessages,
        partialUsageMessages,
        missingUsageMessages,
        usageNotApplicableMessages,

        pricedMessages,
        freeMessages,
        unpricedMessages,
        pricingFailedMessages,
        pricingNotApplicableMessages,

        ambiguousProviderOperations,

        usageComplete,
        pricingComplete,
    }
}

function assertCoverageTotal(
    assistantMessages: string | null,
    categories: (string | null)[],
    field: string,
): void {
    if (
        assistantMessages === null
        || categories.some(
            (value) => value === null,
        )
    ) {
        return
    }

    const total = categories.reduce(
        (sum, value) =>
            sum + BigInt(value ?? '0'),
        0n,
    )

    if (
        total !== BigInt(
            assistantMessages,
        )
    ) {
        throw contractError(
            `${field} не согласован с assistantMessageCount`,
        )
    }
}

function inferUsageComplete(
    coverage: Pick<
        UsageCoverage,
        | 'assistantMessages'
        | 'availableUsageMessages'
        | 'partialUsageMessages'
        | 'missingUsageMessages'
        | 'usageNotApplicableMessages'
    >,
): boolean | null {
    if (
        coverage.assistantMessages === null
        || coverage.availableUsageMessages === null
        || coverage.partialUsageMessages === null
        || coverage.missingUsageMessages === null
        || coverage.usageNotApplicableMessages === null
    ) {
        return null
    }

    return coverage.partialUsageMessages === '0'
        && coverage.missingUsageMessages === '0'
}

function inferPricingComplete(
    coverage: Pick<
        UsageCoverage,
        | 'assistantMessages'
        | 'pricedMessages'
        | 'freeMessages'
        | 'unpricedMessages'
        | 'pricingFailedMessages'
        | 'pricingNotApplicableMessages'
    >,
): boolean | null {
    if (
        coverage.assistantMessages === null
        || coverage.pricedMessages === null
        || coverage.freeMessages === null
        || coverage.unpricedMessages === null
        || coverage.pricingFailedMessages === null
        || coverage.pricingNotApplicableMessages === null
    ) {
        return null
    }

    return coverage.unpricedMessages === '0'
        && coverage.pricingFailedMessages === '0'
}
