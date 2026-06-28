// frontend/src/utils/format.ts
export function formatDateTime(value: string | null | undefined): string {
    if (!value) {
        return '-'
    }

    return new Date(value).toLocaleString()
}

export function formatDate(value: string | null | undefined): string {
    if (!value) {
        return '-'
    }

    return new Date(value).toLocaleDateString()
}

export function formatUsd(value: number | string | null | undefined): string {
    const numericValue = typeof value === 'string'
        ? Number(value)
        : value

    if (numericValue === null || numericValue === undefined || Number.isNaN(numericValue)) {
        return '$0.0000'
    }

    return `$${numericValue.toFixed(4)}`
}