// frontend/src/utils/format.ts
export function formatDateTime(value: string | null | undefined): string {
    if (!value) {
        return '-'
    }

    const date = new Date(value)

    if (Number.isNaN(date.getTime())) {
        return '-'
    }

    return date.toLocaleString()
}

export function formatDate(value: string | null | undefined): string {
    if (!value) {
        return '-'
    }

    const dateOnlyMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)

    if (dateOnlyMatch) {
        const [, year, month, day] = dateOnlyMatch

        return `${day}.${month}.${year}`
    }

    const date = new Date(value)

    if (Number.isNaN(date.getTime())) {
        return '-'
    }

    return date.toLocaleDateString()
}

export function formatUsd(value: number | string | null | undefined): string {
    const numericValue = typeof value === 'string'
        ? Number(value)
        : value

    if (
        numericValue === null
        || numericValue === undefined
        || Number.isNaN(numericValue)
    ) {
        return '$0.0000'
    }

    return `$${numericValue.toFixed(4)}`
}