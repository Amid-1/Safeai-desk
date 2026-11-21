// frontend/src/features/usage/ui/physicalUsageCsv.ts
import type { PhysicalUsageReport } from '../api/physicalUsageApi'
import { serializeCsvRows } from './usageCsv'

export function serializePhysicalUsageCsv(report: PhysicalUsageReport): string {
    const empty = report.physicalAttempts === '0'
    const completeness = (complete: boolean) => empty ? 'Нет данных' : complete ? 'Полно' : 'Неполно'
    return serializeCsvRows([
        [
            'База учёта', 'Начало UTC включительно', 'Конец UTC исключительно', 'Организация',
            'Физических попыток', 'В процессе', 'Неоднозначных', 'Исполнено', 'Не исполнено', 'Отклонено',
            'Попыток с неизвестной стоимостью', 'Попыток с неполным usage', 'Известные входные токены',
            'Известные выходные токены', 'Известная часть стоимости', 'Валюта',
            'Полнота стоимости', 'Полнота токенов', 'База сверки', 'Планов',
            'Совпало', 'Расхождений', 'Непроверяемых', 'Полная сверка',
        ],
        [
            report.aggregationBasis, report.from, report.to, report.organizationId ?? 'Все организации',
            report.physicalAttempts, report.startedAttempts, report.ambiguousAttempts,
            report.knownExecutedAttempts, report.knownNotExecutedAttempts, report.knownRejectedAttempts,
            report.costUnverifiableAttempts, report.usageUnverifiableAttempts,
            empty || report.usageUnverifiableAttempts === report.physicalAttempts ? '' : report.knownInputTokens,
            empty || report.usageUnverifiableAttempts === report.physicalAttempts ? '' : report.knownOutputTokens,
            empty || report.costUnverifiableAttempts === report.physicalAttempts ? '' : report.knownCostUsd,
            report.currency, completeness(report.costComplete),
            completeness(report.usageComplete), report.planSelectionBasis, report.plans,
            report.costMatchedPlans, report.costMismatchPlans, report.unverifiablePlans,
            report.plans === '0' ? 'Нет данных' : report.fullyReconciled ? 'Да' : 'Нет',
        ],
    ])
}
