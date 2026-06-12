import { useEffect, useState } from 'react'
import { getUsageSummary, UsageSummary } from '../api/adminApi'

function AdminUsagePage() {
    const [items, setItems] = useState<UsageSummary[]>([])
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(true)

    useEffect(() => {
        async function loadUsage() {
            try {
                const data = await getUsageSummary()
                setItems(data)
            } catch (err) {
                setError(err instanceof Error ? err.message : 'Failed to load usage')
            } finally {
                setLoading(false)
            }
        }

        void loadUsage()
    }, [])

    return (
        <div className="page">
            <h1>Admin Usage Summary</h1>

            {loading && <p>Loading...</p>}
            {error && <div className="error">{error}</div>}

            <div className="card">
                <table>
                    <thead>
                    <tr>
                        <th>User</th>
                        <th>Model</th>
                        <th>Input tokens</th>
                        <th>Output tokens</th>
                        <th>Total tokens</th>
                        <th>Cost USD</th>
                    </tr>
                    </thead>

                    <tbody>
                    {items.map((item) => (
                        <tr key={`${item.userId}-${item.model}`}>
                            <td>{item.userEmail}</td>
                            <td>{item.model}</td>
                            <td>{item.inputTokens}</td>
                            <td>{item.outputTokens}</td>
                            <td>{item.totalTokens}</td>
                            <td>{item.costUsd}</td>
                        </tr>
                    ))}
                    </tbody>
                </table>
            </div>
        </div>
    )
}

export default AdminUsagePage