// ============================================================
// frontend/src/utils/format.ts
// ============================================================

const USD_FORMATTER = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 4,
    maximumFractionDigits: 6,
})

export function formatDateTime(
    value: string | null | undefined
): string {
    if (!value) {
        return '—'
    }

    const date = new Date(value)

    if (Number.isNaN(date.getTime())) {
        return '—'
    }

    return date.toLocaleString('ru-RU')
}

export function formatDate(
    value: string | null | undefined
): string {
    if (!value) {
        return '—'
    }

    const dateOnlyMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)

    if (dateOnlyMatch) {
        const [, year, month, day] = dateOnlyMatch

        return `${day}.${month}.${year}`
    }

    const date = new Date(value)

    if (Number.isNaN(date.getTime())) {
        return '—'
    }

    return date.toLocaleDateString('ru-RU')
}

export function formatUsd(
    value: number | string | null | undefined
): string {
    if (value === null || value === undefined || value === '') {
        return USD_FORMATTER.format(0)
    }

    const numericValue =
        typeof value === 'string'
            ? Number(value)
            : value

    if (!Number.isFinite(numericValue)) {
        return USD_FORMATTER.format(0)
    }

    return USD_FORMATTER.format(Math.max(0, numericValue))
}

