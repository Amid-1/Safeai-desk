// frontend/src/features/usage/ui/PlatformDashboard.tsx
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { getOrganizations } from '../../../entities/organization/api/organizationApi'
import { getUserStatistics } from '../../../entities/user/api/userApi'
import { getRuntimeModelStatus } from '../../../entities/model/api/modelApi'
import { createRecentDateRange } from '../../../shared/lib/date'
import { ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import { AdminPageHeader } from '../../../shared/ui/AdminPageHeader'
import { getUsageByOrganizations, getUsageDaily, getOrganizationUsageDaily } from '../api/usageApi'
import { getPlatformBudgetSummary } from '../api/platformBudgetApi'
import { useDashboardResource } from '../controller/useDashboardResource'
import { toUsageFilter } from '../lib/adminUsageFilterState'
import { formatCount, trimDecimal } from '../lib/adminUsagePresentation'
import { UsageDailyChart, type UsageDailyMetric } from './UsageDailyChart'
import { UsageKpis } from './UsageKpis'
import { BUDGET_CATEGORIES } from '../lib/usageBudgetStatus'

const loadUsers = (signal: AbortSignal) => getUserStatistics({signal, directory: true})
const loadOrganizations = (signal: AbortSignal) => getOrganizations(0, 1, {signal})
const loadRuntime = (signal: AbortSignal) => getRuntimeModelStatus(signal)
const dateLabel = (value: string) => value.slice(0, 10).split('-').reverse().join('.')

function Panel({title, failed, loading, retry, children}: {
    title: string; failed: boolean; loading: boolean; retry: () => void; children: ReactNode
}) {
    return <section className="platform-dashboard__panel" aria-label={title}>
        <h2>{title}</h2>
        {loading ? <LoadingState variant="inline" /> : failed
            ? <ErrorState variant="inline" message="Данные недоступны. Повторите загрузку."
                action={<button className="secondary-button" onClick={retry}>Повторить</button>} />
            : children}
    </section>
}

export function PlatformDashboard() {
    const [days, setDays] = useState(30)
    const [refresh, setRefresh] = useState(0)
    const [periodRevision, setPeriodRevision] = useState(0)
    const [now, setNow] = useState(() => new Date())
    const [metric, setMetric] = useState<UsageDailyMetric>('tokens')
    const [draft, setDraft] = useState('')
    const [search, setSearch] = useState('')
    const [searchSession, setSearchSession] = useState(false)
    const [page, setPage] = useState(0)
    const [scope, setScope] = useState<{id: string; name: string} | null>(null)
    const analyticsHeading = useRef<HTMLHeadingElement>(null)
    const resultsViewport = useRef<HTMLDivElement>(null)
    function showOrganization(id: string, name: string) {
        setScope(previous => previous?.id === id ? previous : {id, name})
        analyticsHeading.current?.focus({preventScroll: true})
        analyticsHeading.current?.scrollIntoView?.({
            block: 'start', behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
        })
    }
    useEffect(() => {
        const timeout = window.setTimeout(() => {setSearch(draft.trim()); setPage(0)}, 300)
        return () => window.clearTimeout(timeout)
    }, [draft])
    const navigate = useNavigate()
    const range = useMemo(() => createRecentDateRange(days, 'UTC', now), [days, now])
    const filter = useMemo(() => toUsageFilter({...range, model: '', organizationId: ''}, true).filter, [range])
    const loadDaily = useCallback((signal: AbortSignal) => scope
        ? getOrganizationUsageDaily(scope.id, filter, {signal})
        : getUsageDaily(filter, {signal}), [filter, scope])
    const loadTop = useCallback(async (signal: AbortSignal) => {
        const size = search ? 10 : 5
        const [result, matched] = await Promise.all([
            getUsageByOrganizations(page, size, {...filter, order: 'total', organizationQuery: search || undefined,
                usedOnly: search ? undefined : true}, {signal}),
            search ? getOrganizations(0, 1, {query: search, signal}) : Promise.resolve(null),
        ])
        if (result.page !== page || result.size !== size || result.content.length > size) throw new Error('Некорректная страница отчёта.')
        return {...result, matched: matched?.totalElements}
    }, [filter, search, page])
    const key = String(refresh)
    const periodKey = key + ':' + periodRevision
    const users = useDashboardResource(key, loadUsers)
    const organizations = useDashboardResource(key, loadOrganizations)
    const runtime = useDashboardResource(key, loadRuntime)
    const budget = useDashboardResource(key, getPlatformBudgetSummary)
    const daily = useDashboardResource(periodKey + ':' + (scope?.id ?? 'all'), loadDaily)
    const top = useDashboardResource(periodKey + ':' + search + ':' + page, loadTop)
    const searching = draft.trim() !== search
    const resultsLoading = searching || top.loading
    // Expand immediately on input and collapse only once the default ranking settles.
    const searchExpanded = Boolean(draft.trim() || search || (searchSession && top.loading))
    useEffect(() => {
        if (searchSession && !searchExpanded) setSearchSession(false)
    }, [searchSession, searchExpanded])
    useEffect(() => {
        if (resultsViewport.current) resultsViewport.current.scrollTop = 0
    }, [search, page, periodKey])
    const rows = top.data?.content.filter(row => search || BigInt(row.totalTokens) > 0n) ?? []
    const responses = daily.data?.every(row => row.coverage.assistantMessages !== null)
        ? daily.data.reduce((sum, row) => sum + BigInt(row.coverage.assistantMessages ?? '0'), 0n).toString() : null
    const usageLink = (report: string, extra: Record<string, string> = {}) =>
        '/admin/usage?' + new URLSearchParams({report, ...range, ...extra})
    return <div className="page platform-dashboard">
        <AdminPageHeader className="platform-dashboard__header">
            <div><h1>Обзор платформы</h1>
                <p>Сводка по всем организациям · только для суперадминистратора.</p></div>
            <button className="secondary-button" onClick={() => {setNow(new Date()); setRefresh(v => v + 1)}}>Обновить данные</button>
        </AdminPageHeader>
        <div className="platform-dashboard__grid">
            <Panel title="Организации" {...organizations}>
                <strong className="platform-dashboard__number">{(budget.data?.total ?? organizations.data?.totalElements)?.toLocaleString('ru-RU')}</strong>
                <p>{budget.data ? `Включены: ${budget.data.enabled} · Отключены: ${budget.data.total - budget.data.enabled}` : 'Всего в каталоге, включая отключённые.'}</p><Link to="/admin/organizations">Каталог организаций</Link>
            </Panel>
            <Panel title="Пользователи" {...users}>
                <strong className="platform-dashboard__number">{users.data?.total.toLocaleString('ru-RU')}</strong>
                <p>Включены: {users.data?.enabled} · Отключены: {users.data?.disabled}</p><Link to="/admin/users">Каталог пользователей</Link>
            </Panel>
            <Panel title="Подключённая модель" {...runtime}>
                <strong className="platform-dashboard__model">{runtime.data?.model}</strong>
                <p>{runtime.data?.enabled ? 'Включена в конфигурации' : 'Отключена'} · {runtime.data?.provider}
                    {runtime.data?.provider === 'mock' && <> <span className="platform-dashboard__badge">Тестовая модель</span></>}</p>
                <p>Связь с провайдером здесь не проверяется.</p><Link to="/admin/models?section=diagnostics">Диагностика моделей</Link>
            </Panel>
        </div>
        <Panel title="Контроль бюджетов организаций" {...budget}>
            {budget.data && <>
                <p>Текущий календарный месяц (UTC) · {dateLabel(budget.data.periodStart)} — до {dateLabel(budget.data.periodEnd)} (не включительно). Все организации, включая отключённые.</p>
                <div className="platform-dashboard__budget-sections">
                    {([
                        {title: 'Состояние бюджетов', items: BUDGET_CATEGORIES.filter(item =>
                            ['WITHIN', 'NEAR', 'EXCEEDED', 'UNKNOWN'].includes(item.status))},
                        {title: 'Настройка правил', items: BUDGET_CATEGORIES.filter(item =>
                            ['NO_LIMIT', 'DISABLED', 'UNCONFIGURED'].includes(item.status))},
                    ] as const).map(group => <section key={group.title} aria-label={group.title}>
                        <h3>{group.title}</h3>
                        <div className="platform-dashboard__budget-grid">
                            {group.items.map(({status, key, label, tone, description}) => <Link key={status}
                                to={usageLink('organizations', {budgetStatus: status})}
                                title={description}
                                aria-label={label + ': ' + budget.data?.[key] + '. Показать организации'}
                                className={`platform-dashboard__budget-item platform-dashboard__budget-item--${tone}`}>
                                <span>{label}</span>
                                <span className="platform-dashboard__budget-value">
                                    <strong>{budget.data?.[key].toLocaleString('ru-RU')}</strong>
                                    <span aria-hidden="true">↗</span>
                                </span>
                            </Link>)}
                        </div>
                    </section>)}
                </div>
                <p>Учитываются известные расходы и действующие резервы. Неизвестные затраты не считаются нулевыми; подтверждённое превышение показывается даже при неполных данных.</p>
                <Link to={usageLink('organizations')}>Посмотреть бюджеты организаций</Link>
            </>}
        </Panel>
        <section className={'platform-dashboard__panel'
            + (daily.data && daily.data.length <= 3 ? ' platform-dashboard__panel--sparse-usage' : '')}
            aria-label="Период аналитики">
            <div className="platform-dashboard__toolbar"><div><h2 ref={analyticsHeading} tabIndex={-1} className="platform-dashboard__analytics-heading">Использование AI</h2>
                <p>{dateLabel(range.dateFrom)} — {dateLabel(range.dateTo)} · UTC</p>
                <p className="platform-dashboard__scope">Область отчёта: <strong>{scope?.name ?? 'Все организации'}</strong>
                    {scope && <button type="button" className="secondary-button" onClick={() => setScope(null)}>Все организации</button>}</p>
                {daily.loadedAt && <small>Аналитика обновлена: {daily.loadedAt.toLocaleString('ru-RU', {timeZone: 'UTC'})} UTC</small>}</div>
                <div className="platform-dashboard__analytics-actions"><div className="platform-dashboard__period"><label htmlFor="platform-dashboard-period">Период</label><select id="platform-dashboard-period" value={days} onChange={e => {setDays(Number(e.target.value)); setNow(new Date()); setPage(0); setPeriodRevision(v => v + 1)}}>
                    {[7, 30, 90, 365].map(n => <option key={n} value={n}>{n} дней</option>)}
                </select></div><Link to={usageLink('daily', scope ? {organizationId: scope.id} : {})}>Полный отчёт</Link></div></div>
            {daily.loading ? <LoadingState variant="inline" /> : daily.failed
                ? <ErrorState variant="inline" message="Дневные агрегаты недоступны."
                    action={<button onClick={daily.retry}>Повторить</button>} />
                : daily.data && <>
                    <UsageKpis rows={daily.data} tab="daily" dailyTotals hasMore={false}
                        assistantResponses={daily.data.length ? responses : null} />
                    <p className="platform-dashboard__accounting-note">
                        Полнота учёта относится к сохранённым записям и известным ценам,
                        а не к доступности провайдера или готовности финансовой интеграции.
                        {runtime.data?.provider === 'mock' && ' Сейчас используется тестовая модель.'}
                    </p>
                    <UsageDailyChart rows={daily.data} {...range} metric={metric} onMetricChange={setMetric} compactPeriodPicker expandable
                        onInspectRange={(dateFrom, dateTo) => navigate(usageLink('summary', {dateFrom, dateTo, ...(scope ? {organizationId: scope.id} : {})}))} />
                </>}
        </section>
        <section className={'platform-dashboard__panel' + (searchExpanded ? ' platform-dashboard__panel--search-active' : '')} aria-label="Организации по подтверждённым токенам">
            <h2>Организации по использованию AI</h2>
            <div className="platform-dashboard__search" role="search">
                <label htmlFor="dashboard-org-search">Поиск организаций</label>
                <input id="dashboard-org-search" type="search" maxLength={255} placeholder="Название организации" value={draft} onChange={e => {
                    const next = e.target.value
                    setDraft(next)
                    if (next.trim()) setSearchSession(true)
                }} />
                <button type="button" className="secondary-button" onClick={() => {setDraft(''); setSearch(''); setPage(0)}}>Сбросить</button>
            </div>
            <p>Поиск по всему каталогу, включая организации без использования. Без запроса — до 5 организаций с подтверждёнными токенами за выбранный период.</p>
            <p className="platform-dashboard__result-status" role="status">
                {resultsLoading || top.failed ? 'Найдено: —' : search ? 'Найдено: ' + (top.data?.matched ?? '—') : 'Показано: ' + rows.length}
            </p>
            <div ref={resultsViewport} className="platform-dashboard__table-scroll" role="region" aria-label="Результаты поиска организаций" aria-busy={resultsLoading} tabIndex={0}><table><thead><tr>
                <th scope="col">Организация</th><th scope="col">Токены</th><th scope="col">Известная стоимость</th>
                <th scope="col">Аналитика</th>
            </tr></thead><tbody>{resultsLoading ? <tr><td colSpan={4}><LoadingState variant="inline" /></td></tr>
                : top.failed ? <tr><td colSpan={4}><ErrorState variant="inline" message="Рейтинг недоступен." action={<button onClick={top.retry}>Повторить</button>} /></td></tr>
                : rows.length === 0 ? <tr><td colSpan={4}>{search ? 'Организации не найдены.' : 'Нет организаций с подтверждёнными токенами.'}</td></tr>
                : rows.map(row => {
                const empty = row.coverage.assistantMessages === '0'
                return <tr key={row.organizationId} className={scope?.id === row.organizationId ? 'platform-dashboard__selected-row' : undefined}>
                    <td>{empty ? <Link to={'/admin/organizations?organizationId=' + encodeURIComponent(row.organizationId)}>{row.organizationName}</Link>
                        : <Link to={usageLink('summary', {organizationId: row.organizationId})}>{row.organizationName}</Link>}</td>
                    <td>{empty ? '—' : formatCount(row.totalTokens)}</td>
                    <td>{empty || row.costUsd === null ? '—' : trimDecimal(row.costUsd) + ' USD'}{!empty && row.coverage.pricingComplete !== true && <small> · Неполный учёт</small>}</td>
                    <td><button type="button" className="secondary-button" aria-pressed={scope?.id === row.organizationId}
                        onClick={() => showOrganization(row.organizationId, row.organizationName)}>Показать на графике</button></td>
                </tr>
            })}</tbody></table></div>
            <div className="platform-dashboard__search-pager" hidden={!searchExpanded}>
            {search && <>
                <button className="secondary-button" disabled={resultsLoading || top.failed || page === 0} onClick={() => setPage(v => v - 1)}>Назад</button>
                <span>Страница {page + 1}</span>
                <button className="secondary-button" disabled={resultsLoading || top.failed || page + 1 >= (top.data?.totalPages ?? 0)} onClick={() => setPage(v => v + 1)}>Далее</button>
            </>}
            </div>
            <Link to={usageLink('organizations')}>Сравнить все организации</Link>
        </section>
        <section className="platform-dashboard__panel" aria-label="Безопасность и управление">
            <h2>Безопасность и управление</h2>
            <div className="platform-dashboard__links">
                <Link to="/admin/audit">Журнал аудита</Link>
                <Link to="/admin/erasure">Управление данными</Link>
                <Link to="/admin/models?section=access">Правила доступа и бюджеты</Link>
            </div>
        </section>
    </div>
}
