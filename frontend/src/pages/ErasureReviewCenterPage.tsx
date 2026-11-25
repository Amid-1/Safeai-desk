// frontend/src/pages/ErasureReviewCenterPage.tsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { loadReviewSummary, type ReviewSummary } from '../features/erasure/review/reviewApi'
import { getApiErrorMessage } from '../shared/api/http'
import { ErrorState, LoadingState } from '../shared/ui/StateBlock'
import '../features/erasure/review/ErasureReview.css'

export default function ErasureReviewCenterPage() {
    const [summary, setSummary] = useState<ReviewSummary | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    useEffect(() => {
        const controller = new AbortController()
        void loadReviewSummary(controller.signal).then(data => {
            if (!controller.signal.aborted) setSummary(data)
        }).catch(err => {
            if (!controller.signal.aborted) setError(getApiErrorMessage(err, 'Нет полномочий на просмотр.'))
        }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
        return () => controller.abort()
    }, [])
    return <section className="er2-page page">
        <header className="er2-heading"><div className="er2-eyebrow">УПРАВЛЕНИЕ ДАННЫМИ</div>
            <h1>Центр удаления данных</h1>
            <p>Рассмотрение заявок, удержания и политика хранения. Не является панелью запуска удаления.</p>
        </header>
        <div className="er2-card er2-warning" role="note">
            Исполнение удалений не подключено. Согласование и техническая возможность независимы.
        </div>
        {loading ? <LoadingState message="Получаем показатели..." /> : error ?
            <ErrorState title="Доступ к центру ограничен" message={error} /> : <>
                <div className="er2-stats">
                    <div className="er2-card"><span>Всего доступных заявок</span><strong>{summary?.total ?? 0}</strong></div>
                    <div className="er2-card"><span>Ожидают решения</span><strong>{summary?.pending ?? 0}</strong></div>
                    <div className="er2-card"><span>Юридические удержания</span><strong>{summary?.held ?? 0}</strong></div>
                    <div className="er2-card"><span>Требуют уточнения</span><strong>{summary?.needsInformation ?? 0}</strong></div>
                </div>
                <div className="er2-grid-2">
                    <div className="er2-card"><h2>Очередь согласований</h2>
                        <p>Заявки с назначенным независимым проверяющим.</p>
                        <Link className="er2-primary" to="/admin/erasure/requests">Перейти к очереди</Link>
                    </div>
                    <div className="er2-card"><h2>Политика хранения</h2>
                        <p>Глобальные ограничения не зависят от решения по конкретной заявке.</p>
                        <Link className="er2-action" to="/admin/retention">Открыть политику</Link>
                    </div>
                </div>
            </>}
        <p className="er2-muted">Показатели являются обезличенной сводкой по платформе. Для доступа к конкретным
            заявкам требуются отдельные полномочия проверяющего.</p>
    </section>
}
