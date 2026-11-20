// frontend/src/features/usage/ui/UsageBreakdownChart.tsx
import { useMemo, useState, useEffect, useRef } from 'react'
import type { CSSProperties } from 'react'
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
    buildUsageBreakdownChartData, compactTick, exactMetric, coveragePercent,
} from './usageBreakdownChartData'
import type { BreakdownReportTab } from './usageBreakdownChartData'
import type { RankingMetric } from './usageBreakdownChartData'
import { formatCount, trimDecimal } from '../lib/adminUsagePresentation'
import { UsageOrganizationBudget } from './UsageOrganizationRows'

const TITLES: Record<BreakdownReportTab, string> = {
    summary: 'Расход токенов по пользователям и моделям',
    users: 'Расход токенов по пользователям',
    models: 'Расход токенов по моделям',
    organizations: 'Расход токенов по организациям',
}

const METRICS: Record<RankingMetric, string> = {
    tokens: 'Входные / выходные токены', total: 'Всего токенов',
    cost: 'Известная стоимость, USD', responses: 'Попытки ответа AI', coverage: 'Полнота учёта, %',
}
type LinkedRow = UsageAmounts & {userId?: string; userEmail?: string; model?: string; organizationId?: string}

type Props = {
    tab: BreakdownReportTab
    rows: readonly UsageAmounts[]
    hasMore?: boolean
    limit?: number
    metric?: RankingMetric
    globallyRanked?: boolean
    serverSorted?: boolean
    onInspect?: (row: LinkedRow) => void
    relatedLinks?: boolean
    organizationId?: string
    reportIdentity?: string
    reloadToken?: number
}

/** One chart from the report's existing rows; never an unscoped API request. */
export function UsageBreakdownChart({ tab, rows, hasMore = tab !== 'models', limit = 12, metric = 'tokens',
    globallyRanked = false, serverSorted = false, onInspect, relatedLinks, organizationId, reportIdentity, reloadToken = 0 }: Props) {
    const result = useMemo(
        () => buildUsageBreakdownChartData(tab, rows, limit, metric, serverSorted),
        [tab, rows, limit, metric, serverSorted],
    )
    const label = TITLES[tab]
    const height = Math.max(180, 44 + result.shownRows * 58)
    const [selected, setSelected] = useState<UsageAmounts | null>(null)
    const selectionRef = useRef<HTMLDivElement>(null)
    const current = selected === null ? undefined : result.data.find(item => rows[item.sourceIndex] === selected)
    const row = current ? rows[current.sourceIndex] as LinkedRow : undefined
    const emptyOrganization = tab === 'organizations' && row?.coverage.assistantMessages === '0'
    useEffect(() => {
        if (row && tab !== 'organizations') selectionRef.current?.scrollIntoView?.({block: 'nearest'})
    }, [row, tab])
    const inspect = (sourceIndex: number) => {
        const target = rows[sourceIndex]
        if (!onInspect || !target) return
        if (tab === 'organizations') {
            setSelected(target)
            return
        }
        onInspect(target)
    }
    const selection = onInspect && row && current && <div ref={selectionRef}
        className={`usage-chart-selection usage-chart-links${tab === 'organizations' ? ' usage-chart-selection--organization' : ''}`}
        role="region" aria-label="Выбранная запись графика" tabIndex={tab === 'organizations' ? 0 : undefined}>
        <strong>{current.label}</strong>
        {emptyOrganization ? <span role="status">Нет использования за выбранный период. Бюджет не зависит от фильтров отчёта.</span> : <>
            <span>Вход: {formatCount(row.inputTokens)} · Выход: {formatCount(row.outputTokens)} · Всего: {formatCount(row.totalTokens)}</span>
            <span>Известная стоимость: {exactMetric(row, 'cost') === null ? '—' : trimDecimal(exactMetric(row, 'cost')!) + ' USD'} · Полнота учёта: {coveragePercent(row.coverage) === null ? '—' : coveragePercent(row.coverage) + '%'}</span>
            <button type="button" className="usage-chart-primary-action" onClick={() => onInspect(row)}>Детализация использования</button>
        </>}
        {relatedLinks && row.userId && <a href={'/admin/users?userId=' + encodeURIComponent(row.userId)}>Карточка пользователя</a>}
        {relatedLinks && row.model && <a href={'/admin/models?section=catalog&usageModel=' + encodeURIComponent(row.model)
            + (organizationId ? '&organizationId=' + encodeURIComponent(organizationId) : '')}>Карточка модели</a>}
        {relatedLinks && row.organizationId && <a href={'/admin/organizations?organizationId=' + encodeURIComponent(row.organizationId)}>Карточка организации</a>}
        <button type="button" className="secondary-button" onClick={() => setSelected(null)}>Закрыть сведения</button>
        {tab === 'organizations' && relatedLinks && row.organizationId && reportIdentity &&
            <UsageOrganizationBudget key={row.organizationId} id={row.organizationId} userId={reportIdentity} reloadToken={reloadToken}/>}
    </div>

    return (
        <section className={`usage-breakdown-chart${tab === 'organizations' ? ' usage-breakdown-chart--organizations' : ''}`} aria-label={label}>
            <div className="usage-breakdown-chart__top">
                <div>
                    <h3>{metric === 'tokens' ? label : METRICS[metric]}</h3>
                    <p>
                        {metric === 'tokens' ? 'Подтверждённые входные и выходные токены.' : METRICS[metric] + '.'}
                        {globallyRanked ? ' Серверный рейтинг по всему выбранному отчёту.' : hasMore
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
                            {tab === 'organizations' && selection}
                            <div className="usage-breakdown-chart__plot" role="group"
                                 aria-label={`${label}. Данные подтвержденных токенов текущего отчета.`}
                                 onMouseDownCapture={event => {
                                     if (tab !== 'organizations' || !onInspect || event.button !== 0 || !(event.target instanceof Element)) return
                                     const surface = event.target.closest<SVGSVGElement>('svg[role="application"]')
                                     if (!surface) return
                                     // Preserve the chart's keyboard focus without its native focus-scroll.
                                     event.preventDefault()
                                     surface.focus({preventScroll: true})
                                 }}>
                                <div className="usage-breakdown-chart__frame" style={{ '--usage-row-height': `${height}px` } as CSSProperties}>
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
                                            <XAxis type="number" allowDecimals={metric === 'cost'} tickFormatter={compactTick}
                                                domain={metric === 'coverage' ? [0, 100] : [0, 'auto']} tick={{fontSize: 14, fill: '#405572'}}/>
                                            <YAxis type="category" dataKey="sourceIndex"
                                                   width={264} tick={{ fontSize: 14, fill: '#405572',
                                                       className: onInspect ? 'usage-chart-action-tick' : undefined }}
                                                   tickFormatter={(value: number) => result.data.find(r => r.sourceIndex === value)?.shortLabel ?? ''}
                                                   onClick={onInspect ? event => { if (typeof event.value === 'number') inspect(event.value) } : undefined}/>
                                            <Tooltip cursor={{fill: '#eef3ff'}}
                                                     labelFormatter={value => result.data.find(r => r.sourceIndex === Number(value))?.label ?? ''}
                                                     formatter={(value, name, item) => [metric !== 'tokens' && typeof item.payload?.exactValue === 'string'
                                                         ? trimDecimal(item.payload.exactValue) : typeof value === 'number' ? formatCount(String(value)) : value, name]}
                                                     contentStyle={{borderRadius: 10, borderColor: '#dbe5f4'}}/>
                                            {metric === 'tokens' ? <>
                                                <Bar dataKey="input" name="Входные токены" onClick={(_, index) => inspect(result.data[index].sourceIndex)}
                                                     cursor={onInspect ? 'pointer' : undefined} fill="#356de8" radius={[0, 5, 5, 0]} isAnimationActive={false}/>
                                                <Bar dataKey="output" name="Выходные токены" onClick={(_, index) => inspect(result.data[index].sourceIndex)}
                                                     cursor={onInspect ? 'pointer' : undefined} fill="#169f8d" radius={[0, 5, 5, 0]} isAnimationActive={false}/>
                                            </> : <Bar dataKey="value" name={METRICS[metric]} onClick={(_, index) => inspect(result.data[index].sourceIndex)}
                                                     cursor={onInspect ? 'pointer' : undefined} fill="#7955d7" radius={[0, 5, 5, 0]} isAnimationActive={false}/>}
                                        </BarChart>
                                    </ResponsiveContainer>
                                </div>
                            </div>
                            {metric === 'tokens' && <div className="usage-breakdown-chart__legend" aria-hidden="true">
                                <span>Входные токены</span>
                                <span>Выходные токены</span>
                            </div>}
                            {onInspect && <details className="usage-chart-links">
                                <summary>Детализация и карточки · доступно с клавиатуры</summary>
                                <div>{result.data.map(item => <button type="button" key={item.sourceIndex} className="usage-chart-choice"
                                    aria-pressed={row === rows[item.sourceIndex]}
                                    onClick={() => setSelected(rows[item.sourceIndex])}>{item.label}</button>)}</div>
                            </details>}
                            {tab !== 'organizations' && selection}
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
