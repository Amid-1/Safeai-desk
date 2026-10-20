import { useMemo, useState } from 'react'
import {
    Bar,
    BarChart,
    CartesianGrid,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts'
import type { UsageAmounts } from '../../../api/usageApi'
import {
    buildUsageBreakdownChartData,
} from './usageBreakdownChartData'
import type { BreakdownReportTab } from './usageBreakdownChartData'

const TITLES: Record<BreakdownReportTab, string> = {
    summary: 'Расход токенов по сотрудникам и моделям',
    users: 'Расход токенов по пользователям',
    models: 'Расход токенов по моделям',
}

type Props = {
    tab: BreakdownReportTab
    rows: readonly UsageAmounts[]
}

/** One chart from the report's existing rows; never an unscoped API request. */
export function UsageBreakdownChart({ tab, rows }: Props) {
    const [expanded, setExpanded] = useState(true)
    const result = useMemo(
        () => buildUsageBreakdownChartData(tab, rows),
        [tab, rows],
    )
    const label = TITLES[tab]
    const height = Math.max(156, Math.min(620, 65 + result.shownRows * 52))

    return (
        <section className="usage-breakdown-chart" aria-label={label}>
            <div className="usage-breakdown-chart__top">
                <div>
                    <h3>{label}</h3>
                    <p>
                        Подтвержденные входные и выходные токены.
                        {tab === 'models'
                            ? ' По всем строкам ограниченного отчета.'
                            : ' Только по загруженной странице, не по всей организации.'}
                    </p>
                </div>
                <button
                    type="button"
                    aria-expanded={expanded}
                    aria-controls="usage-breakdown-chart-content"
                    onClick={() => setExpanded(value => !value)}
                >
                    {expanded ? 'Свернуть график' : 'Показать график'}
                </button>
            </div>
            {expanded && (
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
                                <div style={{ minWidth: 540, height }}>
                                    <ResponsiveContainer width="100%" height="100%">
                                        <BarChart
                                            data={result.data}
                                            layout="vertical"
                                            margin={{ top: 4, right: 20, bottom: 3, left: 4 }}
                                            barGap={2}
                                            barCategoryGap="23%"
                                        >
                                            <CartesianGrid strokeDasharray="3 3" horizontal={false}/>
                                            <XAxis type="number" allowDecimals={false}/>
                                            <YAxis type="category" dataKey="label"
                                                   width={182} tick={{ fontSize: 12 }}
                                                   tickFormatter={(value: string) => value.length > 25
                                                       ? `${value.slice(0, 24)}…` : value}/>
                                            <Tooltip/>
                                            <Bar dataKey="input" name="Входные токены"
                                                 fill="#4d92ef" radius={[0, 5, 5, 0]} isAnimationActive={false}/>
                                            <Bar dataKey="output" name="Выходные токены"
                                                 fill="#58bd7c" radius={[0, 5, 5, 0]} isAnimationActive={false}/>
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
            )}
        </section>
    )
}
