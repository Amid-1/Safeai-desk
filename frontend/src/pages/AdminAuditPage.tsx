// frontend/src/pages/AdminAuditPages.tsx
import { useEffect, useState } from 'react'
import { AuditEvent, getAuditEvents } from '../api/adminApi'
import { getApiErrorMessage } from '../api/http'

function AdminAuditPage() {
    const [events, setEvents] = useState<AuditEvent[]>([])
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(true)

    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] = useState(0)

    useEffect(() => {
        async function loadAuditEvents() {
            setLoading(true)
            setError('')

            try {
                const data = await getAuditEvents(page, 50)

                setEvents(data.content)
                setTotalPages(data.totalPages)
            } catch (err) {
                setError(getApiErrorMessage(err, 'Failed to load audit events'))
            } finally {
                setLoading(false)
            }
        }

        void loadAuditEvents()
    }, [page])

    return (
        <div className="page">
            <h1>Admin Audit Events</h1>

            {loading && <p>Loading...</p>}
            {error && <div className="error">{error}</div>}

            {!loading && !error && events.length === 0 && (
                <p>No audit events yet.</p>
            )}

            {events.length > 0 && (
                <div className="card">
                    <table>
                        <thead>
                        <tr>
                            <th>Created at</th>
                            <th>User</th>
                            <th>Event type</th>
                            <th>Details</th>
                        </tr>
                        </thead>

                        <tbody>
                        {events.map((event) => (
                            <tr key={event.id}>
                                <td>{event.createdAt}</td>
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