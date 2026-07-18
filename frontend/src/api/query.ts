// ============================================================
// frontend/src/api/query.ts
// ============================================================

export type QueryValue = string | number | boolean | null | undefined

export function buildQueryString(
    values: Record<string, QueryValue>
): string {
    const params = new URLSearchParams()

    Object.entries(values).forEach(([key, value]) => {
        if (value === undefined || value === null || value === '') {
            return
        }

        params.set(key, String(value))
    })

    const query = params.toString()

    return query ? `?${query}` : ''
}

export function normalizePage(page: number): number {
    if (!Number.isFinite(page)) {
        return 0
    }

    return Math.max(0, Math.trunc(page))
}

export function normalizePageSize(
    size: number,
    defaultSize = 50,
    maxSize = 200
): number {
    if (!Number.isFinite(size)) {
        return defaultSize
    }

    return Math.min(
        Math.max(1, Math.trunc(size)),
        maxSize
    )
}

export function pathSegment(value: string): string {
    return encodeURIComponent(value)
}