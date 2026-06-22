// frontend/src/utils/format.ts
export function formatDateTime(value: string): string {
    return new Date(value).toLocaleString()
}

export function formatDate(value: string): string {
    return new Date(value).toLocaleDateString()
}

export function formatUsd(value: number): string {
    return `$${value.toFixed(4)}`
}