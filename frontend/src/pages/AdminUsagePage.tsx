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
        async function loadUsage() {
            setLoading(true)
            setError('')

            try {
                const dateOnlyFilter = {
                    dateFrom: appliedFilter.dateFrom,
                    dateTo: appliedFilter.dateTo,
                }

                const [summaryData, usersData, modelsData, dailyData] =
                    await Promise.all([
                        getUsageSummary(appliedFilter),
                        getUsageByUsers(dateOnlyFilter),
                        getUsageByModels(dateOnlyFilter),
                        getUsageDaily(dateOnlyFilter),
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
    }, [appliedFilter])

    function applyFilters() {
        setAppliedFilter({
            dateFrom: draftDateFrom ? `${draftDateFrom}T00:00:00Z` : undefined,
            dateTo: draftDateTo ? `${addOneDay(draftDateTo)}T00:00:00Z` : undefined,
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
                        <button type="button" disabled={loading} onClick={applyFilters}>
                            Apply filters
                        </button>

                        <button
                            type="button"
                            className="secondary-button"
                            disabled={loading}
                            onClick={resetFilters}
                        >
                            Reset filters
                        </button>
                    </div>

                    <small className="muted">
                        Model filter applies to summary endpoint. Daily usage is grouped by UTC date.
                    </small>
                </div>
            </div>

            <div className="user-toolbar">
                <button
                    type="button"
                    className={tab === 'summary' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('summary')}
                >
                    Summary
                </button>

                <button
                    type="button"
                    className={tab === 'users' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('users')}
                >
                    By users
                </button>

                <button
                    type="button"
                    className={tab === 'models' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('models')}
                >
                    By models
                </button>

                <button
                    type="button"
                    className={tab === 'daily' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('daily')}
                >
                    Daily
                </button>
            </div>

            {loading && <p>Loading...</p>}
            {error && <div className="error">{error}</div>}

            {!loading && !error && (
                <div className="card">
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

function addOneDay(dateValue: string): string {
    const [year, month, day] = dateValue
        .split('-')
        .map((part) => Number(part))

    const date = new Date(Date.UTC(year, month - 1, day))
    date.setUTCDate(date.getUTCDate() + 1)

    return date.toISOString().slice(0, 10)
}

export default AdminUsagePage