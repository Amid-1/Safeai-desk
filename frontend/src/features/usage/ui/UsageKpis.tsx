// frontend/src/features/usage/ui/UsageKpis.tsx
import type { UsageAmounts } from '../api/usageApi'
import type { UsageReportTab } from '../lib/adminUsageFilterState'
import { formatCount, trimDecimal } from '../lib/adminUsagePresentation'
import { summarizeUsageRows } from './usageMetrics'

export function UsageKpis({ rows, tab, hasMore = true, appliedModel }: {
    rows: UsageAmounts[]
    tab: UsageReportTab
    hasMore?: boolean
    appliedModel?: string
}) {
    const sum = summarizeUsageRows(rows)
    const incomplete = sum.unknownCostRows > 0 || sum.incompleteCostRows > 0
    const cost = sum.rowCount === 0 || (sum.pricedRows === 0 && sum.unknownCostRows > 0)
        ? '—' : sum.knownCost === null ? 'Разные валюты'
            : `${trimDecimal(sum.knownCost)} ${sum.currency ?? 'USD'}`
    const paged = (tab === 'users' || tab === 'summary') && hasMore
    const mock = appliedModel === 'mock-safeai' || rows.some(row => 'model' in row && row.model === 'mock-safeai')
    return (
        <section className="usage-kpi-section" aria-label="Ключевые показатели загруженного отчета">
            <div className="usage-kpis">
                <div className="usage-kpi">
                    <span className="usage-kpi__label">Подтверждённые токены</span>
                    <strong className="usage-kpi__value">{sum.rowCount ? formatCount(sum.total) : '—'}</strong>
                    <small className="usage-kpi__hint">{sum.rowCount
                        ? `Вход: ${formatCount(sum.input)} · Выход: ${formatCount(sum.output)}` : 'Нет данных отчёта'}</small>
                    {BigInt(sum.partial) > 0n && <small className="usage-kpi__hint">Отдельно из частичных ответов: {formatCount(sum.partial)}</small>}
                </div>
                <div className={`usage-kpi${incomplete ? ' usage-kpi--attention' : ''}`}>
                    <span className="usage-kpi__label">Известная стоимость</span>
                    <strong className="usage-kpi__value">{cost}</strong>
                    <small className="usage-kpi__hint">{!sum.rowCount ? 'Нет данных отчёта' : incomplete
                        ? 'Не все цены известны. Ноль не означает бесплатно.'
                        : 'Расчётная, не счёт провайдера.'}</small>
                </div>
                <div className={`usage-kpi${sum.completeRows !== sum.rowCount ? ' usage-kpi--attention' : ''}`}>
                    <span className="usage-kpi__label">Полнота учёта</span>
                    <strong className="usage-kpi__value">{sum.rowCount ? `${Math.floor(sum.completeRows * 100 / sum.rowCount)}%` : '—'}</strong>
                    <small className="usage-kpi__hint">{sum.rowCount
                        ? `${sum.completeRows} из ${sum.rowCount} строк с полным учётом токенов и цены`
                        : 'Нет данных отчёта'}</small>
                </div>
            </div>
            {(paged || mock) && <p className="usage-kpi-context">
                {paged && 'Итоги текущей страницы. Остальные страницы не включены.'}
                {paged && mock && ' · '}
                {mock && 'mock-safeai — тестовая модель.'}
            </p>}
        </section>
    )
}
