// ============================================================
// frontend/src/utils/page.ts
// ============================================================

export type PageResponse<T> = {
    content?: T[]
    totalElements?: number
    totalPages?: number
    size?: number
    number?: number
    page?: {
        size?: number
        number?: number
        totalElements?: number
        totalPages?: number
    }
}

export type NormalizedPage<T> = {
    content: T[]
    page: number
    size: number
    totalElements: number
    totalPages: number
}

export type PageOrArray<T> = T[] | PageResponse<T>

export function normalizePageResponse<T>(
    response: PageOrArray<T> | null | undefined
): NormalizedPage<T> {
    if (!response) {
        return emptyPage()
    }

    if (Array.isArray(response)) {
        return {
            content: response,
            page: 0,
            size: response.length,
            totalElements: response.length,
            totalPages: response.length > 0 ? 1 : 0,
        }
    }

    const content = Array.isArray(response.content)
        ? response.content
        : []

    const page = nonNegativeInteger(
        response.page?.number ?? response.number,
        0
    )

    const size = nonNegativeInteger(
        response.page?.size ?? response.size,
        content.length
    )

    const totalElements = nonNegativeInteger(
        response.page?.totalElements ?? response.totalElements,
        content.length
    )

    const inferredTotalPages =
        size > 0
            ? Math.ceil(totalElements / size)
            : content.length > 0
                ? 1
                : 0

    const totalPages = nonNegativeInteger(
        response.page?.totalPages ?? response.totalPages,
        inferredTotalPages
    )

    return {
        content,
        page,
        size,
        totalElements,
        totalPages,
    }
}

export function getPageContent<T>(
    response: PageOrArray<T> | null | undefined
): T[] {
    return normalizePageResponse(response).content
}

export function getPageTotalPages<T>(
    response: PageOrArray<T> | null | undefined
): number {
    return normalizePageResponse(response).totalPages
}

function emptyPage<T>(): NormalizedPage<T> {
    return {
        content: [],
        page: 0,
        size: 0,
        totalElements: 0,
        totalPages: 0,
    }
}

function nonNegativeInteger(
    value: number | undefined,
    fallback: number
): number {
    if (!Number.isFinite(value)) {
        return fallback
    }

    return Math.max(0, Math.trunc(value as number))
}

