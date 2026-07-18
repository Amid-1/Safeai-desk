// frontend/src/pages/AdminUsagePage.tsx
import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
    getUsageByModels,
    getUsageByOrganization,
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
import type { Organization } from '../api/organizationApi'
import { loadAllOrganizations } from '../utils/organizations'
import { getApiErrorMessage } from '../api/http'
import { useAuth } from '../auth/AuthContext'
import { formatDate, formatUsd } from '../utils/format'
import {
    toUtcExclusiveEndOfDayIso,
    toUtcStartOfDayIso,
} from '../utils/date'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlock'

type Tab = 'summary' | 'users' | 'models' | 'daily'

type TabData = {
    summary: UsageSummary[]
    users: UsageUserSummary[]
    models: UsageModelSummary[]
    daily: UsageDailySummary[]
}

type UsageTableColumn<T extends object> = {
    key: keyof T
    title: string
    render?: (value: T[keyof T], row: T) => ReactNode
}

function AdminUsagePage() {
    const { currentUser } = useAuth()
    const superAdmin = currentUser?.roles.includes('SUPER_ADMIN') ?? false

    const [tab, setTab] = useState<Tab>('summary')
    const [data, setData] = useState<TabData>({
        summary: [],
        users: [],
        models: [],
        daily: [],
    })
    const [loading, setLoading] = useState(true)
    const [loadError, setLoadError] = useState('')
    const [filterError, setFilterError] = useState('')
    const [reloadToken, setReloadToken] = useState(0)

    const [draftDateFrom, setDraftDateFrom] = useState('')
    const [draftDateTo, setDraftDateTo] = useState('')
    const [draftModel, setDraftModel] = useState('')
    const [draftOrganizationId, setDraftOrganizationId] = useState('')
    const [appliedFilter, setAppliedFilter] = useState<UsageFilter>({})
    const [appliedOrganizationId, setAppliedOrganizationId] = useState('')

    const [organizations, setOrganizations] = useState<Organization[]>([])
    const [organizationsLoading, setOrganizationsLoading] = useState(false)

    const requestSequenceRef = useRef(0)
    const organizationsSequenceRef = useRef(0)

    useEffect(() => {
        if (!superAdmin) {
            setOrganizations([])
            setDraftOrganizationId('')
            setAppliedOrganizationId('')
            return
        }

        const sequence = ++organizationsSequenceRef.current

        async function loadOrganizations() {
            setOrganizationsLoading(true)

            try {
                const loaded = await loadAllOrganizations()

                if (sequence === organizationsSequenceRef.current) {
                    setOrganizations(loaded)
                }
            } catch {
                if (sequence === organizationsSequenceRef.current) {
                    setOrganizations([])
                }
            } finally {
                if (sequence === organizationsSequenceRef.current) {
                    setOrganizationsLoading(false)
                }
            }
        }

        void loadOrganizations()

        return () => {
            organizationsSequenceRef.current += 1
        }
    }, [superAdmin])

    useEffect(() => {
        const sequence = ++requestSequenceRef.current

        async function loadActiveTab() {
            setLoading(true)
            setLoadError('')

            try {
                const result = await loadTabData(tab, appliedFilter, appliedOrganizationId)

                if (sequence !== requestSequenceRef.current) {
                    return
                }

                setData((current) => ({
                    ...current,
                    [tab]: result,
                }))
            } catch (error) {
                if (sequence === requestSequenceRef.current) {
                    setLoadError(
                        getApiErrorMessage(
                            error,
                            'Не удалось загрузить статистику использования.'
                        )
                    )
                }
            } finally {
                if (sequence === requestSequenceRef.current) {
                    setLoading(false)
                }
            }
        }

        void loadActiveTab()

        return () => {
            requestSequenceRef.current += 1
        }
    }, [tab, appliedFilter, appliedOrganizationId, reloadToken])

    const activeRows = data[tab]

    const organizationFilterNote = useMemo(() => {
        if (!appliedOrganizationId) {
            return ''
        }

        if (tab === 'daily') {
            return 'Backend пока не предоставляет дневную статистику по отдельной организации. Для этой вкладки показаны данные без фильтра организации.'
        }

        return ''
    }, [appliedOrganizationId, tab])

    function applyFilters() {
        setFilterError('')

        if (draftDateFrom && draftDateTo && draftDateFrom > draftDateTo) {
            setFilterError('Дата начала периода не может быть позже даты окончания.')
            return
        }

        try {
            setAppliedFilter({
                dateFrom: draftDateFrom
                    ? toUtcStartOfDayIso(draftDateFrom)
                    : undefined,
                dateTo: draftDateTo
                    ? toUtcExclusiveEndOfDayIso(draftDateTo)
                    : undefined,
                model: draftModel.trim() || undefined,
            })
            setAppliedOrganizationId(superAdmin ? draftOrganizationId : '')
        } catch (error) {
            setFilterError(
                error instanceof Error ? error.message : 'Некорректный диапазон дат.'
            )
        }
    }

    function resetFilters() {
        setDraftDateFrom('')
        setDraftDateTo('')
        setDraftModel('')
        setDraftOrganizationId('')
        setAppliedFilter({})
        setAppliedOrganizationId('')
        setFilterError('')
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
                            min={draftDateFrom || undefined}
                            onChange={(event) => setDraftDateTo(event.target.value)}
                        />
                    </label>

                    <label>
                        Модель
                        <input
                            value={draftModel}
                            onChange={(event) => setDraftModel(event.target.value)}
                            placeholder="mock-safeai"
                            maxLength={100}
                        />
                    </label>

                    {superAdmin && (
                        <label>
                            Организация
                            <select
                                value={draftOrganizationId}
                                disabled={organizationsLoading}
                                onChange={(event) => setDraftOrganizationId(event.target.value)}
                            >
                                <option value="">
                                    {organizationsLoading
                                        ? 'Загрузка организаций...'
                                        : 'Все организации'}
                                </option>
                                {organizations.map((organization) => (
                                    <option key={organization.id} value={organization.id}>
                                        {organization.name}
                                    </option>
                                ))}
                            </select>
                        </label>
                    )}

                    {filterError && <div className="error">{filterError}</div>}

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
                        Период задаётся в UTC. Фильтр модели применяется к сводке и к отчёту выбранной организации.
                    </small>
                </div>
            </div>

            <div className="user-toolbar" role="tablist" aria-label="Отчёты использования">
                {([
                    ['summary', 'Сводка'],
                    ['users', 'По пользователям'],
                    ['models', 'По моделям'],
                    ['daily', 'По дням'],
                ] as const).map(([value, label]) => (
                    <button
                        key={value}
                        type="button"
                        role="tab"
                        aria-selected={tab === value}
                        className={tab === value ? 'filter-button active' : 'filter-button'}
                        onClick={() => setTab(value)}
                    >
                        {label}
                    </button>
                ))}
            </div>

            {organizationFilterNote && (
                <div className="error">{organizationFilterNote}</div>
            )}

            {loading && <LoadingState message="Загрузка статистики использования..." />}

            {!loading && loadError && (
                <ErrorState
                    title="Ошибка загрузки"
                    message={loadError}
                    action={
                        <button type="button" onClick={() => setReloadToken((value) => value + 1)}>
                            Повторить
                        </button>
                    }
                />
            )}

            {!loading && !loadError && (
                <div className="card table-card">
                    {tab === 'summary' && (
                        <UsageTable
                            rows={data.summary}
                            columns={summaryColumns}
                            emptyText="Сводка использования не найдена."
                        />
                    )}
                    {tab === 'users' && (
                        <UsageTable
                            rows={data.users}
                            columns={userColumns}
                            emptyText="Статистика по пользователям не найдена."
                        />
                    )}
                    {tab === 'models' && (
                        <UsageTable
                            rows={data.models}
                            columns={modelColumns}
                            emptyText="Статистика по моделям не найдена."
                        />
                    )}
                    {tab === 'daily' && (
                        <UsageTable
                            rows={data.daily}
                            columns={dailyColumns}
                            emptyText="Дневная статистика не найдена."
                        />
                    )}
                </div>
            )}

            {!loading && !loadError && activeRows.length > 0 && (
                <p className="muted">Строк в отчёте: {activeRows.length}</p>
            )}
        </div>
    )
}

async function loadTabData(
    tab: Tab,
    filter: UsageFilter,
    organizationId: string
): Promise<TabData[Tab]> {
    if (organizationId && tab !== 'daily') {
        const summary = await getUsageByOrganization(organizationId, filter)

        if (tab === 'summary') {
            return summary
        }

        if (tab === 'users') {
            return aggregateByUsers(summary)
        }

        return aggregateByModels(summary)
    }

    const dateOnlyFilter = {
        dateFrom: filter.dateFrom,
        dateTo: filter.dateTo,
    }

    switch (tab) {
        case 'summary':
            return getUsageSummary(filter)
        case 'users':
            return getUsageByUsers(dateOnlyFilter)
        case 'models':
            return getUsageByModels(dateOnlyFilter)
        case 'daily':
            return getUsageDaily(dateOnlyFilter)
    }
}

function aggregateByUsers(rows: UsageSummary[]): UsageUserSummary[] {
    const grouped = new Map<string, UsageUserSummary>()

    rows.forEach((row) => {
        const current = grouped.get(row.userId)
        grouped.set(row.userId, {
            userId: row.userId,
            userEmail: row.userEmail,
            inputTokens: (current?.inputTokens ?? 0) + row.inputTokens,
            outputTokens: (current?.outputTokens ?? 0) + row.outputTokens,
            totalTokens: (current?.totalTokens ?? 0) + row.totalTokens,
            costUsd: (current?.costUsd ?? 0) + row.costUsd,
        })
    })

    return [...grouped.values()].sort((a, b) => a.userEmail.localeCompare(b.userEmail))
}

function aggregateByModels(rows: UsageSummary[]): UsageModelSummary[] {
    const grouped = new Map<string, UsageModelSummary>()

    rows.forEach((row) => {
        const current = grouped.get(row.model)
        grouped.set(row.model, {
            model: row.model,
            inputTokens: (current?.inputTokens ?? 0) + row.inputTokens,
            outputTokens: (current?.outputTokens ?? 0) + row.outputTokens,
            totalTokens: (current?.totalTokens ?? 0) + row.totalTokens,
            costUsd: (current?.costUsd ?? 0) + row.costUsd,
        })
    })

    return [...grouped.values()].sort((a, b) => a.model.localeCompare(b.model))
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
        return <EmptyState message={emptyText} />
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
                                    : String(value ?? '—')}
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
    const value = row as Record<string, unknown>

    if (value.userId && value.model) {
        return `${String(value.userId)}-${String(value.model)}`
    }
    if (value.userId) {
        return String(value.userId)
    }
    if (value.model) {
        return String(value.model)
    }
    if (value.usageDate) {
        return String(value.usageDate)
    }

    return String(index)
}

const summaryColumns: UsageTableColumn<UsageSummary>[] = [
    { key: 'userEmail', title: 'Пользователь' },
    { key: 'model', title: 'Модель' },
    { key: 'inputTokens', title: 'Входные токены' },
    { key: 'outputTokens', title: 'Выходные токены' },
    { key: 'totalTokens', title: 'Всего токенов' },
    { key: 'costUsd', title: 'Стоимость USD', render: (value) => formatUsd(value as number) },
]

const userColumns: UsageTableColumn<UsageUserSummary>[] = [
    { key: 'userEmail', title: 'Пользователь' },
    { key: 'inputTokens', title: 'Входные токены' },
    { key: 'outputTokens', title: 'Выходные токены' },
    { key: 'totalTokens', title: 'Всего токенов' },
    { key: 'costUsd', title: 'Стоимость USD', render: (value) => formatUsd(value as number) },
]

const modelColumns: UsageTableColumn<UsageModelSummary>[] = [
    { key: 'model', title: 'Модель' },
    { key: 'inputTokens', title: 'Входные токены' },
    { key: 'outputTokens', title: 'Выходные токены' },
    { key: 'totalTokens', title: 'Всего токенов' },
    { key: 'costUsd', title: 'Стоимость USD', render: (value) => formatUsd(value as number) },
]

const dailyColumns: UsageTableColumn<UsageDailySummary>[] = [
    { key: 'usageDate', title: 'Дата', render: (value) => formatDate(value as string) },
    { key: 'inputTokens', title: 'Входные токены' },
    { key: 'outputTokens', title: 'Выходные токены' },
    { key: 'totalTokens', title: 'Всего токенов' },
    { key: 'costUsd', title: 'Стоимость USD', render: (value) => formatUsd(value as number) },
]

export default AdminUsagePage
