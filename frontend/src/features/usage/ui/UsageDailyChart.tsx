// frontend/src/features/usage/ui/UsageDailyChart.tsx
import { useMemo, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { UsageDailySummary } from '../api/usageApi'
import { trimDecimal } from '../lib/adminUsagePresentation'
import { buildDailySeries, type DailyGrouping } from './usageDailyChartData'
import { compactTick } from './usageBreakdownChartData'

export type UsageDailyMetric = 'tokens' | 'total' | 'cost' | 'responses' | 'coverage'

export function UsageDailyChart({ rows, dateFrom, dateTo, metric, onMetricChange, onInspectRange }: {
    rows: UsageDailySummary[]
    dateFrom?: string
    dateTo?: string
    metric: UsageDailyMetric
    onMetricChange: (value: UsageDailyMetric) => void
    onInspectRange?: (from: string, to: string) => void
}) {
    const [grouping, setGrouping] = useState<DailyGrouping>('day')
    const [zoom, setZoom] = useState(1)
    const data = useMemo(() => buildDailySeries(rows, dateFrom, dateTo, grouping), [rows, dateFrom, dateTo, grouping])
    const allCostsKnown = rows.every(row => row.costUsd !== null && row.coverage.pricingComplete === true)
    const unavailable = data.some(row => metric === 'cost' ? row.exactCost !== null && row.cost === null
        : metric === 'responses' ? row.exactResponses !== null && row.responses === null
        : metric === 'coverage' ? false
        : metric === 'total' ? row.exactTotal !== null && row.total === null
        : (row.exactInput !== null && row.input === null) || (row.exactOutput !== null && row.output === null))
    const inspect = (index: number) => {
        const entry = data[index]
        if (entry) onInspectRange?.(entry.start, entry.end)
    }
    return (
        <section className="usage-chart-card" aria-label="График использования AI по дням">
            <div className="usage-chart-heading">
                <div>
                    <h3>Динамика {grouping === 'day' ? 'по дням' : grouping === 'week' ? 'по неделям' : 'по месяцам'} UTC</h3>
                    <p>Расход по календарным дням UTC. Дни без записей оставлены пустыми.</p>
                </div>
                <div className="usage-chart-actions">
                <label className="usage-chart-select">
                    Показатель
                    <select value={metric} onChange={event => onMetricChange(event.target.value as UsageDailyMetric)}>
                        <option value="tokens">Входные / выходные токены</option>
                        <option value="total">Всего подтвержденных токенов</option>
                        <option value="cost">Известная стоимость, USD</option>
                        <option value="responses">Ответы AI</option>
                        <option value="coverage">Полнота учёта, %</option>
                    </select>
                </label>
                <label className="usage-chart-select">Группировка
                    <select value={grouping} onChange={event => setGrouping(event.target.value as DailyGrouping)}>
                        <option value="day">Дни</option><option value="week">Недели</option><option value="month">Месяцы</option>
                    </select>
                </label>
                <label className="usage-chart-select">Масштаб времени
                    <select value={zoom} onChange={event => setZoom(Number(event.target.value))}>
                        <option value={1}>1×</option><option value={2}>2×</option><option value={4}>4×</option>
                    </select>
                </label>
                </div>
            </div>
            <div id="usage-daily-chart-content">
            {unavailable ? (
                <p role="status">Значения слишком велики для точного изображения на графике. Все исходные числа сохранены в таблице и CSV без округления.</p>
            ) : (
                <div className="usage-daily-scroll"><div className="usage-chart-viewport" style={{minWidth: Math.max(360, data.length * 42 * zoom)}}>
                    <ResponsiveContainer width="100%" height="100%">
                        <BarChart data={data} margin={{top: 14, right: 18, bottom: 4, left: 0}}
                            barGap={4} maxBarSize={24}>
                            <CartesianGrid stroke="#dce5f2" strokeDasharray="3 3" vertical={false} />
                            <XAxis dataKey="date" tick={{fontSize: 14, fill: '#405572',
                                className: onInspectRange ? 'usage-chart-action-tick' : undefined}} minTickGap={28}
                                onClick={onInspectRange ? event => { const index = data.findIndex(row => row.date === event.value); if (index >= 0) inspect(index) } : undefined} />
                            <YAxis width={82} tick={{fontSize: 14, fill: '#405572'}} tickFormatter={compactTick}
                                domain={metric === 'coverage' ? [0, 100] : [0, 'auto']} allowDecimals={metric === 'cost'} />
                            <Tooltip cursor={{fill: '#eef3ff'}}
                                contentStyle={{borderRadius: 10, borderColor: '#dbe5f4'}}
                                formatter={(value, name, item) => [
                                    metric === 'cost' && typeof item.payload?.exactCost === 'string'
                                        ? trimDecimal(item.payload.exactCost) : value, name
                                ]} />
                            <Legend />
                            {metric === 'tokens' && <>
                                <Bar dataKey="input" name="Входные токены" onClick={(_, i) => inspect(i)} cursor={onInspectRange ? 'pointer' : undefined} fill="#356de8" radius={[5, 5, 0, 0]} isAnimationActive={false} />
                                <Bar dataKey="output" name="Выходные токены" onClick={(_, i) => inspect(i)} cursor={onInspectRange ? 'pointer' : undefined} fill="#169f8d" radius={[5, 5, 0, 0]} isAnimationActive={false} />
                            </>}
                            {metric === 'total' && <Bar dataKey="total" name="Всего токенов" onClick={(_, i) => inspect(i)} cursor={onInspectRange ? 'pointer' : undefined} fill="#356de8" radius={[5, 5, 0, 0]} isAnimationActive={false} />}
                            {metric === 'cost' && <Bar dataKey="cost" name="Известная стоимость, USD" onClick={(_, i) => inspect(i)} cursor={onInspectRange ? 'pointer' : undefined} fill="#7955d7" radius={[5, 5, 0, 0]} isAnimationActive={false} />}
                            {metric === 'responses' && <Bar dataKey="responses" name="Ответы AI" onClick={(_, i) => inspect(i)} fill="#356de8" isAnimationActive={false} />}
                            {metric === 'coverage' && <Bar dataKey="coverage" name="Полнота учёта, %" onClick={(_, i) => inspect(i)} fill="#169f8d" isAnimationActive={false} />}
                        </BarChart>
                    </ResponsiveContainer>
                </div></div>
            )}
            <p className="usage-chart-note">
                {metric === 'cost' && !allCostsKnown
                    ? 'Не все цены известны. Нулевой известный итог не означает бесплатное использование.'
                    : 'Значения взяты из дневного отчёта.'}
                {' '}Точные значения доступны в таблице и CSV.
            </p>
            {onInspectRange && <details className="usage-chart-links"><summary>Открыть период · доступно с клавиатуры</summary>
                <div>{data.map((entry, index) => <button type="button" key={entry.start} className="usage-chart-choice" onClick={() => inspect(index)}>{entry.date} · {entry.records} из {entry.days} дней с записями</button>)}</div>
            </details>}
            </div>
        </section>
    )
}
