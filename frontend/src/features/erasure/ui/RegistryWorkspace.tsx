// frontend/src/features/erasure/ui/RegistryWorkspace.tsx
import {useEffect, useState} from 'react'
import {useOutletContext, useSearchParams} from 'react-router-dom'
import DataManagementIcon, {type DataIconName} from '../../../shared/ui/DataManagementIcon'
import RequestIdCopy from './RequestIdCopy'
import {WorkspaceLink} from '../../../shared/ui/WorkspaceReturn'
import {ApiError} from '../../../shared/api/http'
import {
    loadRegistry,
    loadRegistrySummary,
    type RegistryPage,
    type RegistrySummary,
    type Capabilities
} from '../api/workspaceApi'
import {RESOURCE_NAMES, registryState, reviewTime} from '../review/reviewLabels'
import {EmptyState, LoadingState} from '../../../shared/ui/StateBlock'
import '../review/ErasureReview.css'

type FilterEntry = readonly [code: string, label: string]
const GROUPS: readonly { label: string; entries: readonly FilterEntry[] }[] = [
    {label: 'Общие', entries: [['ALL', 'Все'], ['ATTENTION', 'Требуют внимания'], ['HELD', 'Удержания']]},
    {
        label: 'Регистрация',
        entries: [['DRAFT', 'Черновики'], ['SUBMITTED', 'Зарегистрированы'], ['WITHDRAWN', 'Отозваны']]
    },
    {
        label: 'Рассмотрение',
        entries: [['PENDING', 'На рассмотрении'], ['NEEDS_INFORMATION', 'Нужно уточнение'], ['APPROVED_REVIEW_ONLY', 'Предварительно согласованы'], ['REJECTED', 'Отказы']]
    },
]

const METRIC_ICONS: Record<string, { icon: DataIconName; tone: string }> = {
    ALL: {icon: 'files', tone: 'blue'},
    PENDING: {icon: 'clock', tone: 'amber'},
    NEEDS_INFORMATION: {icon: 'message', tone: 'violet'},
    HELD: {icon: 'lock', tone: 'rose'},
    SUBMITTED: {icon: 'inbox', tone: 'blue'},
    DRAFT: {icon: 'files', tone: 'violet'},
    WITHDRAWN: {icon: 'history', tone: 'rose'},
}

function metricIcon(code: string): { icon: DataIconName; tone: string } {
    return METRIC_ICONS[code] ?? {icon: 'files', tone: 'blue'}
}

type Scope = 'PLATFORM' | 'TENANT' | 'REVIEW'
type Failure = { message: string; status?: number; requestId?: string }
type Load<T> = { key: string; data: T; failure?: never } | { key: string; failure: Failure; data?: never }

function describeFailure(error: unknown, subject: string): Failure {
    const fallback = `Не удалось загрузить ${subject}. Повторите попытку.`
    if (!(error instanceof ApiError)) return {message: fallback}
    const {status, errorCode, requestId} = error
    const message = errorCode === 'INVALID_RESPONSE' ? 'Сервер вернул некорректный ответ. Данные не показаны.'
        : status === 401 ? 'Сессия недействительна. Войдите повторно.'
            : status === 403 ? 'Недостаточно прав для просмотра данных.'
                : status === 400 || status === 422 ? 'Сервер отклонил параметры запроса.'
                    : status === 429 ? 'Слишком много запросов. Повторите попытку позже.'
                        : status >= 500 ? 'Сервис временно недоступен. Повторите попытку.'
                            : errorCode === 'REQUEST_TIMEOUT' ? 'Сервер не ответил вовремя.'
                                : status === 0 ? 'Не удалось связаться с сервером.' : fallback
    return {message, status, requestId}
}

function Status({state, text}: { state: string; text?: string }) {
    const tone = ['PENDING', 'NEEDS_INFORMATION', 'APPROVED_REVIEW_ONLY', 'REJECTED', 'WITHDRAWN', 'HELD'].includes(state)
        ? ` er2-status--${state.toLowerCase()}` : ''
    return <span className={`er2-status${tone}`}>{text ?? registryState(state)}</span>
}

function FailureMessage({failure, retry}: { failure: Failure; retry: () => void }) {
    return <div className="er2-registry-feedback" role="alert"><p>{failure.message}</p>
        {(failure.status !== undefined || failure.requestId) && <details>
            <summary>Диагностика</summary>
            {failure.status !== undefined &&
                <p>{failure.status > 0 ? `HTTP ${failure.status}` : 'Нет подтверждённого HTTP-ответа или нарушен контракт.'}</p>}
            {failure.requestId && <p className="er2-registry-id">Номер запроса: {failure.requestId}</p>}
        </details>}
        <button className="er2-action" type="button" onClick={retry}>Повторить</button>
    </div>
}

export default function RegistryWorkspace({review = false}: { review?: boolean }) {
    const [params, setParams] = useSearchParams()
    const workspace = useOutletContext<{
        capabilities: Capabilities | null;
        loading: boolean;
        failed: boolean
    } | null>()
    const cap = workspace?.capabilities
    // Actual workspace always supplies server-confirmed capabilities. No fetch on a missing context.
    const allowed = review ? cap?.reviewQueue === true : cap?.platform === true || cap?.tenantRegistry === true
    const scope: Scope = review ? 'REVIEW' : cap?.platform ? 'PLATFORM' : 'TENANT'
    const platform = scope === 'PLATFORM'
    const tenant = scope === 'TENANT'
    const groups = GROUPS.map(group => ({
        ...group, entries: group.entries.filter(([code]) => tenant
            ? ['ALL', 'DRAFT', 'SUBMITTED', 'WITHDRAWN'].includes(code)
            : code !== 'DRAFT' && (!review || code !== 'SUBMITTED'))
    })).filter(group => group.entries.length > 0)
    const filters = groups.flatMap(group => group.entries)
    const requestedState = params.get('state') ?? 'ALL'
    const state = filters.some(([code]) => code === requestedState) ? requestedState : 'ALL'
    const query = (params.get('query') ?? '').slice(0, 160)
    const rawPage = Number(params.get('page') ?? '0')
    const page = Number.isSafeInteger(rawPage) && rawPage >= 0 && rawPage <= 100000 ? rawPage : 0
    const [draft, setDraft] = useState(query)
    useEffect(() => setDraft(query), [query])
    const [revision, setRevision] = useState(0)
    const [result, setResult] = useState<Load<RegistryPage> | null>(null)
    const [summary, setSummary] = useState<Load<RegistrySummary> | null>(null)
    const key = JSON.stringify([scope, state, query, page, revision])
    const summaryKey = JSON.stringify([scope, revision])
    useEffect(() => {
        const ac = new AbortController()
        setResult(null)
        if (allowed) {
            void loadRegistry(review, state, query, page, ac.signal).then(data => {
                if (!ac.signal.aborted) {
                    // Do not expose unexpected data if the response belongs to a different scope.
                    if (data.items.some(item => item.reviewVisible !== !tenant || (platform && item.requester !== null))) {
                        setResult({
                            key,
                            failure: {message: 'Область ответа не соответствует доступу. Данные не показаны.'}
                        })
                    } else {
                        setResult({key, data})
                    }
                }
            }).catch(error => {
                if (!ac.signal.aborted) setResult({key, failure: describeFailure(error, 'заявки')})
            })
        }
        return () => ac.abort()
    }, [allowed, key, review, state, query, page, tenant, platform])
    useEffect(() => {
        const ac = new AbortController()
        setSummary(null)
        if (allowed && !review) {
            void loadRegistrySummary(ac.signal).then(data => {
                if (!ac.signal.aborted) setSummary(data.scope === scope ? {key: summaryKey, data}
                    : {key: summaryKey, failure: {message: 'Область статистики не соответствует доступу.'}})
            }).catch(error => {
                if (!ac.signal.aborted) setSummary({key: summaryKey, failure: describeFailure(error, 'сводку')})
            })
        }
        return () => ac.abort()
    }, [allowed, review, scope, summaryKey])
    const current = allowed && result?.key === key ? result : null
    const totals = allowed && !review && summary?.key === summaryKey ? summary : null
    const loading = current === null
    const retry = () => setRevision(v => v + 1)

    function update(nextState: string, nextQuery: string, nextPage: number) {
        if (nextState === state && nextQuery === query && nextPage === page) {
            retry();
            return
        }
        setParams({state: nextState, query: nextQuery, page: String(nextPage)})
    }

    const metrics = platform
        ? [{label: 'Всего в реестре', code: 'ALL', value: totals?.data?.total},
            {label: 'Ожидают решения', code: 'PENDING', value: totals?.data?.pending},
            {label: 'Нужны уточнения', code: 'NEEDS_INFORMATION', value: totals?.data?.needsInformation},
            {label: 'С удержаниями', code: 'HELD', value: totals?.data?.held}]
        : [{label: 'Всего в реестре', code: 'ALL', value: totals?.data?.total},
            {label: 'Зарегистрированы', code: 'SUBMITTED', value: totals?.data?.registered},
            {label: 'Черновики', code: 'DRAFT', value: totals?.data?.drafts},
            {label: 'Отозваны', code: 'WITHDRAWN', value: totals?.data?.withdrawn}]
    const selectedLabel = state === 'SUBMITTED' && platform ? 'Без рассмотрения' : filters.find(([code]) => code === state)?.[1] ?? 'Все'
    const emptyTitle = state !== 'ALL' ? `Состояние: ${selectedLabel}` : query ? 'По заданным условиям поиска' : undefined
    const items = current?.data?.items
    if (!allowed) return <section className="er2-page page"><h1>{review ? 'Очередь согласований' : 'Заявки'}</h1>
        <p role="status">{workspace?.loading ? 'Проверяем доступ…' : workspace?.failed ? 'Не удалось проверить доступ.' : review ? 'Для рассмотрения требуются отдельные полномочия.' : 'Реестр сейчас недоступен.'}</p>
    </section>
    return <section className="er2-page page er2-registry-workspace">
        <header className="er2-heading er2-heading--row"><h1>{review ? 'Очередь согласований' : 'Заявки'}</h1>
            <button className="er2-action" type="button" disabled={loading} onClick={retry}>Обновить</button>
        </header>
        {!review && <section aria-label="Сводка реестра">
            <div
                className="er2-registry-metrics"
                aria-busy={totals === null}
            >
                {metrics.map(metric => {
                    const value = totals === null
                        ? '…'
                        : metric.value?.toLocaleString('ru-RU') ?? '—'

                    const appearance = metricIcon(metric.code)

                    return (
                        <button
                            key={metric.code}
                            type="button"
                            className="er2-registry-metric"
                            aria-label={`${metric.label} ${value}`}
                            aria-pressed={state === metric.code}
                            onClick={() => update(metric.code, query, 0)}
                        >
                <span className="er2-registry-metric-label">
                    <span
                        className={`er2-registry-metric-icon er2-registry-metric-icon--${appearance.tone}`}
                    >
                        <DataManagementIcon name={appearance.icon}/>
                    </span>

                    {metric.label}
                </span>

                            <strong>{value}</strong>
                        </button>
                    )
                })}
            </div>
            {totals?.failure && <FailureMessage failure={totals.failure} retry={retry}/>}
            <p className="er2-registry-caption">Сводка по всей доступной области, независимо от
                поиска. {platform && 'Удержания могут пересекаться с другими состояниями.'}</p>
        </section>}
        <div className="er2-card er2-queue-card">
            <form className="er2-registry-search" onSubmit={e => {
                e.preventDefault();
                update(state, draft.trim(), 0)
            }}>
                <label className="er2-filter">Поиск <input type="search" maxLength={160} value={draft}
                                                           placeholder={platform ? 'Номер заявки или организация' : 'Номер заявки, имя или email'}
                                                           onChange={e => setDraft(e.target.value)}/></label>
                <button className="er2-action" type="submit">Найти</button>
            </form>
            <div className="er2-registry-filters">
                <div role="group" aria-label="Быстрые фильтры" className="er2-registry-quick">
                    {filters.filter(([code]) => ['ALL', 'ATTENTION', 'HELD'].includes(code)).map(([code, label]) =>
                        <button type="button" key={code} aria-pressed={state === code}
                                className={`er2-tab${state === code ? ' er2-tab--active' : ''}`}
                                onClick={() => update(code, query, 0)}>{label}</button>)}
                </div>
                <label className="er2-filter">Состояние заявки <select value={state}
                                                                       onChange={e => update(e.target.value, query, 0)}>
                    {groups.map(group => <optgroup key={group.label}
                                                   label={group.label}>{group.entries.map(([code, label]) => <option
                        key={code}
                        value={code}>{code === 'SUBMITTED' && platform ? 'Без рассмотрения' : label}</option>)}</optgroup>)}
                </select></label>
            </div>
            {loading ? <LoadingState message="Загружаем заявки…"/> : current.failure ?
                <FailureMessage failure={current.failure} retry={retry}/> : items && <>
                {items.length === 0 ? <EmptyState title={emptyTitle} message="Заявок не найдено."
                                                  action={state !== 'ALL' || query ?
                                                      <button className="er2-action" type="button"
                                                              onClick={() => update('ALL', '', 0)}>Сбросить
                                                          фильтры</button> : undefined}/>
                    : <div className="er2-table-wrap">
                        <table className="er2-table">
                            <thead>
                            <tr>
                                <th>Заявка</th>
                                <th>Организация</th>
                                {!platform && <th>Заявитель</th>}
                                <th>Регистрация</th>
                                {!tenant && <>
                                    <th>Рассмотрение</th>
                                    <th>Удержание</th>
                                </>}
                                <th>Обновлена</th>
                                <th>Действие</th>
                            </tr>
                            </thead>
                            <tbody>{items.map(item => <tr key={item.requestId}>
                                <td><strong>{RESOURCE_NAMES[item.resourceType] ?? 'Объект'}</strong>
                                    <details>
                                        <summary>Номер {item.requestId.slice(0, 8)}</summary>
                                        <RequestIdCopy key={item.requestId} requestId={item.requestId}/></details>
                                </td>
                                <td>{item.organization}</td>
                                {!platform && <td>{item.requester ?? 'Не указан'}</td>}
                                <td><Status state={item.intakeState}/></td>
                                {!tenant &&
                                    <td>{item.reviewState ? <Status state={item.reviewState}/> : 'Не начато'}</td>}
                                {!tenant && <td>{item.holdActive ? <Status state="HELD" text="Активно"/> : 'Нет'}</td>}
                                <td>{reviewTime(item.updatedAt)}</td>
                                <td><WorkspaceLink
                                    to={review ? `/admin/erasure/requests/${item.requestId}` : `/admin/erasure/registry/${item.requestId}`}>Открыть
                                    карточку</WorkspaceLink></td>
                            </tr>)}</tbody>
                        </table>
                    </div>}
                <div className="er2-registry-footer">
                    <span>На странице: {items.length}</span>{(page > 0 || current.data?.hasNext) ?
                    <div role="group" aria-label="Страницы заявок">
                        <button className="er2-action" type="button" disabled={page === 0}
                                onClick={() => update(state, query, page - 1)}>Назад
                        </button>
                        <span>Страница {page + 1}</span>
                        <button className="er2-action" type="button" disabled={!current.data?.hasNext}
                                onClick={() => update(state, query, page + 1)}>Вперёд
                        </button>
                    </div> : <span>Страница 1</span>}</div>
            </>}
        </div>
        <aside className="er2-registry-notice">
            <strong>{cap?.approvalsEnabled === false ? 'Рассмотрение заявок отключено' : tenant ? 'Регистрация и рассмотрение разделены' : 'Рассмотрение требует отдельных полномочий'}</strong>
            <p>{cap?.approvalsEnabled === false ? 'Зарегистрированные заявки сохраняются. Ожидание не означает, что удаление запущено.' : 'Решения принимаются только в очереди согласований при наличии независимого полномочия.'} Удаление
                по заявкам не подключено.</p>
        </aside>
    </section>
}
