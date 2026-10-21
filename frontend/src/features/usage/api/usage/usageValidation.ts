// frontend/src/features/usage/api/usage/usageValidation.ts
// ============================================================
// frontend/src/features/usage/api/usage/usageValidation.ts
// ============================================================

import { contractError } from '../../../../shared/api/runtime'
import type { UsageCoverage } from './usageTypes'

export function deriveUsageComplete(
    record: Record<string, unknown>,
): boolean | null {
    const partial = optionalCount(
        record.partialUsageMessages
            ?? record.partialUsageMessageCount,
        'usage.partialUsageMessages',
    )
    const missing = optionalCount(
        record.missingUsageMessages
            ?? record.missingUsageMessageCount,
        'usage.missingUsageMessages',
    )

    if (partial === null || missing === null) {
        return null
    }

    return partial === '0' && missing === '0'
}

export function derivePricingComplete(
    record: Record<string, unknown>,
): boolean | null {
    const unpriced = optionalCount(
        record.unpricedMessages
            ?? record.unpricedMessageCount,
        'cost.unpricedMessages',
    )
    const failed = optionalCount(
        record.pricingFailedMessages
            ?? record.pricingFailedMessageCount,
        'cost.pricingFailedMessages',
    )

    if (unpriced === null || failed === null) {
        return null
    }

    return unpriced === '0' && failed === '0'
}

export function validateCoverage(
    coverage: UsageCoverage,
    costUsd: string | null,
    field: string,
): void {
    // Подтверждённая полнота не должна противоречить известным пропускам.
    if (
        coverage.usageComplete === true
        && (
            (coverage.partialUsageMessages !== null && coverage.partialUsageMessages !== '0')
            || (coverage.missingUsageMessages !== null && coverage.missingUsageMessages !== '0')
        )
    ) {
        throw contractError(`${field}: usageComplete противоречит coverage`)
    }
    if (
        coverage.pricingComplete === true
        && (
            (coverage.unpricedMessages !== null && coverage.unpricedMessages !== '0')
            || (coverage.pricingFailedMessages !== null && coverage.pricingFailedMessages !== '0')
        )
    ) {
        throw contractError(`${field}: pricingComplete противоречит coverage`)
    }
    if (
        coverage.pricingComplete === true
        && coverage.assistantMessages !== null
        && coverage.assistantMessages !== '0'
        && costUsd === null
    ) {
        throw contractError(
            `${field}: knownCostUsd отсутствует при complete pricing coverage`,
        )
    }
}

export function assertSum(
    left: string,
    right: string,
    total: string,
    field: string,
): void {
    if (
        BigInt(left) + BigInt(right)
        !== BigInt(total)
    ) {
        throw contractError(
            `${field} не равен сумме составляющих`,
        )
    }
}

export function addCounts(
    left: string,
    right: string,
): string {
    return (
        BigInt(left) + BigInt(right)
    ).toString()
}

export function count(
    value: unknown,
    field: string,
): string {
    const parsed = optionalCount(value, field)

    if (parsed === null) {
        throw contractError(
            `${field} не должен быть null`,
        )
    }

    return parsed
}

export function optionalCount(
    value: unknown,
    field: string,
): string | null {
    if (value === undefined || value === null) {
        return null
    }

    if (
        typeof value === 'number'
        && Number.isSafeInteger(value)
        && value >= 0
    ) {
        return String(value)
    }

    if (
        typeof value === 'string'
        && /^(0|[1-9]\d*)$/.test(value)
    ) {
        return value
    }

    throw contractError(
        `${field} должен быть неотрицательным целым числом`,
    )
}

export function nonNegativeDecimal(
    value: unknown,
    field: string,
): string | null {
    if (value === undefined || value === null) {
        return null
    }

    const normalized = typeof value === 'number'
        ? String(value)
        : typeof value === 'string'
            ? value
            : null

    if (
        normalized === null
        || !/^(0|[1-9]\d*)(\.\d+)?$/.test(
            normalized,
        )
    ) {
        throw contractError(
            `${field} должен быть неотрицательным decimal`,
        )
    }

    return normalized
}

export function optionalBoolean(
    value: unknown,
    field: string,
): boolean | null {
    if (value === undefined || value === null) {
        return null
    }

    if (typeof value !== 'boolean') {
        throw contractError(
            `${field} должен быть boolean`,
        )
    }

    return value
}

export function optionalString(
    value: unknown,
): string | null {
    return typeof value === 'string'
        && value.trim().length > 0
        ? value.trim()
        : null
}

export function isRecord(
    value: unknown,
): value is Record<string, unknown> {
    return typeof value === 'object'
        && value !== null
        && !Array.isArray(value)
}
