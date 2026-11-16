// frontend/src/features/usage/ui/UsageBreakdownChart.tsx
import { useMemo } from 'react'
import {
    Bar,
    BarChart,
    CartesianGrid,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts'
import type { UsageAmounts } from '../api/usageApi'
import {
    buildUsageBreakdownChartData,
} from './usageBreakdownChartData'
import type { BreakdownReportTab } from './usageBreakdownChartData'

const TITLES: Record<BreakdownReportTab, string> = {
    summary: 'Расход токенов по пользователям и моделям',
    users: 'Расход токенов по пользователям',
    models: 'Расход токенов по моделям',
}

type Props = {
    tab: BreakdownReportTab
    rows: readonly UsageAmounts[]
    hasMore?: boolean
}

/** One chart from the report's existing rows; never an unscoped API request. */
export function UsageBreakdownChart({ tab, rows, hasMore = tab !== 'models' }: Props) {
    const result = useMemo(
        () => buildUsageBreakdownChartData(tab, rows),
        [tab, rows],
    )
    const label = TITLES[tab]
    const height = Math.max(180, 44 + result.shownRows * 58)

    return (
        <section className="usage-breakdown-chart" aria-label={label}>
            <div className="usage-breakdown-chart__top">
                <div>
                    <h3>{label}</h3>
                    <p>
                        Подтверждённые входные и выходные токены.
                        {hasMore
                            ? ' Только на текущей странице отчёта.'
                            : ' По выбранным фильтрам.'}
                    </p>
                </div>
            </div>
                <div id="usage-breakdown-chart-content">
                    {!result.available ? (
                        <p role="status" className="usage-breakdown-chart__warning">
                            Некоторые значения слишком велики для точного графика.
                            Все исходные числа доступны в таблице и CSV.
                        </p>
                    ) : (
                        <>
                            <div className="usage-breakdown-chart__plot" role="group"
                                 aria-label={`${label}. Данные подтвержденных токенов текущего отчета.`}>
                                <div className="usage-breakdown-chart__frame" style={{ minHeight: height }}>
                                    <ResponsiveContainer width="100%" height="100%">
                                        <BarChart
                                            data={result.data}
                                            layout="vertical"
                                            margin={{ top: 4, right: 20, bottom: 3, left: 4 }}
                                            barGap={2}
                                            barCategoryGap="23%"
                                            maxBarSize={28}
                                        >
                                            <CartesianGrid stroke="#dce5f2" strokeDasharray="3 3" horizontal={false}/>
                                            <XAxis type="number" allowDecimals={false} tick={{fontSize: 14, fill: '#405572'}}/>
                                            <YAxis type="category" dataKey="label"
                                                   width={264} tick={{ fontSize: 14, fill: '#405572' }}
                                                   tickFormatter={(value: string) => value.length > 32
                                                       ? `${value.slice(0, 31)}…` : value}/>
                                            <Tooltip cursor={{fill: '#eef3ff'}}
                                                     contentStyle={{borderRadius: 10, borderColor: '#dbe5f4'}}/>
                                            <Bar dataKey="input" name="Входные токены"
                                                 fill="#356de8" radius={[0, 5, 5, 0]} isAnimationActive={false}/>
                                            <Bar dataKey="output" name="Выходные токены"
                                                 fill="#169f8d" radius={[0, 5, 5, 0]} isAnimationActive={false}/>
                                        </BarChart>
                                    </ResponsiveContainer>
                                </div>
                            </div>
                            <div className="usage-breakdown-chart__legend" aria-hidden="true">
                                <span>Входные токены</span>
                                <span>Выходные токены</span>
                            </div>
                            {result.totalRows > result.shownRows && (
                                <p className="usage-breakdown-chart__warning">
                                    Показаны первые {result.shownRows} по расходу из {result.totalRows}
                                    {' '}загруженных строк. Все строки доступны в таблице.
                                </p>
                            )}
                        </>
                    )}
                </div>
        </section>
    )
}
