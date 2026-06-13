// pages/AdminAuditPages.tsx
import { useEffect, useState } from 'react'
import { AuditEvent, getAuditEvents } from '../api/adminApi'
import { getApiErrorMessage } from '../api/http'

function AdminAuditPage() {
    const [events, setEvents] = useState<AuditEvent[]>([])
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(true)

    useEffect(() => {
        async function loadAuditEvents() {
            try {
                const data = await getAuditEvents()
                setEvents(data)
            } catch (err) {
                setError(getApiErrorMessage(err, 'Failed to load audit events'))
            } finally {
                setLoading(false)
            }
        }

        void loadAuditEvents()
    }, [])

    return (
        <div className="page">
            <h1>Admin Audit Events</h1>

            {loading && <p>Loading...</p>}
            {error && <div className="error">{error}</div>}

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
            </div>
        </div>
    )
}

export default AdminAuditPage