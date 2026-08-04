import { ApiError } from './http'
import type { PageResponse } from '../utils/page'

const UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function contractError(
    message: string,
): ApiError {
    return new ApiError(
        message,
        {
            status: 0,
            error: 'INVALID_RESPONSE',
            message,
        },
        0,
    )
}

export function isRecord(
    value: unknown,
): value is Record<string, unknown> {
    return typeof value === 'object'
        && value !== null
        && !Array.isArray(value)
}

export function expectRecord(
    value: unknown,
    field = 'response',
): Record<string, unknown> {
    if (!isRecord(value)) {
        throw contractError(
            `${field} должен быть объектом`,
        )
    }

    return value
}

export function expectString(
    value: unknown,
    field: string,
    options: {
        allowEmpty?: boolean
        maxLength?: number
    } = {},
): string {
    if (typeof value !== 'string') {
        throw contractError(
            `${field} должен быть строкой`,
        )
    }

    if (!options.allowEmpty && value.length === 0) {
        throw contractError(
            `${field} не должен быть пустым`,
        )
    }

    if (
        options.maxLength !== undefined
        && value.length > options.maxLength
    ) {
        throw contractError(
            `${field} превышает допустимую длину`,
        )
    }

    return value
}

export function expectNullableString(
    value: unknown,
    field: string,
    options: {
        maxLength?: number
    } = {},
): string | null {
    if (value === null) {
        return null
    }

    return expectString(
        value,
        field,
        {
            allowEmpty: true,
            maxLength: options.maxLength,
        },
    )
}

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

export function expectUuid(
    value: unknown,
    field: string,
): string {
    const normalized = expectString(
        value,
        field,
    )

    if (!UUID_PATTERN.test(normalized)) {
        throw contractError(
            `${field} должен быть UUID`,
        )
    }

    return normalized.toLowerCase()
}

export function expectNullableUuid(
    value: unknown,
    field: string,
): string | null {
    return value === null
        ? null
        : expectUuid(value, field)
}

export function expectInstant(
    value: unknown,
    field: string,
): string {
    const instant = expectString(value, field)
    const timestamp = Date.parse(instant)

    if (!Number.isFinite(timestamp)) {
        throw contractError(
            `${field} должен быть ISO-8601 Instant`,
        )
    }

    return instant
}

export function expectNullableInstant(
    value: unknown,
    field: string,
): string | null {
    return value === null
        ? null
        : expectInstant(value, field)
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

export function expectNullableNonNegativeInteger(
    value: unknown,
    field: string,
): number | null {
    return value === null
        ? null
        : expectNonNegativeInteger(
            value,
            field,
        )
}

export function expectOptionalNonNegativeInteger(
    value: unknown,
    field: string,
): number | null {
    return value === undefined || value === null
        ? null
        : expectNonNegativeInteger(
            value,
            field,
        )
}

export function expectEnum<T extends string>(
    value: unknown,
    field: string,
    allowed: readonly T[],
): T {
    if (
        typeof value !== 'string'
        || !allowed.includes(value as T)
    ) {
        throw contractError(
            `${field} содержит неизвестное значение`,
        )
    }

    return value as T
}

export function expectNullableEnum<
    T extends string,
>(
    value: unknown,
    field: string,
    allowed: readonly T[],
): T | null {
    return value === null
        ? null
        : expectEnum(value, field, allowed)
}

export function expectStringArray<T extends string>(
    value: unknown,
    field: string,
    allowed: readonly T[],
): T[] {
    if (!Array.isArray(value)) {
        throw contractError(
            `${field} должен быть массивом`,
        )
    }

    const result: T[] = []

    value.forEach((item, index) => {
        const parsed = expectEnum(
            item,
            `${field}[${index}]`,
            allowed,
        )

        if (!result.includes(parsed)) {
            result.push(parsed)
        }
    })

    return result
}

export function parseDecimalString(
    value: unknown,
    field: string,
): string | null {
    if (value === null) {
        return null
    }

    if (typeof value === 'string') {
        if (!/^-?\d+(?:\.\d+)?$/.test(value)) {
            throw contractError(
                `${field} должен быть decimal string`,
            )
        }

        return value
    }

    // Временная обратная совместимость с Jackson BigDecimal,
    // сериализованным как JSON number. Целевой backend-контракт —
    // строка, чтобы браузер не терял decimal precision.
    if (
        typeof value === 'number'
        && Number.isFinite(value)
    ) {
        return String(value)
    }

    throw contractError(
        `${field} должен быть decimal string или null`,
    )
}

export function parsePageResponse<T>(
    value: unknown,
    itemParser: (
        item: unknown,
        field: string,
    ) => T,
): PageResponse<T> {
    const record = expectRecord(
        value,
        'pageResponse',
    )

    if (!Array.isArray(record.content)) {
        throw contractError(
            'pageResponse.content должен быть массивом',
        )
    }

    const content = record.content.map(
        (item, index) =>
            itemParser(
                item,
                `pageResponse.content[${index}]`,
            ),
    )

    const nestedPage =
        isRecord(record.page)
            ? record.page
            : null

    const page = expectNonNegativeInteger(
        nestedPage?.number
            ?? (
                typeof record.page
                    === 'number'
                    ? record.page
                    : record.number
            ),
        'pageResponse.page',
    )

    const size = expectNonNegativeInteger(
        nestedPage?.size
            ?? record.size,
        'pageResponse.size',
    )

    const totalElements =
        expectNonNegativeInteger(
            nestedPage?.totalElements
                ?? record.totalElements,
            'pageResponse.totalElements',
        )

    const totalPages =
        expectNonNegativeInteger(
            nestedPage?.totalPages
                ?? record.totalPages,
            'pageResponse.totalPages',
        )

    if (
        totalElements < content.length
    ) {
        throw contractError(
            'pageResponse.totalElements меньше количества элементов content',
        )
    }

    if (
        size > 0
        && content.length > size
    ) {
        throw contractError(
            'pageResponse.content превышает page size',
        )
    }

    return {
        content,
        page,
        size,
        totalElements,
        totalPages,
    }
}
