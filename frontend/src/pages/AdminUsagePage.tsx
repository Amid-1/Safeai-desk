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
import { EmptyState, LoadingState } from '../components/StateBlock'

type Tab = 'summary' | 'users' | 'models' | 'daily'

type UsageRow = Record<string, string | number | null | undefined>

type UsageTableColumn<T extends object> = {
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
                setError(getApiErrorMessage(err, 'Не удалось загрузить статистику использования.'))
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
            <h1>Использование AI</h1>

            <div className="card form-card">
                <div className="form">
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

                    <label>
                        Модель
                        <input
                            value={draftModel}
                            onChange={(event) => setDraftModel(event.target.value)}
                            placeholder="mock-safeai"
                        />
                    </label>

                    <div className="filter-actions">
                        <button type="button" disabled={loading} onClick={applyFilters}>
                            Применить фильтры
                        </button>

                        <button
                            type="button"
                            className="secondary-button"
                            disabled={loading}
                            onClick={resetFilters}
                        >
                            Сбросить фильтры
                        </button>
                    </div>

                    <small className="muted">
                        Фильтр по модели применяется только к сводке. Дневная статистика группируется по UTC.
                    </small>
                </div>
            </div>

            <div className="user-toolbar">
                <button
                    type="button"
                    className={tab === 'summary' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('summary')}
                >
                    Сводка
                </button>

                <button
                    type="button"
                    className={tab === 'users' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('users')}
                >
                    По пользователям
                </button>

                <button
                    type="button"
                    className={tab === 'models' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('models')}
                >
                    По моделям
                </button>

                <button
                    type="button"
                    className={tab === 'daily' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setTab('daily')}
                >
                    По дням
                </button>
            </div>

            {loading && <LoadingState message="Загрузка статистики использования..." />}
            {error && <div className="error">{error}</div>}

            {!loading && !error && (
                <div className="card table-card">
                    {tab === 'summary' && (
                        <UsageTable
                            rows={summary}
                            columns={[
                                { key: 'userEmail', title: 'Пользователь' },
                                { key: 'model', title: 'Модель' },
                                { key: 'inputTokens', title: 'Входные токены' },
                                { key: 'outputTokens', title: 'Выходные токены' },
                                { key: 'totalTokens', title: 'Всего токенов' },
                                {
                                    key: 'costUsd',
                                    title: 'Стоимость USD',
                                    render: (value) => formatUsd(value as number),
                                },
                            ]}
                            emptyText="Сводка использования не найдена."
                        />
                    )}

                    {tab === 'users' && (
                        <UsageTable
                            rows={users}
                            columns={[
                                { key: 'userEmail', title: 'Пользователь' },
                                { key: 'inputTokens', title: 'Входные токены' },
                                { key: 'outputTokens', title: 'Выходные токены' },
                                { key: 'totalTokens', title: 'Всего токенов' },
                                {
                                    key: 'costUsd',
                                    title: 'Стоимость USD',
                                    render: (value) => formatUsd(value as number),
                                },
                            ]}
                            emptyText="Статистика по пользователям не найдена."
                        />
                    )}

                    {tab === 'models' && (
                        <UsageTable
                            rows={models}
                            columns={[
                                { key: 'model', title: 'Модель' },
                                { key: 'inputTokens', title: 'Входные токены' },
                                { key: 'outputTokens', title: 'Выходные токены' },
                                { key: 'totalTokens', title: 'Всего токенов' },
                                {
                                    key: 'costUsd',
                                    title: 'Стоимость USD',
                                    render: (value) => formatUsd(value as number),
                                },
                            ]}
                            emptyText="Статистика по моделям не найдена."
                        />
                    )}

                    {tab === 'daily' && (
                        <UsageTable
                            rows={daily}
                            columns={[
                                {
                                    key: 'usageDate',
                                    title: 'Дата',
                                    render: (value) => formatDate(value as string),
                                },
                                { key: 'inputTokens', title: 'Входные токены' },
                                { key: 'outputTokens', title: 'Выходные токены' },
                                { key: 'totalTokens', title: 'Всего токенов' },
                                {
                                    key: 'costUsd',
                                    title: 'Стоимость USD',
                                    render: (value) => formatUsd(value as number),
                                },
                            ]}
                            emptyText="Дневная статистика не найдена."
                        />
                    )}
                </div>
            )}
        </div>
    )
}

function UsageTable<T extends object>({
                                          rows,
                                          columns,
                                          emptyText,
                                      }: {
    rows: T[]
    columns: UsageTableColumn<T>[]
    emptyText: string
}) {
    if (rows.length === 0) {
        return (
            <EmptyState
                title="Нет данных"
                message={emptyText}
            />
        )
    }

    return (
        <table className="admin-table usage-table">
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

function getUsageRowKey(row: object, index: number): string {
    const usageRow = row as UsageRow

    if (usageRow.userId && usageRow.model) {
        return `${usageRow.userId}-${usageRow.model}`
    }

    if (usageRow.userId) {
        return String(usageRow.userId)
    }

    if (usageRow.model) {
        return String(usageRow.model)
    }

    if (usageRow.usageDate) {
        return String(usageRow.usageDate)
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
