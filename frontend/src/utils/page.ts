export type PageResponse<T> = {
    content: T[]
    page: number
    size: number
    totalElements: number
    totalPages: number
}

export type NormalizedPage<T> =
    PageResponse<T>

export function normalizePageResponse<T>(
    response: PageResponse<T>,
): NormalizedPage<T> {
    if (
        !response
        || typeof response !== 'object'
        || !Array.isArray(
            response.content,
        )
        || !isNonNegativeSafeInteger(
            response.page,
        )
        || !isNonNegativeSafeInteger(
            response.size,
        )
        || !isNonNegativeSafeInteger(
            response.totalElements,
        )
        || !isNonNegativeSafeInteger(
            response.totalPages,
        )
    ) {
        throw new TypeError(
            'Некорректный PageResponse.',
        )
    }

    if (
        response.totalElements
            < response.content.length
    ) {
        throw new TypeError(
            'PageResponse.totalElements меньше количества элементов страницы.',
        )
    }

    if (
        response.size > 0
        && response.content.length
            > response.size
    ) {
        throw new TypeError(
            'PageResponse.content превышает page size.',
        )
    }

    return response
}

export function pageFromArray<T>(
    content: T[],
): PageResponse<T> {
    return {
        content,
        page: 0,
        size: content.length,
        totalElements: content.length,
        totalPages:
            content.length > 0
                ? 1
                : 0,
    }
}

function isNonNegativeSafeInteger(
    value: unknown,
): value is number {
    return typeof value === 'number'
        && Number.isSafeInteger(value)
        && value >= 0
}
