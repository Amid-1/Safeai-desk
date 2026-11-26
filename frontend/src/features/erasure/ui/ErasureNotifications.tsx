// frontend/src/features/erasure/ui/ErasureNotifications.tsx
import { Link } from 'react-router-dom'
import type { NotificationFeed } from '../api/notificationsApi'
import './ErasureNotifications.css'

const labels: Record<string, string> = {
    SUBMITTED: 'Новая заявка на рассмотрение', INFORMATION_PROVIDED: 'Получено уточнение',
    APPROVED_REVIEW_ONLY: 'Заявка согласована (удаление не начато)', REJECTED: 'В заявке отказано',
    NEEDS_INFORMATION: 'Запрошено уточнение',
}
export function ErasureNotifications({ feed }: { feed: NotificationFeed | null }) {
    if (!feed || (!feed.canRequest && feed.reviewCount === null)) return null
    const events = [...feed.personal, ...feed.review].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    return <details className="erasure-notifications">
        <summary aria-label="Уведомления по заявкам">Уведомления{events.length > 0 && <span className="erasure-menu-count" title="Недавние события, не счётчик непрочитанных">{events.length}</span>}</summary>
        <div className="erasure-notifications-panel">
            <strong>Последние события по заявкам</strong>
            <small>Обновляются автоматически. Не являются подтверждением удаления.</small>
            {events.length === 0 ? <p>Новых для отображения событий нет.</p> : <ul>{events.map(event => <li key={event.audience + event.eventId}>
                <Link to={event.audience === 'PERSONAL' ? `/my/erasure-requests/${event.requestId}` : `/admin/erasure/requests/${event.requestId}`}>
                    {labels[event.eventCode]}
                    <time dateTime={event.createdAt}>{new Date(event.createdAt).toLocaleString('ru-RU')}</time>
                </Link>
            </li>)}</ul>}
        </div>
    </details>
}
