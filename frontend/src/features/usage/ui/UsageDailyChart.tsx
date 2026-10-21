// frontend/src/features/usage/ui/UsageDailyChart.tsx
import { useMemo, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { UsageDailySummary } from '../api/usageApi'
import { formatIsoDate } from '../lib/adminUsagePresentation'

type Metric = 'tokens' | 'total' | 'cost'

function chartNumber(value: string): number | null {
    const number = Number(value)
    // Recharts uses IEEE-754. Never draw a false precise value beyond its range.
    return Number.isFinite(number) && number >= 0 && number <= Number.MAX_SAFE_INTEGER
        ? number : null
}

export function UsageDailyChart({ rows }: { rows: UsageDailySummary[] }) {
    const [metric, setMetric] = useState<Metric>('tokens')
    const sorted = useMemo(() => [...rows].sort((a, b) => a.usageDate.localeCompare(b.usageDate)), [rows])
    const valid = sorted.every(row => [row.inputTokens, row.outputTokens, row.totalTokens]
        .every(value => chartNumber(value) !== null))
    const allCostsKnown = sorted.every(row => row.costUsd !== null)
    const data = sorted.map(row => ({
        date: formatIsoDate(row.usageDate),
        input: chartNumber(row.inputTokens),
        output: chartNumber(row.outputTokens),
        total: chartNumber(row.totalTokens),
        cost: row.costUsd === null ? null : chartNumber(row.costUsd),
        exactCost: row.costUsd,
    }))
    const costCanRender = data.every(row => row.cost !== null || row.exactCost === null)
    const unavailable = !valid || (metric === 'cost' && !costCanRender)
    return (
        <section className="usage-chart-card" aria-label="График использования AI по дням">
            <div className="usage-chart-heading">
                <div>
                    <h3>Динамика по дням UTC</h3>
                    <p>График дополняет таблицу. Значения по календарным дням не изменяются.</p>
                </div>
                <label className="usage-chart-select">
                    Показатель
                    <select value={metric} onChange={event => setMetric(event.target.value as Metric)}>
                        <option value="tokens">Входные / выходные токены</option>
                        <option value="total">Всего подтвержденных токенов</option>
                        <option value="cost">Известная стоимость, USD</option>
                    </select>
                </label>
            </div>
            {unavailable ? (
                <p role="status">Значения слишком велики для точного изображения на графике. Все исходные числа сохранены в таблице и CSV без округления.</p>
            ) : (
                <div className="usage-chart-viewport">
                    <ResponsiveContainer width="100%" height="100%">
                        <BarChart data={data} margin={{top: 8, right: 18, bottom: 4, left: 0}} barGap={3}>
                            <CartesianGrid strokeDasharray="3 3" vertical={false} />
                            <XAxis dataKey="date" tick={{fontSize: 11}} />
                            <YAxis width={64} tick={{fontSize: 11}} />
                            <Tooltip />
                            <Legend />
                            {metric === 'tokens' && <>
                                <Bar dataKey="input" name="Входные токены" fill="#4d92ef" radius={[5, 5, 0, 0]} isAnimationActive={false} />
                                <Bar dataKey="output" name="Выходные токены" fill="#57bc7b" radius={[5, 5, 0, 0]} isAnimationActive={false} />
                            </>}
                            {metric === 'total' && <Bar dataKey="total" name="Всего токенов" fill="#4d92ef" radius={[5, 5, 0, 0]} isAnimationActive={false} />}
                            {metric === 'cost' && <Bar dataKey="cost" name="Известная стоимость, USD" fill="#57bc7b" radius={[5, 5, 0, 0]} isAnimationActive={false} />}
                        </BarChart>
                    </ResponsiveContainer>
                </div>
            )}
            <p className="usage-chart-note">
                {metric === 'cost' && !allCostsKnown
                    ? 'Часть дней не имеет известной стоимости; отсутствие столбца не означает 0 USD.'
                    : 'На графике представлены те же строки, что и в дневной таблице.'}
                {' '}Ось и подсказки графика визуализируют числа приближенно; точные значения сохранены в таблице и CSV.
            </p>
        </section>
    )
}
