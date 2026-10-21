// frontend/src/api/chat/chatValidation.ts
// Локальные проверки безопасных целых чисел и логических значений.

import { contractError } from '../runtime'

export function expectBoolean(
    value: unknown,
    field: string,
): boolean {
    if (typeof value !== 'boolean') {
        throw contractError(
            `${field} должен быть boolean`,
        )
    }

    return value
}

export function expectNonNegativeInteger(
    value: unknown,
    field: string,
): number {
    if (
        typeof value !== 'number'
        || !Number.isSafeInteger(value)
        || value < 0
    ) {
        throw contractError(
            `${field} должен быть неотрицательным целым числом`,
        )
    }

    return value
}

export function expectPositiveInteger(
    value: unknown,
    field: string,
): number {
    const parsed = expectNonNegativeInteger(
        value,
        field,
    )

    if (parsed < 1) {
        throw contractError(
            `${field} должен быть положительным`,
        )
    }

    return parsed
}
