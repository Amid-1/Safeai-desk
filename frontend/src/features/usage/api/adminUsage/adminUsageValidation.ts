// frontend/src/features/usage/api/adminUsage/adminUsageValidation.ts
// ============================================================
// frontend/src/features/usage/api/adminUsage/adminUsageValidation.ts
// ============================================================

import { contractError, expectBoolean, expectString, parseDecimalString } from '../../../../shared/api/runtime'

export function parseOptionalBoolean(
    value: unknown,
    field: string,
): boolean | null {
    if (
        value === undefined
        || value === null
    ) {
        return null
    }

    return expectBoolean(
        value,
        field,
    )
}

export function parseOptionalCount(
    value: unknown,
    field: string,
): string | null {
    if (
        value === undefined
        || value === null
    ) {
        return null
    }

    return parseNonNegativeIntegerString(
        value,
        field,
    )
}

export function parseNonNegativeIntegerString(
    value: unknown,
    field: string,
): string {
    if (
        typeof value === 'number'
    ) {
        if (
            !Number.isSafeInteger(value)
            || value < 0
        ) {
            throw contractError(
                `${field} должен быть неотрицательным safe integer или decimal string`,
            )
        }

        return String(value)
    }

    if (
        typeof value === 'string'
        && /^(?:0|[1-9]\d*)$/.test(
            value,
        )
    ) {
        return value
    }

    throw contractError(
        `${field} должен быть неотрицательным integer string`,
    )
}

export function parseNonNegativeDecimalString(
    value: unknown,
    field: string,
): string | null {
    if (value === null) {
        return null
    }

    if (typeof value !== 'string') {
        throw contractError(
            `${field} должен быть decimal string или null`,
        )
    }

    const decimal =
        parseDecimalString(
            value,
            field,
        )

    if (decimal === null) {
        return null
    }

    if (
        decimal.startsWith('-')
    ) {
        throw contractError(
            `${field} не может быть отрицательным`,
        )
    }

    const fraction =
        decimal.split('.')[1] ?? ''

    if (fraction.length > 12) {
        throw contractError(
            `${field} не должен иметь scale больше 12`,
        )
    }

    return decimal
}

export function parseDateOnly(
    value: unknown,
    field: string,
): string {
    const dateValue = expectString(
        value,
        field,
    )

    const match =
        /^(\d{4})-(\d{2})-(\d{2})$/.exec(
            dateValue,
        )

    if (!match) {
        throw contractError(
            `${field} должен иметь формат YYYY-MM-DD`,
        )
    }

    const year = Number(match[1])
    const month = Number(match[2])
    const day = Number(match[3])

    // Date.UTC по умолчанию трактует годы 00–99 как 1900–1999.
    // Для ISO LocalDate такая коррекция недопустима.
    const date = new Date(0)
    date.setUTCFullYear(year, month - 1, day)
    date.setUTCHours(0, 0, 0, 0)

    if (
        date.getUTCFullYear() !== year
        || date.getUTCMonth()
            !== month - 1
        || date.getUTCDate() !== day
    ) {
        throw contractError(
            `${field} содержит невозможную календарную дату`,
        )
    }

    return dateValue
}
