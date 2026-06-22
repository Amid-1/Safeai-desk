// frontend/src/pages/AdminUsagePage.tsx
import { useEffect, useState } from 'react'
import {
    getUsageByModels,
    getUsageByUsers,
    getUsageDaily,
    getUsageSummary,
} from '../api/adminApi'
import type {
    UsageDailySummary,
    UsageModelSummary,
    UsageSummary,
    UsageUserSummary,
} from '../api/adminApi'
import { getApiErrorMessage } from '../api/http'
import { formatDate, formatUsd } from '../utils/format'

type Tab = 'summary' | 'users' | 'models' | 'daily'

function AdminUsagePage() {
    const [tab, setTab] = useState<Tab>('summary')

    const [summary, setSummary] = useState<UsageSummary[]>([])
    const [users, setUsers] = useState<UsageUserSummary[]>([])
    const [models, setModels] = useState<UsageModelSummary[]>([])
    const [daily, setDaily] = useState<UsageDailySummary[]>([])

    const [error, setError] = useState('')
    const [loading, setLoading] = useState(true)

    useEffect(() => {
        async function loadUsage() {
            setLoading(true)
            setError('')

            try {
                const [summaryData, usersData, modelsData, dailyData] =
                    await Promise.all([
                        getUsageSummary(),
                        getUsageByUsers(),
                        getUsageByModels(),
                        getUsageDaily(),
                    ])

                setSummary(summaryData)
                setUsers(usersData)
                setModels(modelsData)
                setDaily(dailyData)
            } catch (err) {
                setError(getApiErrorMessage(err, 'Failed to load usage'))
            } finally {
                setLoading(false)
            }
        }

        void loadUsage()
    }, [])

    return (
        <div className="page">
            <h1>Admin Usage</h1>

            <div className="user-toolbar">
                <button className={tab === 'summary' ? 'filter-button active' : 'filter-button'} onClick={() => setTab('summary')}>
                    Summary
                </button>
                <button className={tab === 'users' ? 'filter-button active' : 'filter-button'} onClick={() => setTab('users')}>
                    By users
                </button>
                <button className={tab === 'models' ? 'filter-button active' : 'filter-button'} onClick={() => setTab('models')}>
                    By models
                </button>
                <button className={tab === 'daily' ? 'filter-button active' : 'filter-button'} onClick={() => setTab('daily')}>
                    Daily
                </button>
            </div>

            {loading && <p>Loading...</p>}
            {error && <div className="error">{error}</div>}

            {!loading && !error && (
                <div className="card">
                    {tab === 'summary' && (
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
                            {summary.map((item) => (
                                <tr key={`${item.userId}-${item.model}`}>
                                    <td>{item.userEmail}</td>
                                    <td>{item.model}</td>
                                    <td>{item.inputTokens}</td>
                                    <td>{item.outputTokens}</td>
                                    <td>{item.totalTokens}</td>
                                    <td>{formatUsd(item.costUsd)}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    )}

                    {tab === 'users' && (
                        <table>
                            <thead>
                            <tr>
                                <th>User</th>
                                <th>Input tokens</th>
                                <th>Output tokens</th>
                                <th>Total tokens</th>
                                <th>Cost USD</th>
                            </tr>
                            </thead>
                            <tbody>
                            {users.map((item) => (
                                <tr key={item.userId}>
                                    <td>{item.userEmail}</td>
                                    <td>{item.inputTokens}</td>
                                    <td>{item.outputTokens}</td>
                                    <td>{item.totalTokens}</td>
                                    <td>{item.costUsd}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    )}

                    {tab === 'models' && (
                        <table>
                            <thead>
                            <tr>
                                <th>Model</th>
                                <th>Input tokens</th>
                                <th>Output tokens</th>
                                <th>Total tokens</th>
                                <th>Cost USD</th>
                            </tr>
                            </thead>
                            <tbody>
                            {models.map((item) => (
                                <tr key={item.model}>
                                    <td>{item.model}</td>
                                    <td>{item.inputTokens}</td>
                                    <td>{item.outputTokens}</td>
                                    <td>{item.totalTokens}</td>
                                    <td>{item.costUsd}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    )}

                    {tab === 'daily' && (
                        <table>
                            <thead>
                            <tr>
                                <th>Date</th>
                                <th>Input tokens</th>
                                <th>Output tokens</th>
                                <th>Total tokens</th>
                                <th>Cost USD</th>
                            </tr>
                            </thead>
                            <tbody>
                            {daily.map((item) => (
                                <tr key={item.usageDate}>
                                    <td>{formatDate(item.usageDate)}</td>
                                    <td>{item.inputTokens}</td>
                                    <td>{item.outputTokens}</td>
                                    <td>{item.totalTokens ?? item.inputTokens + item.outputTokens}</td>
                                    <td>{item.costUsd}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    )}
                </div>
            )}
        </div>
    )
}

export default AdminUsagePage