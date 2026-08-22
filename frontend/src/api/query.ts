export type QueryValue =
    | string
    | number
    | boolean
    | null
    | undefined

const UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function buildQueryString(
    values: Record<string, QueryValue>,
): string {
    const params = new URLSearchParams()

    Object.entries(values)
        .sort(([firstKey], [secondKey]) => {
            if (firstKey < secondKey) {
                return -1
            }

            if (firstKey > secondKey) {
                return 1
            }

            return 0
        })
        .forEach(([key, value]) => {
            if (
                value === undefined
                || value === null
                || value === ''
            ) {
                return
            }

            if (
                typeof value === 'number'
                && !Number.isFinite(value)
            ) {
                throw new Error(
                    `Query-параметр ${key} должен быть конечным числом`,
                )
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
    maxSize = 200,
): number {
    const safeMaxSize =
        Number.isFinite(maxSize)
        && maxSize >= 1
            ? Math.trunc(maxSize)
            : 200

    const safeDefaultSize =
        Number.isFinite(defaultSize)
            ? Math.min(
                Math.max(
                    1,
                    Math.trunc(defaultSize),
                ),
                safeMaxSize,
            )
            : Math.min(50, safeMaxSize)

    if (!Number.isFinite(size)) {
        return safeDefaultSize
    }

    return Math.min(
        Math.max(1, Math.trunc(size)),
        safeMaxSize,
    )
}

export function pathSegment(value: string): string {
    if (!value) {
        throw new Error(
            'Path segment не должен быть пустым',
        )
    }

    if (
        value === '.'
        || value === '..'
        || value.includes('/')
        || value.includes('\\')
        || containsControlCharacter(value)
    ) {
        throw new Error(
            'Недопустимый path segment',
        )
    }

    try {
        return encodeURIComponent(value)
    } catch {
        throw new Error(
            'Path segment содержит некорректные символы',
        )
    }
}

function containsControlCharacter(value: string): boolean {
    return Array.from(value).some((character) => {
        const codePoint = character.codePointAt(0) ?? 0
        return codePoint < 32 || codePoint === 127
    })
}

export function uuidPathSegment(
    value: string,
): string {
    if (!UUID_PATTERN.test(value)) {
        throw new Error(
            'Некорректный UUID path segment',
        )
    }

    return value.toLowerCase()
}
