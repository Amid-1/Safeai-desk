// frontend/src/pages/AdminUsagePage.tsx
import { useEffect, useMemo, useState } from 'react'
import {
    Bar,
    BarChart,
    CartesianGrid,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts'
import {
    getUsageByModels,
    getUsageByUsers,
    getUsageDaily,
    getUsageSummary,
} from '../api/adminApi'
import type {
    UsageDailySummary,
    UsageFilter,
    UsageModelSummary,
    UsageSummary,
    UsageUserSummary,
} from '../api/adminApi'
import { getApiErrorMessage } from '../api/http'
import { formatDate, formatUsd } from '../utils/format'

type Tab = 'summary' | 'users' | 'models' | 'daily'

type UsageRow = Record<string, string | number | null | undefined>

type UsageTableColumn<T extends UsageRow> = {
    key: keyof T
    title: string
    render?: (value: T[keyof T], row: T) => string
}

function AdminUsagePage() {
    const [tab, setTab] = useState<Tab>('summary')

    const [summary, setSummary] = useState<UsageSummary[]>([])
    const [users, setUsers] = useState<UsageUserSummary[]>([])
    const [models, setModels] = useState<UsageModelSummary[]>([])
    const [daily, setDaily] = useState<UsageDailySummary[]>([])

    const [error, setError] = useState('')
    const [loading, setLoading] = useState(true)

    const [draftDateFrom, setDraftDateFrom] = useState('')
    const [draftDateTo, setDraftDateTo] = useState('')
    const [draftModel, setDraftModel] = useState('')

    const [appliedFilter, setAppliedFilter] = useState<UsageFilter>({})

    useEffect(() => {
        void loadUsage(appliedFilter)
    }, [appliedFilter])

    const totals = useMemo(() => {
        return summary.reduce(
            (acc, row) => ({
                inputTokens: acc.inputTokens + row.inputTokens,
                outputTokens: acc.outputTokens + row.outputTokens,
                totalTokens: acc.totalTokens + row.totalTokens,
                costUsd: acc.costUsd + row.costUsd,
            }),
            {
                inputTokens: 0,
                outputTokens: 0,
                totalTokens: 0,
                costUsd: 0,
            }
        )
    }, [summary])

    async function loadUsage(filter: UsageFilter) {
        setLoading(true)
        setError('')

        try {
            const [summaryData, usersData, modelsData, dailyData] =
                await Promise.all([
                    getUsageSummary(filter),
                    getUsageByUsers(filter),
                    getUsageByModels(filter),
                    getUsageDaily(filter),
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

    function applyFilters() {
        setAppliedFilter({
            dateFrom: draftDateFrom ? `${draftDateFrom}T00:00:00Z` : undefined,
            dateTo: draftDateTo ? `${draftDateTo}T23:59:59Z` : undefined,
            model: draftModel.trim() || undefined,
        })
    }

    function resetFilters() {
        setDraftDateFrom('')
        setDraftDateTo('')
        setDraftModel('')
        setAppliedFilter({})
    }

    return (
        <div className="page">
            <h1>Admin Usage</h1>

            <div className="card usage-total-card">
                <div>
                    <strong>Total tokens</strong>
                    <p>{totals.totalTokens}</p>
                </div>

                <div>
                    <strong>Input tokens</strong>
                    <p>{totals.inputTokens}</p>
                </div>

                <div>
                    <strong>Output tokens</strong>
                    <p>{totals.outputTokens}</p>
                </div>

                <div>
                    <strong>Cost</strong>
                    <p>{formatUsd(totals.costUsd)}</p>
                </div>
            </div>

            <div className="card form-card">
                <div className="form">
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
                        Model
                        <input
                            value={draftModel}
                            onChange={(event) => setDraftModel(event.target.value)}
                            placeholder="mock-safeai"
                        />
                    </label>

                    <div className="user-actions">
                        <button type="button" onClick={applyFilters}>
                            Apply
                        </button>

                        <button
                            type="button"
                            className="secondary-button"
                            onClick={resetFilters}
                        >
                            Reset
                        </button>

                        <button
                            type="button"
                            className="secondary-button"
                            onClick={() => void loadUsage(appliedFilter)}
                        >
                            Refresh
                        </button>
                    </div>
                </div>
            </div>

            <div className="user-toolbar">
                <button
                    className={tab === 'summary' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('summary')}
                >
                    Summary
                </button>

                <button
                    className={tab === 'users' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('users')}
                >
                    By users
                </button>

                <button
                    className={tab === 'models' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('models')}
                >
                    By models
                </button>

                <button
                    className={tab === 'daily' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('daily')}
                >
                    Daily
                </button>
            </div>

            {loading && <p>Loading...</p>}
            {error && <div className="error">{error}</div>}

            {!loading && !error && (
                <>
                    <UsageCharts models={models} daily={daily} />

                    <div className="card table-card">
                        {tab === 'summary' && (
                            <UsageTable
                                rows={summary}
                                columns={[
                                    { key: 'userEmail', title: 'User' },
                                    { key: 'model', title: 'Model' },
                                    { key: 'inputTokens', title: 'Input tokens' },
                                    { key: 'outputTokens', title: 'Output tokens' },
                                    { key: 'totalTokens', title: 'Total tokens' },
                                    {
                                        key: 'costUsd',
                                        title: 'Cost USD',
                                        render: (value) => formatUsd(value as number),
                                    },
                                ]}
                                emptyText="No usage summary found."
                            />
                        )}

                        {tab === 'users' && (
                            <UsageTable
                                rows={users}
                                columns={[
                                    { key: 'userEmail', title: 'User' },
                                    { key: 'inputTokens', title: 'Input tokens' },
                                    { key: 'outputTokens', title: 'Output tokens' },
                                    { key: 'totalTokens', title: 'Total tokens' },
                                    {
                                        key: 'costUsd',
                                        title: 'Cost USD',
                                        render: (value) => formatUsd(value as number),
                                    },
                                ]}
                                emptyText="No user usage found."
                            />
                        )}

                        {tab === 'models' && (
                            <UsageTable
                                rows={models}
                                columns={[
                                    { key: 'model', title: 'Model' },
                                    { key: 'inputTokens', title: 'Input tokens' },
                                    { key: 'outputTokens', title: 'Output tokens' },
                                    { key: 'totalTokens', title: 'Total tokens' },
                                    {
                                        key: 'costUsd',
                                        title: 'Cost USD',
                                        render: (value) => formatUsd(value as number),
                                    },
                                ]}
                                emptyText="No model usage found."
                            />
                        )}

                        {tab === 'daily' && (
                            <UsageTable
                                rows={daily}
                                columns={[
                                    {
                                        key: 'usageDate',
                                        title: 'Date',
                                        render: (value) => formatDate(value as string),
                                    },
                                    { key: 'inputTokens', title: 'Input tokens' },
                                    { key: 'outputTokens', title: 'Output tokens' },
                                    { key: 'totalTokens', title: 'Total tokens' },
                                    {
                                        key: 'costUsd',
                                        title: 'Cost USD',
                                        render: (value) => formatUsd(value as number),
                                    },
                                ]}
                                emptyText="No daily usage found."
                            />
                        )}
                    </div>
                </>
            )}
        </div>
    )
}

function UsageCharts({
                         models,
                         daily,
                     }: {
    models: UsageModelSummary[]
    daily: UsageDailySummary[]
}) {
    if (models.length === 0 && daily.length === 0) {
        return null
    }

    return (
        <div className="usage-chart-grid">
            {daily.length > 0 && (
                <div className="card">
                    <h2>Daily usage</h2>

                    <div className="chart-box">
                        <ResponsiveContainer width="100%" height={260}>
                            <BarChart data={daily}>
                                <CartesianGrid strokeDasharray="3 3" />
                                <XAxis dataKey="usageDate" />
                                <YAxis />
                                <Tooltip />
                                <Bar dataKey="totalTokens" />
                            </BarChart>
                        </ResponsiveContainer>
                    </div>
                </div>
            )}

            {models.length > 0 && (
                <div className="card">
                    <h2>Model usage</h2>

                    <div className="chart-box">
                        <ResponsiveContainer width="100%" height={260}>
                            <BarChart data={models}>
                                <CartesianGrid strokeDasharray="3 3" />
                                <XAxis dataKey="model" />
                                <YAxis />
                                <Tooltip />
                                <Bar dataKey="totalTokens" />
                            </BarChart>
                        </ResponsiveContainer>
                    </div>
                </div>
            )}
        </div>
    )
}

function UsageTable<T extends UsageRow>({
                                            rows,
                                            columns,
                                            emptyText,
                                        }: {
    rows: T[]
    columns: UsageTableColumn<T>[]
    emptyText: string
}) {
    if (rows.length === 0) {
        return <p>{emptyText}</p>
    }

    return (
        <table>
            <thead>
            <tr>
                {columns.map((column) => (
                    <th key={String(column.key)}>{column.title}</th>
                ))}
            </tr>
            </thead>

            <tbody>
            {rows.map((row, index) => (
                <tr key={getUsageRowKey(row, index)}>
                    {columns.map((column) => {
                        const value = row[column.key]

                        return (
                            <td key={String(column.key)}>
                                {column.render
                                    ? column.render(value, row)
                                    : String(value ?? '-')}
                            </td>
                        )
                    })}
                </tr>
            ))}
            </tbody>
        </table>
    )
}

function getUsageRowKey(row: UsageRow, index: number): string {
    if (row.userId && row.model) {
        return `${row.userId}-${row.model}`
    }

    if (row.userId) {
        return String(row.userId)
    }

    if (row.model) {
        return String(row.model)
    }

    if (row.usageDate) {
        return String(row.usageDate)
    }

    return String(index)
}

export default AdminUsagePage