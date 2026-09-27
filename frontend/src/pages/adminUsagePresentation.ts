// frontend/src/pages/adminUsagePresentation.ts

import type {
    UsageReportTab,
} from './adminUsageFilterState'

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

export function formatCount(
    value: string,
): string {
    try {
        return new Intl.NumberFormat(
            'ru-RU',
        ).format(BigInt(value))
    } catch {
        return value
    }
}

export function trimDecimal(
    value: string,
): string {
    if (!value.includes('.')) {
        return value
    }

    const normalized = value
        .replace(/0+$/, '')
        .replace(/\.$/, '')

    return normalized || '0'
}

export function formatIsoDate(
    value: string | null | undefined,
): string {
    if (
        typeof value !== 'string'
        || !/^\d{4}-\d{2}-\d{2}$/.test(value)
    ) {
        return '—'
    }

    const [year, month, day] = value.split('-')

    return `${day}.${month}.${year}`
}

export function countPart(
    label: string,
    value: string | null,
): string {
    return value && value !== '0'
        ? `${label}: ${formatCount(value)}`
        : ''
}
