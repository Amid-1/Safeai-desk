// frontend/src/components/admin/models/modelControlPlane/modelValues.ts
const DECIMAL_PATTERN = /^\d+(?:\.\d+)?$/


export function requireText(
    value: string,
    field: string,
): string {
    const normalized = value.trim()

    if (!normalized) {
        throw new Error(
            `${field} не должен быть пустым.`,
        )
    }

    return normalized
}

export function nullableText(
    value: string,
): string | null {
    const normalized = value.trim()
    return normalized || null
}

export function parsePositiveInteger(
    value: string,
    field: string,
): number {
    const parsed = Number(value)

    if (
        !Number.isSafeInteger(parsed)
        || parsed <= 0
    ) {
        throw new Error(
            `${field} должен быть положительным целым числом.`,
        )
    }

    return parsed
}

export function parseOptionalPositiveInteger(
    value: string,
    field: string,
): number | null {
    return value.trim()
        ? parsePositiveInteger(value, field)
        : null
}

export function parseOptionalNonNegativeInteger(
    value: string,
    field: string,
): number | null {
    if (!value.trim()) {
        return null
    }

    const parsed = Number(value)

    if (
        !Number.isSafeInteger(parsed)
        || parsed < 0
    ) {
        throw new Error(
            `${field} должен быть неотрицательным целым числом.`,
        )
    }

    return parsed
}

export function parseOptionalDecimal(
    value: string,
    field: string,
): string | null {
    const normalized = value.trim()

    if (!normalized) {
        return null
    }

    if (!DECIMAL_PATTERN.test(normalized)) {
        throw new Error(
            `${field}: укажите неотрицательное число без экспоненты.`,
        )
    }

    const [integerPart, fractionPart = ''] =
        normalized.split('.')

    const integerDigits =
        integerPart.replace(/^0+/, '').length

    if (
        integerDigits > 18
        || fractionPart.length > 12
    ) {
        throw new Error(
            `${field}: допускается максимум 18 цифр до точки и 12 после.`,
        )
    }

    return normalized
}

export function normalizeJsonObject(
    value: string,
): string {
    const normalized =
        value.trim() || '{}'

    let parsed: unknown

    try {
        parsed = JSON.parse(normalized)
    } catch {
        throw new Error(
            'Дополнительные параметры тарификации должны быть корректным JSON-объектом.',
        )
    }

    if (
        typeof parsed !== 'object'
        || parsed === null
        || Array.isArray(parsed)
    ) {
        throw new Error(
            'Дополнительные параметры тарификации должны быть JSON-объектом.',
        )
    }

    return JSON.stringify(parsed)
}
