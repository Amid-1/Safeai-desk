// frontend/src/pages/ErasureReviewQueuePage.tsx
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { getApiErrorMessage } from '../shared/api/http'
import { loadReviewQueue, type ReviewView } from '../features/erasure/review/reviewApi'
import { RESOURCE_NAMES, REVIEW_STATES, reviewTime } from '../features/erasure/review/reviewLabels'
import { ErrorState, EmptyState, LoadingState } from '../shared/ui/StateBlock'
import '../features/erasure/review/ErasureReview.css'

export default function ErasureReviewQueuePage() {
    const [items, setItems] = useState<ReviewView[]>([])
    const [filter, setFilter] = useState('ALL')
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [version, setVersion] = useState(0)
    useEffect(() => {
        const ac = new AbortController()
        void loadReviewQueue(ac.signal).then(rows => {
            if (!ac.signal.aborted) { setItems(rows); setError(null) }
        }).catch(err => {
            if (!ac.signal.aborted) setError(getApiErrorMessage(err, 'Нет доступа к очереди или модуль выключен.'))
        }).finally(() => { if (!ac.signal.aborted) setLoading(false) })
        return () => ac.abort()
    }, [version])
    const filtered = useMemo(() => items.filter(row => filter === 'ALL' || row.reviewState === filter),
        [items, filter])
    return <section className="er2-page page">
        <header className="er2-heading er2-heading--row">
            <div><div className="er2-eyebrow">УПРАВЛЕНИЕ ДАННЫМИ / РАССМОТРЕНИЕ</div>
                <h1>Запросы организации</h1>
                <p>Независимые решения по заявкам без запуска удаления.</p></div>
            <button type="button" className="er2-action" onClick={() => setVersion(value => value+1)}>
                Обновить очередь
            </button>
        </header>
        <div className="er2-card er2-warning" role="note">
            <strong>Решение по заявке не запускает удаление.</strong> Исполнители отключены; каждый тип данных
            потребует отдельного технологического допуска и проверки.
        </div>
        {loading ? <LoadingState message="Загружаем очередь..." /> : error ? <ErrorState
            title="Очередь недоступна" message={error} /> : <div className="er2-card">
            <div className="er2-panel-heading"><h2>Очередь согласований</h2>
                <label className="er2-filter">Состояние
                    <select value={filter} onChange={e => setFilter(e.target.value)}>
                        <option value="ALL">Все</option>
                        {Object.entries(REVIEW_STATES).map(([key, text]) =>
                            <option key={key} value={key}>{text}</option>)}
                    </select></label></div>
            <div className="er2-table-wrap"><table className="er2-table">
                <thead><tr><th>Объект</th><th>Состояние</th><th>Удержание</th><th>Обновлена</th><th>Заявка</th></tr></thead>
                <tbody>{filtered.map(item => <tr key={item.requestId}>
                    <td><strong>{RESOURCE_NAMES[item.resourceType] ?? item.resourceType}</strong>
                        <small>{item.operation}</small></td>
                    <td><span className="er2-pill">{REVIEW_STATES[item.reviewState]}</span></td>
                    <td>{item.holdActive ? 'Есть удержание' : 'Нет'}</td>
                    <td>{reviewTime(item.updatedAt)}</td>
                    <td><Link to={`/admin/erasure/requests/${item.requestId}`}>Открыть</Link></td>
                </tr>)}</tbody>
            </table></div>
            {filtered.length === 0 && <EmptyState message="По выбранному фильтру заявок нет." />}
        </div>}
    </section>
}
