import {
    toUtcStartOfDayIso,
} from '../utils/date'

const UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export type UsageReportTab =
    | 'summary'
    | 'users'
    | 'models'
    | 'daily'

export function validateFilters(
    dateFrom: string,
    dateTo: string,
    organizationId: string,
): string | null {
    if (
        !dateFrom
        || !dateTo
    ) {
        return 'Обе даты обязательны.'
    }

    try {
        toUtcStartOfDayIso(
            dateFrom,
        )

        toUtcStartOfDayIso(
            dateTo,
        )
    } catch {
        return 'Некорректная календарная дата.'
    }

    if (
        dateFrom > dateTo
    ) {
        return 'Дата начала не может быть позже даты окончания.'
    }

    const rangeDays =
        Math.round(
            (
                Date.parse(
                    `${dateTo}T00:00:00Z`,
                )
                - Date.parse(
                    `${dateFrom}T00:00:00Z`,
                )
            ) / 86_400_000,
        )

    if (
        rangeDays + 1 > 366
    ) {
        return 'Период не должен превышать 366 дней.'
    }

    const normalizedOrganizationId =
        organizationId.trim()

    if (
        normalizedOrganizationId
        && !UUID_PATTERN.test(
            normalizedOrganizationId,
        )
    ) {
        return 'Некорректный UUID организации.'
    }

    return null
}

export function defaultUtcRange(): {
    dateFrom: string
    dateTo: string
} {
    const dateTo =
        new Date()

    dateTo.setUTCHours(
        0,
        0,
        0,
        0,
    )

    const dateFrom =
        new Date(
            dateTo,
        )

    dateFrom.setUTCDate(
        dateFrom.getUTCDate()
        - 29,
    )

    return {
        dateFrom:
            dateFrom
                .toISOString()
                .slice(
                    0,
                    10,
                ),

        dateTo:
            dateTo
                .toISOString()
                .slice(
                    0,
                    10,
                ),
    }
}

export function parseTab(
    value:
        string | null,
): UsageReportTab {
    switch (value) {
        case 'users':
        case 'models':
        case 'daily':
            return value

        default:
            return 'summary'
    }
}

export function reportTitle(
    tab: UsageReportTab,
): string {
    switch (tab) {
        case 'users':
            return 'Расход по сотрудникам'

        case 'models':
            return 'Расход по моделям'

        case 'daily':
            return 'Динамика использования'

        default:
            return 'Детальная сводка'
    }
}

export function reportDescription(
    tab: UsageReportTab,
): string {
    switch (tab) {
        case 'users':
            return 'Суммарные токены и стоимость для каждого сотрудника.'

        case 'models':
            return 'Сравнение объёма и стоимости используемых AI-моделей.'

        case 'daily':
            return 'Изменение расхода токенов и стоимости по календарным дням.'

        default:
            return 'Использование с разбивкой по сотрудникам и моделям.'
    }
}

export function parsePage(
    value:
        string | null,
): number {
    if (!value) {
        return 0
    }

    const parsed =
        Number(value)

    return Number.isSafeInteger(
        parsed,
    )
    && parsed >= 0
        ? parsed
        : 0
}

export function formatCount(
    value: string,
): string {
    try {
        return new Intl.NumberFormat(
            'ru-RU',
        ).format(
            BigInt(
                value,
            ),
        )
    } catch {
        return value
    }
}

export function trimDecimal(
    value: string,
): string {
    if (
        !value.includes(
            '.',
        )
    ) {
        return value
    }

    const normalized =
        value
            .replace(
                /0+$/,
                '',
            )
            .replace(
                /\.$/,
                '',
            )

    return normalized
        || '0'
}

export function formatIsoDate(
    value: string,
): string {
    const [
        year,
        month,
        day,
    ] = value.split(
        '-',
    )

    void year

    return `${day}.${month}.${year}`
}

export function countPart(
    label: string,
    value:
        string | null,
): string {
    return value
    && value !== '0'
        ? `${label}: ${formatCount(value)}`
        : ''
}

export function isAbortError(
    error: unknown,
): boolean {
    return error instanceof Error
        && (
            error.name === 'AbortError'
            || (
                'errorCode' in error
                && (error as { errorCode?: string }).errorCode
                === 'REQUEST_ABORTED'
            )
        )
}

