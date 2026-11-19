// frontend/src/features/usage/ui/PlatformDashboard.tsx
import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { getOrganizations } from '../../../entities/organization/api/organizationApi'
import { getUserStatistics } from '../../../entities/user/api/userApi'
import { getRuntimeModelStatus } from '../../../entities/model/api/modelApi'
import { createRecentDateRange } from '../../../shared/lib/date'
import { ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import { getUsageByOrganizations, getUsageDaily } from '../api/usageApi'
import { useDashboardResource } from '../controller/useDashboardResource'
import { toUsageFilter } from '../lib/adminUsageFilterState'
import { formatCount, trimDecimal } from '../lib/adminUsagePresentation'
import { UsageDailyChart, type UsageDailyMetric } from './UsageDailyChart'
import { UsageKpis } from './UsageKpis'

const loadUsers = (signal: AbortSignal) => getUserStatistics({signal, directory: true})
const loadOrganizations = (signal: AbortSignal) => getOrganizations(0, 1, {signal})
const loadRuntime = (signal: AbortSignal) => getRuntimeModelStatus(signal)

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
    const navigate = useNavigate()
    const range = useMemo(() => createRecentDateRange(days, 'UTC', now), [days, now])
    const filter = useMemo(() => toUsageFilter({...range, model: '', organizationId: ''}, true).filter, [range])
    const loadDaily = useCallback((signal: AbortSignal) => getUsageDaily(filter, {signal}), [filter])
    const loadTop = useCallback(async (signal: AbortSignal) => {
        const result = await getUsageByOrganizations(0, 5, {...filter, order: 'total'}, {signal})
        if (result.page !== 0 || result.size !== 5 || result.content.length > 5) throw new Error('Некорректная страница отчёта.')
        return result
    }, [filter])
    const key = String(refresh)
    const periodKey = key + ':' + periodRevision
    const users = useDashboardResource(key, loadUsers)
    const organizations = useDashboardResource(key, loadOrganizations)
    const runtime = useDashboardResource(key, loadRuntime)
    const daily = useDashboardResource(periodKey, loadDaily)
    const top = useDashboardResource(periodKey, loadTop)
    const usageLink = (report: string, extra: Record<string, string> = {}) =>
        '/admin/usage?' + new URLSearchParams({report, ...range, ...extra})
    return <div className="page platform-dashboard">
        <header className="platform-dashboard__header">
            <div><span className="usage-eyebrow">SAFEAI DESK · ПЛАТФОРМА</span><h1>Dashboard платформы</h1>
                <p>Сводка по всем организациям · только для супер администратора.</p></div>
            <button className="secondary-button" onClick={() => {setNow(new Date()); setRefresh(v => v + 1)}}>Обновить данные</button>
        </header>
        <div className="platform-dashboard__grid">
            <Panel title="Организации" {...organizations}>
                <strong className="platform-dashboard__number">{organizations.data?.totalElements.toLocaleString('ru-RU')}</strong>
                <p>Всего в каталоге, включая отключённые.</p><Link to="/admin/organizations">Каталог организаций</Link>
            </Panel>
            <Panel title="Пользователи" {...users}>
                <strong className="platform-dashboard__number">{users.data?.total.toLocaleString('ru-RU')}</strong>
                <p>Включены: {users.data?.enabled} · Отключены: {users.data?.disabled}</p><Link to="/admin/users">Каталог пользователей</Link>
            </Panel>
            <Panel title="Подключённая модель" {...runtime}>
                <strong className="platform-dashboard__model">{runtime.data?.model}</strong>
                <p>{runtime.data?.enabled ? 'Включена в конфигурации' : 'Отключена'} · {runtime.data?.provider}</p>
                <p>Связь с провайдером здесь не проверяется.</p><Link to="/admin/models?section=diagnostics">Диагностика моделей</Link>
            </Panel>
        </div>
        <section className="platform-dashboard__panel" aria-label="Период аналитики">
            <div className="platform-dashboard__toolbar"><div><h2>Использование AI</h2>
                <p>{range.dateFrom} — {range.dateTo} · UTC</p></div>
                <div className="platform-dashboard__period"><label htmlFor="platform-dashboard-period">Период</label><select id="platform-dashboard-period" value={days} onChange={e => {setDays(Number(e.target.value)); setNow(new Date()); setPeriodRevision(v => v + 1)}}>
                    {[7, 30, 90, 365].map(n => <option key={n} value={n}>{n} дней</option>)}
                </select></div><Link to={usageLink('daily')}>Полный отчёт</Link></div>
            {daily.loading ? <LoadingState variant="inline" /> : daily.failed
                ? <ErrorState variant="inline" message="Дневные агрегаты недоступны."
                    action={<button onClick={daily.retry}>Повторить</button>} />
                : daily.data && <>
                    <UsageKpis rows={daily.data} tab="daily" dailyTotals hasMore={false} />
                    <UsageDailyChart rows={daily.data} {...range} metric={metric} onMetricChange={setMetric} compactPeriodPicker
                        onInspectRange={(dateFrom, dateTo) => navigate(usageLink('summary', {dateFrom, dateTo}))} />
                </>}
        </section>
        <Panel title="Организации по подтверждённым токенам" {...top}>
            <p>До 5 организаций из серверного рейтинга по всему выбранному периоду.</p>
            <div className="platform-dashboard__table-scroll"><table><thead><tr>
                <th scope="col">Организация</th><th scope="col">Токены</th><th scope="col">Известная стоимость</th>
            </tr></thead><tbody>{top.data?.content.map(row => {
                const empty = row.coverage.assistantMessages === '0'
                return <tr key={row.organizationId}>
                    <td>{empty ? <Link to={'/admin/organizations?organizationId=' + encodeURIComponent(row.organizationId)}>{row.organizationName}</Link>
                        : <Link to={usageLink('summary', {organizationId: row.organizationId})}>{row.organizationName}</Link>}</td>
                    <td>{empty ? '—' : formatCount(row.totalTokens)}</td>
                    <td>{empty || row.costUsd === null ? '—' : trimDecimal(row.costUsd) + ' USD'}{!empty && row.coverage.pricingComplete !== true && <small> · Неполный учёт</small>}</td>
                </tr>
            })}</tbody></table></div>
            {top.data?.content.length === 0 && <p>Нет организаций в отчёте.</p>}
            <Link to={usageLink('organizations')}>Сравнить все организации</Link>
        </Panel>
        <section className="platform-dashboard__panel" aria-label="Безопасность и управление">
            <h2>Безопасность и управление</h2>
            <div className="platform-dashboard__links">
                <Link to="/admin/audit">Журнал событий и решений</Link>
                <Link to="/admin/models?section=access">Правила доступа и бюджеты</Link>
            </div>
        </section>
    </div>
}
