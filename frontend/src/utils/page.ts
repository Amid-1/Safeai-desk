// frontend/src/utils/page.ts
export type PageResponse<T> = {
    content: T[]
    totalElements?: number
    totalPages?: number
    size?: number
    number?: number
    page?: {
        size: number
        number: number
        totalElements: number
        totalPages: number
    }
}

export type PageOrArray<T> = T[] | PageResponse<T>

export function getPageContent<T>(
    response: PageOrArray<T> | null | undefined
): T[] {
    if (!response) {
        return []
    }

    if (Array.isArray(response)) {
        return response
    }

    return response.content ?? []
}

export function getPageTotalPages<T>(
    response: PageOrArray<T> | null | undefined
): number {
    if (!response || Array.isArray(response)) {
        return 1
    }

    return response.page?.totalPages ?? response.totalPages ?? 1
}