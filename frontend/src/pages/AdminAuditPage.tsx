// frontend/src/pages/AdminAuditPage.tsx
import { useEffect, useMemo, useState } from 'react'
import { getAuditEvents } from '../api/adminApi'
import type { AuditEvent, AuditEventFilter } from '../api/adminApi'
import { getCurrentOrganization, getOrganizations } from '../api/organizationApi'
import type { Organization } from '../api/organizationApi'
import { getApiErrorMessage } from '../api/http'
import { formatDateTime } from '../utils/format'
import { getPageContent, getPageTotalPages } from '../utils/page'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlock'

const PAGE_SIZE = 50

const EVENT_TYPES = [
    'USER_LOGIN_SUCCESS',
    'USER_LOGIN_FAILED',
    'USER_LOGOUT',

    'CHAT_CREATED',
    'CHAT_MESSAGE_SENT',
    'AI_RESPONSE_RECEIVED',
    'AI_RESPONSE_FAILED',

    'USER_CREATED',
    'USER_UPDATED',
    'USER_ENABLED_CHANGED',
    'USER_ROLES_CHANGED',
    'USER_PASSWORD_RESET',

    'ORGANIZATION_CREATED',
    'ORGANIZATION_NAME_CHANGED',
    'ORGANIZATION_ENABLED_CHANGED',

    'RATE_LIMIT_EXCEEDED',
    'SECURITY_REFRESH_REUSE_DETECTED',
]

const EVENT_TYPE_LABELS: Record<string, string> = {
    USER_LOGIN_SUCCESS: 'Успешный вход',
    USER_LOGIN_FAILED: 'Ошибка входа',
    USER_LOGOUT: 'Выход из системы',

    CHAT_CREATED: 'Чат создан',
    CHAT_MESSAGE_SENT: 'Сообщение отправлено',
    AI_RESPONSE_RECEIVED: 'Ответ AI получен',
    AI_RESPONSE_FAILED: 'Ошибка AI-ответа',

    USER_CREATED: 'Пользователь создан',
    USER_UPDATED: 'Пользователь изменен',
    USER_ENABLED_CHANGED: 'Статус пользователя изменен',
    USER_ROLES_CHANGED: 'Роли пользователя изменены',
    USER_PASSWORD_RESET: 'Пароль пользователя сброшен',

    ORGANIZATION_CREATED: 'Организация создана',
    ORGANIZATION_NAME_CHANGED: 'Название организации изменено',
    ORGANIZATION_ENABLED_CHANGED: 'Статус организации изменен',

    RATE_LIMIT_EXCEEDED: 'Превышен лимит',
    SECURITY_REFRESH_REUSE_DETECTED: 'Повторное использование refresh token',
}

function AdminAuditPage() {
    const [events, setEvents] = useState<AuditEvent[]>([])
    const [organizations, setOrganizations] = useState<Organization[]>([])

    const [error, setError] = useState('')
    const [loading, setLoading] = useState(true)

    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] = useState(1)

    const [draftEventType, setDraftEventType] = useState('')
    const [draftUserEmail, setDraftUserEmail] = useState('')
    const [draftDateFrom, setDraftDateFrom] = useState('')
    const [draftDateTo, setDraftDateTo] = useState('')
    const [draftOrganizationId, setDraftOrganizationId] = useState('')

    const [appliedFilter, setAppliedFilter] = useState<AuditEventFilter>({})

    const organizationNameById = useMemo(() => {
        const map = new Map<string, string>()

        organizations.forEach((organization) => {
            map.set(organization.id, organization.name)
        })

        return map
    }, [organizations])

    useEffect(() => {
        async function loadOrganizationsForLabels() {
            try {
                const data = await getOrganizations(0, 500)
                setOrganizations(getPageContent(data))
                return
            } catch {
                // ADMIN не обязан иметь доступ к списку всех организаций.
                // Для него пробуем получить только текущую организацию.
            }

            try {
                const currentOrganization = await getCurrentOrganization()
                setOrganizations([currentOrganization])
            } catch {
                setOrganizations([])
            }
        }

        void loadOrganizationsForLabels()
    }, [])

    useEffect(() => {
        async function loadAuditEvents() {
            setLoading(true)
            setError('')

            try {
                const data = await getAuditEvents(page, PAGE_SIZE, appliedFilter)

                setEvents(getPageContent(data))
                setTotalPages(getPageTotalPages(data))
            } catch (err) {
                setError(getApiErrorMessage(err, 'Не удалось загрузить аудит.'))
            } finally {
                setLoading(false)
            }
        }

        void loadAuditEvents()
    }, [page, appliedFilter])

    function applyFilters() {
        setPage(0)

        setAppliedFilter({
            eventType: draftEventType || undefined,
            userEmail: draftUserEmail.trim() || undefined,
            dateFrom: draftDateFrom ? toUtcStartOfDayIso(draftDateFrom) : undefined,
            dateTo: draftDateTo ? toUtcExclusiveEndOfDayIso(draftDateTo) : undefined,
            organizationId: draftOrganizationId.trim() || undefined,
        })
    }

    function resetFilters() {
        setPage(0)

        setDraftEventType('')
        setDraftUserEmail('')
        setDraftDateFrom('')
        setDraftDateTo('')
        setDraftOrganizationId('')

        setAppliedFilter({})
    }

    return (
        <div className="page">
            <h1>Аудит событий</h1>

            <div className="card form-card">
                <div className="form">
                    <label>
                        Тип события
                        <select
                            value={draftEventType}
                            onChange={(event) => setDraftEventType(event.target.value)}
                        >
                            <option value="">Все события</option>

                            {EVENT_TYPES.map((eventType) => (
                                <option key={eventType} value={eventType}>
                                    {getEventTypeLabel(eventType)}
                                </option>
                            ))}
                        </select>
                    </label>

                    <label>
                        Email пользователя
                        <input
                            value={draftUserEmail}
                            onChange={(event) => setDraftUserEmail(event.target.value)}
                            placeholder="admin@test.com"
                        />
                    </label>

                    <label>
                        Дата с
                        <input
                            type="date"
                            value={draftDateFrom}
                            onChange={(event) => setDraftDateFrom(event.target.value)}
                        />
                    </label>

                    <label>
                        Дата по
                        <input
                            type="date"
                            value={draftDateTo}
                            onChange={(event) => setDraftDateTo(event.target.value)}
                        />
                    </label>

                    {organizations.length > 1 ? (
                        <label>
                            Организация
                            <select
                                value={draftOrganizationId}
                                onChange={(event) => setDraftOrganizationId(event.target.value)}
                            >
                                <option value="">Все организации</option>

                                {organizations.map((organization) => (
                                    <option key={organization.id} value={organization.id}>
                                        {organization.name}
                                    </option>
                                ))}
                            </select>
                        </label>
                    ) : (
                        <label>
                            ID организации
                            <input
                                value={draftOrganizationId}
                                onChange={(event) => setDraftOrganizationId(event.target.value)}
                                placeholder="Только для SUPER_ADMIN"
                            />
                        </label>
                    )}

                    <div className="filter-actions">
                        <button type="button" onClick={applyFilters}>
                            Применить фильтры
                        </button>

                        <button
                            type="button"
                            className="secondary-button"
                            onClick={resetFilters}
                        >
                            Сбросить фильтры
                        </button>
                    </div>
                </div>
            </div>

            {loading && <LoadingState message="Загрузка событий аудита..." />}

            {!loading && error && (
                <ErrorState
                    title="Ошибка загрузки"
                    message={error}
                    action={
                        <button type="button" onClick={() => setAppliedFilter({ ...appliedFilter })}>
                            Повторить
                        </button>
                    }
                />
            )}

            {!loading && !error && events.length === 0 && (
                <EmptyState
                    title="Нет данных"
                    message="События аудита не найдены."
                />
            )}

            {!loading && !error && events.length > 0 && (
                <div className="card table-card">
                    <table className="admin-table audit-table">
                        <thead>
                        <tr>
                            <th>Создано</th>
                            <th>Организация</th>
                            <th>Пользователь</th>
                            <th>Тип события</th>
                            <th>Детали</th>
                        </tr>
                        </thead>

                        <tbody>
                        {events.map((event) => (
                            <tr key={event.id}>
                                <td>{formatDateTime(event.createdAt)}</td>

                                <td>
                                    <OrganizationCell
                                        organizationId={event.organizationId}
                                        organizationNameById={organizationNameById}
                                    />
                                </td>

                                <td>{event.userEmail ?? '—'}</td>

                                <td>
                                    <span className="event-type-badge">
                                        {getEventTypeLabel(event.eventType)}
                                    </span>
                                </td>

                                <td className="audit-details-cell">
                                    <JsonDetails details={event.details} />
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>

                    <div className="pagination">
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={page === 0 || loading}
                            onClick={() => setPage((prev) => Math.max(0, prev - 1))}
                        >
                            Назад
                        </button>

                        <span>
                            Страница {page + 1} из {Math.max(totalPages, 1)}
                        </span>

                        <button
                            type="button"
                            className="secondary-button"
                            disabled={page + 1 >= totalPages || loading}
                            onClick={() => setPage((prev) => prev + 1)}
                        >
                            Вперед
                        </button>
                    </div>
                </div>
            )}
        </div>
    )
}

function OrganizationCell({
                              organizationId,
                              organizationNameById,
                          }: {
    organizationId: string | null
    organizationNameById: Map<string, string>
}) {
    if (!organizationId) {
        return <span className="muted">—</span>
    }

    const organizationName = organizationNameById.get(organizationId)

    if (organizationName) {
        return (
            <span title={organizationId}>
                {organizationName}
            </span>
        )
    }

    return (
        <span className="muted" title={organizationId}>
            Неизвестная организация
        </span>
    )
}

function JsonDetails({
                         details,
                     }: {
    details: Record<string, unknown> | null
}) {
    if (!details || Object.keys(details).length === 0) {
        return <span className="muted">—</span>
    }

    return (
        <details className="json-details">
            <summary>Показать детали</summary>
            <pre>{JSON.stringify(details, null, 2)}</pre>
        </details>
    )
}

function getEventTypeLabel(eventType: string): string {
    return EVENT_TYPE_LABELS[eventType] ?? eventType
}

function toUtcStartOfDayIso(dateValue: string): string {
    return `${dateValue}T00:00:00Z`
}

function toUtcExclusiveEndOfDayIso(dateValue: string): string {
    const [year, month, day] = dateValue
        .split('-')
        .map((part) => Number(part))

    const date = new Date(Date.UTC(year, month - 1, day))
    date.setUTCDate(date.getUTCDate() + 1)

    return `${date.toISOString().slice(0, 10)}T00:00:00Z`
}

export default AdminAuditPage