const DATE_PATTERN =
    /^(\d{4})-(\d{2})-(\d{2})$/

export type CalendarDate = {
    year: number
    month: number
    day: number
}

export type DateRange = {
    dateFrom: string
    dateTo: string
}

export type DateBoundaryMode =
    | 'UTC'
    | 'LOCAL'

export function parseDateValue(
    dateValue: string,
): CalendarDate {
    const match = DATE_PATTERN.exec(
        dateValue,
    )

    if (!match) {
        throw new Error(
            `Некорректная дата: ${dateValue}. `
            + 'Ожидается формат YYYY-MM-DD.',
        )
    }

    const year = Number(match[1])
    const month = Number(match[2])
    const day = Number(match[3])

    const date = new Date(
        Date.UTC(
            year,
            month - 1,
            day,
        ),
    )

    if (
        date.getUTCFullYear() !== year
        || date.getUTCMonth()
            !== month - 1
        || date.getUTCDate() !== day
    ) {
        throw new Error(
            `Некорректная календарная дата: ${dateValue}.`,
        )
    }

    return {
        year,
        month,
        day,
    }
}

export function toUtcStartOfDayIso(
    dateValue: string,
): string {
    parseDateValue(dateValue)

    return `${dateValue}T00:00:00.000Z`
}

export function toUtcExclusiveEndOfDayIso(
    dateValue: string,
): string {
    return `${addCalendarDays(
        dateValue,
        1,
    )}T00:00:00.000Z`
}

export function toLocalStartOfDayIso(
    dateValue: string,
): string {
    const {
        year,
        month,
        day,
    } = parseDateValue(dateValue)

    return new Date(
        year,
        month - 1,
        day,
        0,
        0,
        0,
        0,
    ).toISOString()
}

export function toLocalExclusiveEndOfDayIso(
    dateValue: string,
): string {
    const {
        year,
        month,
        day,
    } = parseDateValue(dateValue)

    // Конструктор локальной даты корректно учитывает DST:
    // exclusive end — следующая локальная полночь,
    // а не start + 24 часа.
    return new Date(
        year,
        month - 1,
        day + 1,
        0,
        0,
        0,
        0,
    ).toISOString()
}

export function addCalendarDays(
    dateValue: string,
    days: number,
): string {
    if (!Number.isSafeInteger(days)) {
        throw new Error(
            'Количество дней должно быть целым безопасным числом.',
        )
    }

    const {
        year,
        month,
        day,
    } = parseDateValue(dateValue)

    const date = new Date(
        Date.UTC(
            year,
            month - 1,
            day,
        ),
    )

    date.setUTCDate(
        date.getUTCDate() + days,
    )

    return date
        .toISOString()
        .slice(0, 10)
}

export function getLocalDateValue(
    date: Date = new Date(),
): string {
    return formatDateParts(
        date.getFullYear(),
        date.getMonth() + 1,
        date.getDate(),
    )
}

export function getUtcDateValue(
    date: Date = new Date(),
): string {
    return formatDateParts(
        date.getUTCFullYear(),
        date.getUTCMonth() + 1,
        date.getUTCDate(),
    )
}

export function createRecentDateRange(
    days: number,
    mode: DateBoundaryMode,
    now: Date = new Date(),
): DateRange {
    if (
        !Number.isSafeInteger(days)
        || days < 1
    ) {
        throw new Error(
            'Период должен содержать минимум один день.',
        )
    }

    const dateTo =
        mode === 'UTC'
            ? getUtcDateValue(now)
            : getLocalDateValue(now)

    return {
        dateFrom: addCalendarDays(
            dateTo,
            -(days - 1),
        ),
        dateTo,
    }
}

export function getInclusiveDayCount(
    dateFrom: string,
    dateTo: string,
): number {
    const from = parseDateValue(
        dateFrom,
    )
    const to = parseDateValue(
        dateTo,
    )

    const fromEpoch = Date.UTC(
        from.year,
        from.month - 1,
        from.day,
    )

    const toEpoch = Date.UTC(
        to.year,
        to.month - 1,
        to.day,
    )

    const difference =
        Math.trunc(
            (
                toEpoch - fromEpoch
            ) / 86_400_000,
        )

    return difference + 1
}

export function assertDateRange(
    dateFrom: string,
    dateTo: string,
    maxInclusiveDays: number,
): void {
    if (
        !Number.isSafeInteger(
            maxInclusiveDays,
        )
        || maxInclusiveDays < 1
    ) {
        throw new Error(
            'Максимальный диапазон дат задан некорректно.',
        )
    }

    const days = getInclusiveDayCount(
        dateFrom,
        dateTo,
    )

    if (days < 1) {
        throw new Error(
            'Дата начала периода не может быть позже даты окончания.',
        )
    }

    if (days > maxInclusiveDays) {
        throw new Error(
            `Период не должен превышать ${maxInclusiveDays} дней.`,
        )
    }
}

function formatDateParts(
    year: number,
    month: number,
    day: number,
): string {
    return [
        String(year).padStart(
            4,
            '0',
        ),
        String(month).padStart(
            2,
            '0',
        ),
        String(day).padStart(
            2,
            '0',
        ),
    ].join('-')
}
