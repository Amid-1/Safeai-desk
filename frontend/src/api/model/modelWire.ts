// frontend/src/api/model/modelWire.ts
// Назначение: строгая проверка примитивов и денежных значений ответа API.

const DECIMAL_PATTERN =
    /^\d+(?:\.\d+)?$/

const UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const SHA256_PATTERN = /^[0-9a-f]{64}$/
const MODEL_KEY_PATTERN = /^[a-z0-9][a-z0-9._:/-]{0,159}$/
const INSTANT_PATTERN =
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/

export function requireNonBlankString(
    value: unknown,
    path: string,
): string {
    const result = requireString(value, path)
    if (!result.trim()) {
        throw new Error(`${path} не должен быть пустым`)
    }
    return result
}

export function optionalNonBlankString(
    value: unknown,
    path: string,
): string | null {
    return value === null || value === undefined
        ? null
        : requireNonBlankString(value, path)
}

export function requireUuid(value: unknown, path: string): string {
    const result = requireString(value, path)
    if (!UUID_PATTERN.test(result)) {
        throw new Error(`${path} должен быть UUID`)
    }
    return result
}

export function optionalUuid(value: unknown, path: string): string | null {
    return value === null || value === undefined
        ? null
        : requireUuid(value, path)
}

export function requireSha256(value: unknown, path: string): string {
    const result = requireString(value, path)
    if (!SHA256_PATTERN.test(result)) {
        throw new Error(`${path} должен быть lowercase SHA-256`)
    }
    return result
}

export function requireInstant(value: unknown, path: string): string {
    const result = requireString(value, path)
    const match = INSTANT_PATTERN.exec(result)

    if (!match) {
        throw new Error(`${path} должен быть ISO-8601 Instant в UTC`)
    }

    const year = Number(result.slice(0, 4))
    const month = Number(result.slice(5, 7))
    const day = Number(result.slice(8, 10))
    const hour = Number(result.slice(11, 13))
    const minute = Number(result.slice(14, 16))
    const second = Number(result.slice(17, 19))

    if (month < 1 || month > 12
        || day < 1
        || day > daysInUtcMonth(year, month)
        || hour > 23
        || minute > 59
        || second > 59
        || Number.isNaN(Date.parse(result))) {
        throw new Error(`${path} должен быть корректным ISO-8601 Instant в UTC`)
    }

    return result
}

export function daysInUtcMonth(year: number, month: number): number {
    if (month === 2) {
        const leap = year % 4 === 0
            && (year % 100 !== 0 || year % 400 === 0)
        return leap ? 29 : 28
    }

    return [4, 6, 9, 11].includes(month)
        ? 30
        : 31
}

export function optionalInstant(value: unknown, path: string): string | null {
    return value === null || value === undefined
        ? null
        : requireInstant(value, path)
}

export function requirePositiveInteger(value: unknown, path: string): number {
    const result = requireInteger(value, path)
    if (result <= 0) {
        throw new Error(`${path} должен быть положительным safe integer`)
    }
    return result
}

export function requireNonNegativeInteger(value: unknown, path: string): number {
    const result = requireInteger(value, path)
    if (result < 0) {
        throw new Error(`${path} должен быть неотрицательным safe integer`)
    }
    return result
}

export function optionalPositiveInteger(value: unknown, path: string): number | null {
    return value === null || value === undefined
        ? null
        : requirePositiveInteger(value, path)
}

export function optionalNonNegativeInteger(value: unknown, path: string): number | null {
    return value === null || value === undefined
        ? null
        : requireNonNegativeInteger(value, path)
}

export function requireModelKey(value: unknown, path: string): string {
    const result = requireString(value, path)
    if (!MODEL_KEY_PATTERN.test(result) || result !== result.toLowerCase()) {
        throw new Error(`${path} должен быть нормализованным modelKey`)
    }
    return result
}

export function optionalModelKey(value: unknown, path: string): string | null {
    return value === null || value === undefined
        ? null
        : requireModelKey(value, path)
}

export function requireModelKeyArray(value: unknown, path: string): string[] {
    const result = requireArray(value, path).map(
        (item, index) => requireModelKey(item, `${path}[${index}]`),
    )
    if (new Set(result).size !== result.length) {
        throw new Error(`${path} содержит дубликаты`)
    }
    return result
}

export function optionalEnum<T extends readonly string[]>(
    value: unknown,
    allowed: T,
    path: string,
): T[number] | null {
    return value === null || value === undefined
        ? null
        : requireEnum(value, allowed, path)
}

export function requireJsonObjectString(value: unknown, path: string): string {
    const result = requireString(value, path)

    let parsed: unknown
    try {
        parsed = JSON.parse(result) as unknown
    } catch {
        throw new Error(`${path} должен быть JSON object string`)
    }

    if (
        parsed === null
        || typeof parsed !== 'object'
        || Array.isArray(parsed)
    ) {
        throw new Error(`${path} должен быть JSON object string`)
    }

    return result
}

export function optionalDecimalString(
    value: unknown,
    path: string,
): string | null {
    if (value === null || value === undefined) {
        return null
    }

    if (
        typeof value !== 'string'
        || !DECIMAL_PATTERN.test(value)
    ) {
        throw new Error(
            `${path} должен быть decimal string`,
        )
    }

    const [integerPart, fractionPart = ''] = value.split('.')
    const significantIntegerDigits = integerPart.replace(/^0+/, '').length
    if (significantIntegerDigits > 18 || fractionPart.length > 12) {
        throw new Error(
            `${path} должен помещаться в NUMERIC(30,12)`,
        )
    }

    return value
}



export function requireObject(
    value: unknown,
    path: string,
): Record<string, unknown> {
    if (
        value === null
        || typeof value !== 'object'
        || Array.isArray(value)
    ) {
        throw new Error(
            `${path} должен быть object`,
        )
    }

    return value as Record<string, unknown>
}

export function requireArray(
    value: unknown,
    path: string,
): unknown[] {
    if (!Array.isArray(value)) {
        throw new Error(
            `${path} должен быть array`,
        )
    }

    return value
}

export function requireString(
    value: unknown,
    path: string,
): string {
    if (typeof value !== 'string') {
        throw new Error(
            `${path} должен быть string`,
        )
    }

    return value
}

export function requireBoolean(
    value: unknown,
    path: string,
): boolean {
    if (typeof value !== 'boolean') {
        throw new Error(
            `${path} должен быть boolean`,
        )
    }

    return value
}

export function requireInteger(
    value: unknown,
    path: string,
): number {
    if (
        typeof value !== 'number'
        || !Number.isSafeInteger(value)
    ) {
        throw new Error(
            `${path} должен быть safe integer`,
        )
    }

    return value
}


export function requireEnumArray<
    T extends readonly string[],
>(
    value: unknown,
    allowed: T,
    path: string,
): T[number][] {
    if (!Array.isArray(value)) {
        throw new Error(
            `${path} должен быть array`,
        )
    }

    return value.map(
        (item, index) =>
            requireEnum(
                item,
                allowed,
                `${path}[${index}]`,
            ),
    )
}

export function requireEnum<
    T extends readonly string[],
>(
    value: unknown,
    allowed: T,
    path: string,
): T[number] {
    if (
        typeof value !== 'string'
        || !allowed.includes(
            value as T[number],
        )
    ) {
        throw new Error(
            `${path} содержит неизвестное значение`,
        )
    }

    return value as T[number]
}
