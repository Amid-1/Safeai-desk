// frontend/src/features/usage/ui/UsageKpis.tsx
import type { UsageAmounts } from '../api/usageApi'
import type { UsageReportTab } from '../lib/adminUsageFilterState'
import { formatCount, trimDecimal } from '../lib/adminUsagePresentation'
import { summarizeUsageRows } from './usageMetrics'

export function UsageKpis({rows, tab}: {
    rows: UsageAmounts[]
    tab: UsageReportTab
}) {
    const sum = summarizeUsageRows(rows)
    const partial = BigInt(sum.partial) > 0n
    const cost = sum.pricedRows === 0 && sum.unknownCostRows > 0
        ? '—'
        : sum.knownCost === null
            ? 'Разные валюты'
            : `${trimDecimal(sum.knownCost)} ${sum.currency ?? 'USD'}`
    const scope = tab === 'users' || tab === 'summary'
        ? 'Итог только загруженной страницы; не всей организации.'
        : 'Итог всех строк показанного ограниченного отчета.'
    return (
        <section aria-label="Ключевые показатели загруженного отчета">
            <div className="usage-kpis">
                <div className="usage-kpi">
                    <span className="usage-kpi__label">Входные токены</span>
                    <strong className="usage-kpi__value">{formatCount(sum.input)}</strong>
                    <small className="usage-kpi__hint">Подтвержденный учет</small>
                </div>
                <div className="usage-kpi">
                    <span className="usage-kpi__label">Выходные токены</span>
                    <strong className="usage-kpi__value">{formatCount(sum.output)}</strong>
                    <small className="usage-kpi__hint">Подтвержденный учет</small>
                </div>
                <div className="usage-kpi">
                    <span className="usage-kpi__label">Всего токенов</span>
                    <strong className="usage-kpi__value">{formatCount(sum.total)}</strong>
                    <small className="usage-kpi__hint">{partial
                        ? `Отдельно известно из частичных ответов: ${formatCount(sum.partial)}`
                        : 'Частичные ответы не прибавляются к подтвержденному итогу'}</small>
                </div>
                <div className={`usage-kpi${sum.unknownCostRows > 0 ? ' usage-kpi--attention' : ''}`}>
                    <span className="usage-kpi__label">Известная стоимость</span>
                    <strong className="usage-kpi__value">{cost}</strong>
                    <small className="usage-kpi__hint">
                        {sum.unknownCostRows > 0
                            ? `Неизвестная стоимость: ${sum.unknownCostRows} строк. Это не общие расходы.`
                            : 'Стоимость известных строк; не счет внешнего провайдера.'}
                    </small>
                </div>
                <div className={`usage-kpi${sum.completeRows !== sum.rowCount ? ' usage-kpi--attention' : ''}`}>
                    <span className="usage-kpi__label">Качество данных</span>
                    <strong className="usage-kpi__value">{sum.completeRows} / {sum.rowCount}</strong>
                    <small className="usage-kpi__hint">
                        Строк с подтвержденной полнотой токенов и тарификации.
                        Не количество AI-запросов.
                    </small>
                </div>
            </div>
            <p className="usage-chart-note">{scope}</p>
            {rows.some(row => 'model' in row && row.model === 'mock-safeai') && (
                <p className="usage-chart-note">
                    mock-safeai — тестовая Runtime-модель. Рассчитанная стоимость не является счетом внешнего AI-провайдера.
                    Нулевая известная стоимость не означает отсутствие данных о цене.
                </p>
            )}
        </section>
    )
}
