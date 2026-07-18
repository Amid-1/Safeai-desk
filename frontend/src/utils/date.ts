// ============================================================
// frontend/src/utils/date.ts
// ============================================================

const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/

export function toUtcStartOfDayIso(dateValue: string): string {
    assertValidDateValue(dateValue)

    return `${dateValue}T00:00:00Z`
}

export function toUtcExclusiveEndOfDayIso(
    dateValue: string
): string {
    return `${addOneDayUtc(dateValue)}T00:00:00Z`
}

export function addOneDayUtc(dateValue: string): string {
    const { year, month, day } = parseDateValue(dateValue)

    const date = new Date(Date.UTC(year, month - 1, day))
    date.setUTCDate(date.getUTCDate() + 1)

    return date.toISOString().slice(0, 10)
}

function assertValidDateValue(dateValue: string): void {
    parseDateValue(dateValue)
}

function parseDateValue(dateValue: string): {
    year: number
    month: number
    day: number
} {
    const match = DATE_PATTERN.exec(dateValue)

    if (!match) {
        throw new Error(
            `Некорректная дата: ${dateValue}. Ожидается формат YYYY-MM-DD`
        )
    }

    const year = Number(match[1])
    const month = Number(match[2])
    const day = Number(match[3])

    const date = new Date(Date.UTC(year, month - 1, day))

    if (
        date.getUTCFullYear() !== year
        || date.getUTCMonth() !== month - 1
        || date.getUTCDate() !== day
    ) {
        throw new Error(`Некорректная календарная дата: ${dateValue}`)
    }

    return { year, month, day }
}