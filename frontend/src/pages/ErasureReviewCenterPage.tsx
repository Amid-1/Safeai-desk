// frontend/src/pages/ErasureReviewCenterPage.tsx
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { loadReviewSummary, type ReviewSummary } from '../features/erasure/review/reviewApi'
import { getApiErrorMessage } from '../shared/api/http'
import '../features/erasure/review/ErasureReview.css'

const METRICS: { key: keyof ReviewSummary; label: string; detail: string; tone: string }[] = [
    { key: 'total', label: 'Всего заявок', detail: 'Доступная платформенная сводка', tone: 'blue' },
    { key: 'pending', label: 'Ожидают решения', detail: 'Нужно независимое рассмотрение', tone: 'amber' },
    { key: 'needsInformation', label: 'Нужно уточнение', detail: 'Ожидают действий заявителя', tone: 'violet' },
    { key: 'held', label: 'Активные удержания', detail: 'Удаление заблокировано', tone: 'rose' },
]

export default function ErasureReviewCenterPage() {
    const [summary, setSummary] = useState<ReviewSummary | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [revision, setRevision] = useState(0)
    const refresh = useCallback(() => setRevision(value => value + 1), [])

    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        setError(null)
        void loadReviewSummary(controller.signal).then(value => {
            if (!controller.signal.aborted) setSummary(value)
        }).catch(err => {
            if (!controller.signal.aborted) {
                setSummary(null)
                setError(getApiErrorMessage(err, 'Не удалось получить сводку R1b.'))
            }
        }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
        return () => controller.abort()
    }, [revision])

    return <section className="er2-page page er2-center">
        <header className="er2-hero er2-center-hero">
            <div>
                <div className="er2-eyebrow">УПРАВЛЕНИЕ ДАННЫМИ / R1b</div>
                <h1>Центр удаления данных</h1>
                <p>Контроль обращений, независимых согласований и ограничений хранения.</p>
                <div className="er2-hero-actions">
                    <Link className="er2-primary" to="/admin/erasure/requests">Очередь рассмотрения</Link>
                    <Link className="er2-action" to="/admin/retention">Политика хранения</Link>
                </div>
            </div>
            <div className="er2-hero-aside">
                <span className="er2-hero-aside-label">ТЕХНИЧЕСКОЕ ИСПОЛНЕНИЕ</span>
                <strong>Удаление отключено</strong>
                <span>Согласование заявки не запускает очистку данных.</span>
            </div>
        </header>

        <div className="er2-banner er2-banner--warning" role="note">
            <span className="er2-banner-mark" aria-hidden="true">!</span>
            <div><strong>Рассмотрение и физическое удаление — разные процессы.</strong>
                <p>R1b фиксирует решения и удержания. Исполнители R3–R8 не активированы.</p></div>
        </div>

        <section aria-labelledby="er2-metrics-title">
            <div className="er2-section-heading">
                <div><div className="er2-eyebrow">СВОДКА</div><h2 id="er2-metrics-title">Состояние заявок</h2>
                    <p>Показатели берутся только из API, без демонстрационных значений.</p></div>
                <button className="er2-action" type="button" onClick={refresh} disabled={loading}>
                    {loading ? 'Загрузка…' : 'Обновить данные'}
                </button>
            </div>
            {error && <div className="er2-banner er2-banner--danger" role="alert">
                <span className="er2-banner-mark" aria-hidden="true">!</span>
                <div><strong>Сводка недоступна</strong><p>{error}</p>
                    <p>Возможные причины: R1b выключен, недостаточно прав или недоступен backend.
                        Другие разделы ниже остаются доступны согласно вашей роли.</p></div>
            </div>}
            <div className="er2-metrics-grid" aria-busy={loading}>
                {METRICS.map(metric => <div key={metric.key} className={`er2-metric er2-metric--${metric.tone}`}>
                    <span>{metric.label}</span>
                    <strong>{loading ? '…' : summary ? summary[metric.key].toLocaleString('ru-RU') : '—'}</strong>
                    <small>{metric.detail}</small>
                </div>)}
            </div>
        </section>

        <div className="er2-dashboard-grid">
            <section className="er2-card er2-operations" aria-labelledby="er2-work-title">
                <div className="er2-eyebrow">РАБОЧИЕ ПРОЦЕССЫ</div>
                <h2 id="er2-work-title">Что требует внимания</h2>
                <div className="er2-operation-row">
                    <span className="er2-operation-symbol" aria-hidden="true">01</span>
                    <div><strong>Согласования</strong><p>Решения, отказ и запрос дополнительных сведений.</p></div>
                    <Link to="/admin/erasure/requests">Открыть очередь</Link>
                </div>
                <div className="er2-operation-row">
                    <span className="er2-operation-symbol" aria-hidden="true">02</span>
                    <div><strong>Политика хранения</strong><p>Допуск новых обращений и технические ограничения.</p></div>
                    <Link to="/admin/retention">Посмотреть</Link>
                </div>
                <div className="er2-operation-row">
                    <span className="er2-operation-symbol" aria-hidden="true">03</span>
                    <div><strong>Журнал аудита</strong><p>История зарегистрированных действий платформы.</p></div>
                    <Link to="/admin/audit">Открыть аудит</Link>
                </div>
            </section>
            <aside className="er2-card er2-readiness" aria-labelledby="er2-ready-title">
                <div className="er2-eyebrow">ГОТОВНОСТЬ</div>
                <h2 id="er2-ready-title">Границы текущего этапа</h2>
                <ul>
                    <li><span className="er2-readiness-dot er2-readiness-dot--blue" />
                        <div><strong>Регистрация обращений</strong><small>R1a: черновик, подача, отзыв</small></div></li>
                    <li><span className="er2-readiness-dot er2-readiness-dot--blue" />
                        <div><strong>Рассмотрение R1b</strong><small>Доступ зависит от независимых полномочий и флага</small></div></li>
                    <li><span className="er2-readiness-dot er2-readiness-dot--amber" />
                        <div><strong>Подтверждение личности</strong><small>Полноценный подтверждённый сценарий ещё не подключён</small></div></li>
                    <li><span className="er2-readiness-dot er2-readiness-dot--rose" />
                        <div><strong>Физическое удаление</strong><small>Не выполняется; никаких кнопок запуска</small></div></li>
                </ul>
                <p className="er2-muted">Сводка не заменяет аудиторские доказательства и release-gates.</p>
            </aside>
        </div>
    </section>
}
