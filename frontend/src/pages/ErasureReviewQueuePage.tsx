// frontend/src/pages/ErasureReviewQueuePage.tsx
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { getApiErrorMessage } from '../shared/api/http'
import { loadReviewQueue, type ReviewView } from '../features/erasure/review/reviewApi'
import { RESOURCE_NAMES, REVIEW_STATES, reviewTime } from '../features/erasure/review/reviewLabels'
import { EmptyState, LoadingState } from '../shared/ui/StateBlock'
import '../features/erasure/review/ErasureReview.css'

type ViewFilter = 'ALL' | ReviewView['reviewState'] | 'HELD'
const FILTERS: { code: ViewFilter; label: string }[] = [
    { code: 'ALL', label: 'Все' }, { code: 'PENDING', label: 'На рассмотрении' },
    { code: 'NEEDS_INFORMATION', label: 'Нужны сведения' }, { code: 'HELD', label: 'Удержания' },
    { code: 'APPROVED_REVIEW_ONLY', label: 'Согласованы' }, { code: 'REJECTED', label: 'Отказы' },
    { code: 'WITHDRAWN', label: 'Отозваны' },
]

export default function ErasureReviewQueuePage() {
    const [items, setItems] = useState<ReviewView[]>([])
    const [filter, setFilter] = useState<ViewFilter>('ALL')
    const [query, setQuery] = useState('')
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [version, setVersion] = useState(0)
    useEffect(() => {
        const ac = new AbortController()
        setLoading(true)
        setError(null)
        void loadReviewQueue(ac.signal).then(rows => {
            if (!ac.signal.aborted) setItems(rows)
        }).catch(err => {
            if (!ac.signal.aborted) { setItems([]); setError(getApiErrorMessage(err,
                'Очередь недоступна. Проверьте полномочия и включение R1b.')) }
        }).finally(() => { if (!ac.signal.aborted) setLoading(false) })
        return () => ac.abort()
    }, [version])

    const filtered = useMemo(() => items.filter(item => {
        if (filter === 'HELD' && !item.holdActive) return false
        if (filter !== 'ALL' && filter !== 'HELD' && item.reviewState !== filter) return false
        const search = query.trim().toLocaleLowerCase('ru-RU')
        if (!search) return true
        const name = RESOURCE_NAMES[item.resourceType] ?? item.resourceType
        const state = REVIEW_STATES[item.reviewState] ?? item.reviewState
        return `${name} ${state} ${item.scope}`.toLocaleLowerCase('ru-RU').includes(search)
    }), [items, filter, query])

    return <section className="er2-page page">
        <Link className="er2-back" to="/admin/erasure">← Центр удаления данных</Link>
        <header className="er2-heading er2-heading--row">
            <div><div className="er2-eyebrow">УПРАВЛЕНИЕ ДАННЫМИ / РАССМОТРЕНИЕ</div>
                <h1>Очередь согласований</h1>
                <p>Только заявки, доступные назначенному проверяющему в своей организации.</p></div>
            <button type="button" className="er2-action" disabled={loading}
                onClick={() => setVersion(value => value + 1)}>Обновить очередь</button>
        </header>
        <div className="er2-banner er2-banner--warning" role="note">
            <span className="er2-banner-mark" aria-hidden="true">!</span>
            <div><strong>Согласование не запускает удаление.</strong>
                <p>Сначала проверяются права и удержания. Физическое исполнение остаётся отключённым.</p></div>
        </div>
        {!loading && !error && <div className="er2-queue-metrics" aria-label="Сводка доступной очереди">
            <div><span>Доступно заявок</span><strong>{items.length}</strong></div>
            <div><span>Ожидают решения</span><strong>{items.filter(item => item.reviewState === 'PENDING').length}</strong></div>
            <div><span>Нужны сведения</span><strong>{items.filter(item => item.reviewState === 'NEEDS_INFORMATION').length}</strong></div>
            <div><span>Есть удержание</span><strong>{items.filter(item => item.holdActive).length}</strong></div>
        </div>}
        {loading ? <LoadingState message="Загружаем очередь..." /> : error ?
            <div className="er2-card er2-unavailable" role="alert"><h2>Очередь недоступна</h2>
                <p>{error}</p><p>Наличие роли ADMIN или SUPER_ADMIN само по себе не выдаёт полномочия REVIEW/LEGAL.</p>
                <button type="button" className="er2-action" onClick={() => setVersion(v => v + 1)}>Повторить</button>
            </div> : <div className="er2-card er2-queue-card">
            <div className="er2-panel-heading er2-queue-heading"><div>
                <h2>Заявки на рассмотрение</h2><p>Локальный поиск по загруженным записям.</p></div>
                <label className="er2-filter">Тип объекта или статус
                    <input type="search" value={query} onChange={e => setQuery(e.target.value)}
                        placeholder="Например, чат или уточнение" />
                </label></div>
            <div className="er2-filter-tabs" aria-label="Фильтр по состоянию">
                {FILTERS.map(option => <button key={option.code} type="button"
                    aria-pressed={filter === option.code} className={filter === option.code ? 'er2-tab er2-tab--active' : 'er2-tab'}
                    onClick={() => setFilter(option.code)}>{option.label}</button>)}
            </div>
            {filtered.length > 0 ? <div className="er2-table-wrap"><table className="er2-table">
                <thead><tr><th>Объект и заявка</th><th>Рассмотрение</th><th>Удержание</th><th>Обновлена</th><th>Действие</th></tr></thead>
                <tbody>{filtered.map(item => <tr key={item.requestId}>
                    <td><strong>{RESOURCE_NAMES[item.resourceType] ?? item.resourceType}</strong>
                        <small>{item.scope}</small></td>
                    <td><span className={`er2-status er2-status--${item.reviewState.toLowerCase()}`}>
                        {REVIEW_STATES[item.reviewState]}</span></td>
                    <td>{item.holdActive ? <span className="er2-status er2-status--held">Активно</span> :
                        <span className="er2-table-secondary">Нет</span>}</td>
                    <td>{reviewTime(item.updatedAt)}</td>
                    <td><Link className="er2-row-link" to={`/admin/erasure/requests/${item.requestId}`}>
                        Открыть карточку <span aria-hidden="true">→</span></Link></td>
                </tr>)}</tbody>
            </table></div> : <EmptyState message={items.length === 0 ?
                'Доступных заявок пока нет.' : 'По выбранным фильтрам заявок не найдено.'} />}
            <p className="er2-muted er2-table-footnote">Сервер возвращает не более 100 последних доступных заявок.
                Счётчики выше относятся только к этой выборке, не ко всей платформе.</p>
        </div>}
    </section>
}
