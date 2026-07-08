// frontend/src/utils/date.ts

export function toUtcStartOfDayIso(dateValue: string): string {
    return `${dateValue}T00:00:00Z`
}

export function toUtcExclusiveEndOfDayIso(dateValue: string): string {
    return `${addOneDayUtc(dateValue)}T00:00:00Z`
}

export function addOneDayUtc(dateValue: string): string {
    const [year, month, day] = dateValue
        .split('-')
        .map((part) => Number(part))

    const date = new Date(Date.UTC(year, month - 1, day))
    date.setUTCDate(date.getUTCDate() + 1)

    return date.toISOString().slice(0, 10)
}