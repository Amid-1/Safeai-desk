// frontend/src/features/usage/lib/usageBudgetStatus.ts
import type { BudgetStatus } from '../api/usage/usageTypes'
import type { PlatformBudgetSummary } from '../api/platformBudgetApi'

export const BUDGET_CATEGORIES: readonly {
    status: BudgetStatus; key: keyof Omit<PlatformBudgetSummary, 'total' | 'enabled' | 'periodStart' | 'periodEnd' | 'asOf'>;
    label: string; tone: string; description: string
}[] = [
    {status: 'WITHIN', key: 'within', label: 'В пределах бюджета', tone: 'ok', description: 'Известные обязательства составляют менее 80% бюджета.'},
    {status: 'NEAR', key: 'nearLimit', label: 'От 80% до лимита', tone: 'warn', description: 'Известные обязательства составляют от 80% до 100% бюджета включительно.'},
    {status: 'EXCEEDED', key: 'exceeded', label: 'Превысили лимит', tone: 'danger', description: 'Известные обязательства превышают бюджет, даже если часть расходов неизвестна.'},
    {status: 'NO_LIMIT', key: 'noLimit', label: 'Без лимита', tone: 'neutral', description: 'Правила включены, месячный бюджет не задан.'},
    {status: 'UNKNOWN', key: 'unknown', label: 'Неизвестные расходы', tone: 'warn', description: 'Есть обязательства без известной цены; превышение пока не подтверждено.'},
    {status: 'DISABLED', key: 'disabled', label: 'Правила отключены', tone: 'neutral', description: 'Политика существует, но отключена.'},
    {status: 'UNCONFIGURED', key: 'unconfigured', label: 'Правила не заданы', tone: 'neutral', description: 'Политика использования моделей для организации ещё не создана.'},
]

export function parseBudgetStatus(value: string | null): BudgetStatus | undefined {
    return BUDGET_CATEGORIES.find(category => category.status === value)?.status
}
