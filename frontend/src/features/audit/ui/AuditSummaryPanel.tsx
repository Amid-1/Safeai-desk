// frontend/src/features/audit/ui/AuditSummaryPanel.tsx
import type { AuditCategory, AuditMetric, AuditSummary } from '../api/adminAuditApi'

const categories: { value?: AuditCategory; label: string }[] = [
    { label: 'Все события' }, { value: 'SECURITY', label: 'Безопасность' },
    { value: 'USERS', label: 'Пользователи' }, { value: 'ORGANIZATIONS', label: 'Организации' },
    { value: 'AI', label: 'AI и чаты' }, { value: 'KNOWLEDGE', label: 'Базы знаний' },
    { value: 'SYSTEM', label: 'Системные' },
]
const cards: { metric?: AuditMetric; key: keyof AuditSummary; label: string; tone: string; hint: string }[] = [
    { key: 'total', label: 'Всего событий', tone: 'total', hint: 'Все события в выбранной области и периоде' },
    { metric: 'SUCCESS', key: 'successful', label: 'Успешные', tone: 'success', hint: 'Подтверждённые успешные операции и завершения' },
    { metric: 'DENIED', key: 'denied', label: 'Отказы', tone: 'warning', hint: 'Неудачный вход, блокировка AI или ограничение частоты' },
    { metric: 'CRITICAL', key: 'critical', label: 'Критические', tone: 'danger', hint: 'Сигналы повторного использования refresh-токена; не подтверждённые инциденты' },
]

export default function AuditSummaryPanel({ summary, loading, error, disabled, category, metric, onMetric, onCategory, onRetry }: {
    summary: AuditSummary | null; loading: boolean; error: string; disabled: boolean
    category?: AuditCategory; metric?: AuditMetric
    onMetric: (value?: AuditMetric) => void; onCategory: (value?: AuditCategory) => void
    onRetry: () => void
}) {
    return <section className="audit-investigation" aria-label="Сводка аудита">
        <div className="audit-investigation__heading">
            <h2>События за выбранный период</h2>
            <span className="muted">Серверная сводка · не зависит от страницы</span>
        </div>
        <div className="audit-investigation__cards" aria-busy={loading}>
            {cards.map(card => <button key={card.key} type="button"
                className={'audit-summary-card audit-summary-card--' + card.tone}
                aria-pressed={metric === card.metric} title={card.hint}
                aria-label={`${card.label} ${summary ? summary[card.key].toLocaleString('ru-RU') : 'нет данных'}`}
                disabled={disabled || !summary || loading || !!error}
                onClick={() => onMetric(card.metric)}>
                <span>{card.label}</span>
                <strong>{summary ? summary[card.key].toLocaleString('ru-RU') : '—'}</strong>
                <span className="audit-summary-card__arrow" aria-hidden="true">↗</span>
            </button>)}
        </div>
        {error && <div className="audit-investigation__error" role="status">
            Сводка недоступна. Журнал можно использовать отдельно.
            <button type="button" className="secondary-button" disabled={disabled} onClick={onRetry}>Обновить сводку</button>
        </div>}
        <div className="audit-investigation__categories" role="group" aria-label="Категория событий">
            {categories.map(item => <button key={item.label} type="button"
                aria-pressed={category === item.value} disabled={disabled}
                onClick={() => onCategory(item.value)}>{item.label}</button>)}
        </div>
        <p className="muted audit-investigation__hint">Категории и карточки фильтруют весь журнал. Критические сигналы могут пересекаться с другими результатами. Ошибки выполнения не входят в отказы; не все события относятся к этим трём группам.</p>
    </section>
}
