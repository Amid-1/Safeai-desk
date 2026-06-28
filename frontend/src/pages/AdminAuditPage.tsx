// frontend/src/pages/AdminAuditPage.tsx
import { useEffect, useState } from 'react'
import { getAuditEvents } from '../api/adminApi'
import type { AuditEvent, AuditEventFilter } from '../api/adminApi'
import { getApiErrorMessage } from '../api/http'
import { formatDateTime } from '../utils/format'

const EVENT_TYPES = [
    'USER_LOGIN_SUCCESS',
    'USER_LOGIN_FAILED',

    'CHAT_CREATED',
    'CHAT_MESSAGE_SENT',
    'AI_RESPONSE_RECEIVED',
    'AI_RESPONSE_FAILED',

    'USER_CREATED',
    'ORGANIZATION_CREATED',
    'USER_ENABLED_CHANGED',
    'USER_ROLES_CHANGED',
    'USER_PASSWORD_RESET',

    'RATE_LIMIT_EXCEEDED',

    'SECURITY_REFRESH_REUSE_DETECTED',
    'USER_LOGOUT',

    'ORGANIZATION_NAME_CHANGED',
    'ORGANIZATION_ENABLED_CHANGED'
]

function AdminAuditPage() {
    const [events, setEvents] = useState<AuditEvent[]>([])
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(true)

    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] = useState(0)

    const [draftEventType, setDraftEventType] = useState('')
    const [draftUserEmail, setDraftUserEmail] = useState('')
    const [draftDateFrom, setDraftDateFrom] = useState('')
    const [draftDateTo, setDraftDateTo] = useState('')
    const [draftOrganizationId, setDraftOrganizationId] = useState('')

    const [appliedFilter, setAppliedFilter] = useState<AuditEventFilter>({})

    useEffect(() => {
        async function loadAuditEvents() {
            setLoading(true)
            setError('')

            try {
                const data = await getAuditEvents(page, 50, appliedFilter)

                setEvents(data.content)
                setTotalPages(data.totalPages)
            } catch (err) {
                setError(getApiErrorMessage(err, 'Failed to load audit events'))
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
            dateFrom: draftDateFrom ? `${draftDateFrom}T00:00:00Z` : undefined,
            dateTo: draftDateTo ? `${draftDateTo}T23:59:59Z` : undefined,
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
            <h1>Admin Audit Events</h1>

            <div className="card form-card">
                <div className="form">
                    <label>
                        Event type
                        <select
                            value={draftEventType}
                            onChange={(event) => setDraftEventType(event.target.value)}
                        >
                            <option value="">All events</option>

                            {EVENT_TYPES.map((eventType) => (
                                <option key={eventType} value={eventType}>
                                    {eventType}
                                </option>
                            ))}
                        </select>
                    </label>

                    <label>
                        User email
                        <input
                            value={draftUserEmail}
                            onChange={(event) => setDraftUserEmail(event.target.value)}
                            placeholder="admin@test.com"
                        />
                    </label>

                    <label>
                        Date from
                        <input
                            type="date"
                            value={draftDateFrom}
                            onChange={(event) => setDraftDateFrom(event.target.value)}
                        />
                    </label>

                    <label>
                        Date to
                        <input
                            type="date"
                            value={draftDateTo}
                            onChange={(event) => setDraftDateTo(event.target.value)}
                        />
                    </label>

                    <label>
                        Organization ID
                        <input
                            value={draftOrganizationId}
                            onChange={(event) => setDraftOrganizationId(event.target.value)}
                            placeholder="Only for SUPER_ADMIN"
                        />
                    </label>

                    <div className="user-actions">
                        <button type="button" onClick={applyFilters}>
                            Apply filters
                        </button>

                        <button
                            type="button"
                            className="secondary-button"
                            onClick={resetFilters}
                        >
                            Reset filters
                        </button>
                    </div>
                </div>
            </div>

            {loading && <p>Loading...</p>}
            {error && <div className="error">{error}</div>}

            {!loading && !error && events.length === 0 && (
                <div className="card">
                    <p>No audit events found.</p>
                </div>
            )}

            {!loading && !error && events.length > 0 && (
                <div className="card">
                    <table>
                        <thead>
                        <tr>
                            <th>Created at</th>
                            <th>Organization</th>
                            <th>User</th>
                            <th>Event type</th>
                            <th>Details</th>
                        </tr>
                        </thead>

                        <tbody>
                        {events.map((event) => (
                            <tr key={event.id}>
                                <td>{formatDateTime(event.createdAt)}</td>
                                <td>{event.organizationId ?? '-'}</td>
                                <td>{event.userEmail ?? '-'}</td>
                                <td>{event.eventType}</td>
                                <td>
                                    <pre>{JSON.stringify(event.details, null, 2)}</pre>
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
                            Previous
                        </button>

                        <span>
                            Page {page + 1} of {Math.max(totalPages, 1)}
                        </span>

                        <button
                            type="button"
                            className="secondary-button"
                            disabled={page + 1 >= totalPages || loading}
                            onClick={() => setPage((prev) => prev + 1)}
                        >
                            Next
                        </button>
                    </div>
                </div>
            )}
        </div>
    )
}

export default AdminAuditPage