// frontend/src/features/usage/ui/UsageDailyChart.tsx
import { useMemo } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { UsageDailySummary } from '../api/usageApi'
import { trimDecimal } from '../lib/adminUsagePresentation'
import { buildDailySeries, chartNumber } from './usageDailyChartData'

export type UsageDailyMetric = 'tokens' | 'total' | 'cost'

export function UsageDailyChart({ rows, dateFrom, dateTo, metric, onMetricChange }: {
    rows: UsageDailySummary[]
    dateFrom?: string
    dateTo?: string
    metric: UsageDailyMetric
    onMetricChange: (value: UsageDailyMetric) => void
}) {
    const data = useMemo(() => buildDailySeries(rows, dateFrom, dateTo), [rows, dateFrom, dateTo])
    const allCostsKnown = rows.every(row => row.costUsd !== null && row.coverage.pricingComplete === true)
    const unavailable = metric === 'cost'
        ? rows.some(row => row.costUsd !== null && chartNumber(row.costUsd) === null)
        : rows.some(row => (metric === 'total' ? [row.totalTokens] : [row.inputTokens, row.outputTokens])
            .some(value => chartNumber(value) === null))
    return (
        <section className="usage-chart-card" aria-label="График использования AI по дням">
            <div className="usage-chart-heading">
                <div>
                    <h3>Динамика по дням UTC</h3>
                    <p>Расход по календарным дням UTC. Дни без записей оставлены пустыми.</p>
                </div>
                <div className="usage-chart-actions">
                <label className="usage-chart-select">
                    Показатель
                    <select value={metric} onChange={event => onMetricChange(event.target.value as UsageDailyMetric)}>
                        <option value="tokens">Входные / выходные токены</option>
                        <option value="total">Всего подтвержденных токенов</option>
                        <option value="cost">Известная стоимость, USD</option>
                    </select>
                </label>
                </div>
            </div>
            <div id="usage-daily-chart-content">
            {unavailable ? (
                <p role="status">Значения слишком велики для точного изображения на графике. Все исходные числа сохранены в таблице и CSV без округления.</p>
            ) : (
                <div className="usage-chart-viewport">
                    <ResponsiveContainer width="100%" height="100%">
                        <BarChart data={data} margin={{top: 14, right: 18, bottom: 4, left: 0}}
                            barGap={4} maxBarSize={24}>
                            <CartesianGrid stroke="#dce5f2" strokeDasharray="3 3" vertical={false} />
                            <XAxis dataKey="date" tick={{fontSize: 14, fill: '#405572'}} minTickGap={28} />
                            <YAxis width={72} tick={{fontSize: 14, fill: '#405572'}} allowDecimals={metric === 'cost'} />
                            <Tooltip cursor={{fill: '#eef3ff'}}
                                contentStyle={{borderRadius: 10, borderColor: '#dbe5f4'}}
                                formatter={(value, name, item) => [
                                    metric === 'cost' && typeof item.payload?.exactCost === 'string'
                                        ? trimDecimal(item.payload.exactCost) : value, name
                                ]} />
                            <Legend />
                            {metric === 'tokens' && <>
                                <Bar dataKey="input" name="Входные токены" fill="#356de8" radius={[5, 5, 0, 0]} isAnimationActive={false} />
                                <Bar dataKey="output" name="Выходные токены" fill="#169f8d" radius={[5, 5, 0, 0]} isAnimationActive={false} />
                            </>}
                            {metric === 'total' && <Bar dataKey="total" name="Всего токенов" fill="#356de8" radius={[5, 5, 0, 0]} isAnimationActive={false} />}
                            {metric === 'cost' && <Bar dataKey="cost" name="Известная стоимость, USD" fill="#7955d7" radius={[5, 5, 0, 0]} isAnimationActive={false} />}
                        </BarChart>
                    </ResponsiveContainer>
                </div>
            )}
            <p className="usage-chart-note">
                {metric === 'cost' && !allCostsKnown
                    ? 'Не все цены известны. Нулевой известный итог не означает бесплатное использование.'
                    : 'Значения взяты из дневного отчёта.'}
                {' '}Точные значения доступны в таблице и CSV.
            </p>
            </div>
        </section>
    )
}
