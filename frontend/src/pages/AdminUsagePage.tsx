import {
    useEffect,
    useMemo,
    useRef,
    useState,
} from 'react'
import type {
    ReactNode,
} from 'react'
import {
    getOrganizationUsageDaily,
    getOrganizationUsageModels,
    getOrganizationUsageUsers,
    getUsageByModels,
    getUsageByOrganization,
    getUsageByUsers,
    getUsageDaily,
    getUsageSummary,
} from '../api/adminApi'
import type {
    UsageCoverage,
    UsageDailySummary,
    UsageFilter,
    UsageModelSummary,
    UsageSummary,
    UsageUserSummary,
} from '../api/adminApi'
import {
    searchOrganizationDirectory,
} from '../api/organizationApi'
import type {
    OrganizationDirectoryItem,
} from '../api/organizationApi'
import {
    ApiError,
    getApiErrorMessage,
} from '../api/http'
import {
    useAuth,
} from '../auth/AuthContext'
import {
    formatDate,
    formatIntegerValue,
    formatUsd,
} from '../utils/format'
import type {
    PageResponse,
} from '../utils/page'
import {
    pageFromArray,
} from '../utils/page'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import PageErrorBoundary
    from '../components/PageErrorBoundary'
import {
    buildUsageSearch,
    createDefaultUsageDraftFilter,
    readUsageUrlState,
    toUsageFilter,
    usageDraftFiltersEqual,
} from './adminUsage.helpers'
import type {
    UsageDraftFilter,
    UsageTab,
} from './adminUsage.helpers'

const PAGE_SIZE = 50

type UsageRow =
    | UsageSummary
    | UsageUserSummary
    | UsageModelSummary
    | UsageDailySummary

type UsageReport =
    | {
        tab: 'summary'
        page:
            PageResponse<UsageSummary>
    }
    | {
        tab: 'users'
        page:
            PageResponse<UsageUserSummary>
    }
    | {
        tab: 'models'
        page:
            PageResponse<UsageModelSummary>
    }
    | {
        tab: 'daily'
        page:
            PageResponse<UsageDailySummary>
    }

function AdminUsagePage() {
    return (
        <PageErrorBoundary>
            <AdminUsagePageContent />
        </PageErrorBoundary>
    )
}

function AdminUsagePageContent() {
    const { currentUser } = useAuth()

    const superAdmin =
        currentUser?.roles.includes(
            'SUPER_ADMIN',
        ) ?? false

    const initialState =
        useMemo(
            () =>
                readUsageUrlState(
                    window.location.search,
                    superAdmin,
                ),
            [superAdmin],
        )

    const initialApplied =
        useMemo(
            () =>
                toUsageFilter(
                    initialState.draft,
                    superAdmin,
                ),
            [
                initialState,
                superAdmin,
            ],
        )

    const [tab, setTab] =
        useState<UsageTab>(
            initialState.tab,
        )

    const [page, setPage] =
        useState(initialState.page)

    const [report, setReport] =
        useState<UsageReport | null>(
            null,
        )

    const [loading, setLoading] =
        useState(true)

    const [
        loadError,
        setLoadError,
    ] = useState('')

    const [
        filterError,
        setFilterError,
    ] = useState('')

    const [
        reloadToken,
        setReloadToken,
    ] = useState(0)

    const [
        draftFilter,
        setDraftFilter,
    ] = useState<UsageDraftFilter>(
        initialApplied.draft,
    )

    const [
        appliedDraftFilter,
        setAppliedDraftFilter,
    ] = useState<UsageDraftFilter>(
        initialApplied.draft,
    )

    const [
        appliedFilter,
        setAppliedFilter,
    ] = useState<UsageFilter>(
        initialApplied.filter,
    )

    const [
        appliedOrganizationId,
        setAppliedOrganizationId,
    ] = useState(
        initialApplied.organizationId,
    )

    const [
        organizations,
        setOrganizations,
    ] = useState<
        OrganizationDirectoryItem[]
    >([])

    const [
        organizationSearch,
        setOrganizationSearch,
    ] = useState('')

    const [
        organizationsLoading,
        setOrganizationsLoading,
    ] = useState(false)

    const [
        organizationsError,
        setOrganizationsError,
    ] = useState('')

    const requestSequenceRef =
        useRef(0)

    const organizationSequenceRef =
        useRef(0)

    const organizationControllerRef =
        useRef<AbortController | null>(
            null,
        )

    const filtersDirty =
        useMemo(
            () =>
                !usageDraftFiltersEqual(
                    draftFilter,
                    appliedDraftFilter,
                ),
            [
                draftFilter,
                appliedDraftFilter,
            ],
        )

    const selectedOrganization =
        useMemo(
            () =>
                organizations.find(
                    (organization) =>
                        organization.id
                        === appliedOrganizationId,
                ) ?? null,
            [
                organizations,
                appliedOrganizationId,
            ],
        )

    useEffect(() => {
        if (!superAdmin) {
            organizationControllerRef.current
                ?.abort()
            setOrganizations([])
            setOrganizationsError('')
            setOrganizationSearch('')
            setDraftFilter(
                (current) => ({
                    ...current,
                    organizationId: '',
                }),
            )
            setAppliedDraftFilter(
                (current) => ({
                    ...current,
                    organizationId: '',
                }),
            )
            setAppliedOrganizationId('')
            return
        }

        const timerId =
            window.setTimeout(
                () => {
                    const sequence =
                        ++organizationSequenceRef.current

                    organizationControllerRef.current
                        ?.abort()

                    const controller =
                        new AbortController()

                    organizationControllerRef.current =
                        controller

                    setOrganizationsLoading(
                        true,
                    )
                    setOrganizationsError('')

                    void searchOrganizationDirectory(
                        organizationSearch,
                        50,
                        {
                            signal:
                                controller.signal,
                        },
                    )
                        .then((result) => {
                            if (
                                sequence
                                === organizationSequenceRef.current
                            ) {
                                setOrganizations(
                                    result,
                                )
                            }
                        })
                        .catch((error) => {
                            if (
                                sequence
                                === organizationSequenceRef.current
                                && !isRequestAborted(
                                    error,
                                )
                            ) {
                                setOrganizationsError(
                                    getApiErrorMessage(
                                        error,
                                        'Не удалось загрузить каталог организаций. Текущий scope не изменён.',
                                    ),
                                )
                            }
                        })
                        .finally(() => {
                            if (
                                sequence
                                === organizationSequenceRef.current
                            ) {
                                setOrganizationsLoading(
                                    false,
                                )
                            }
                        })
                },
                300,
            )

        return () => {
            window.clearTimeout(
                timerId,
            )
            organizationControllerRef.current
                ?.abort()
            organizationSequenceRef.current
                += 1
        }
    }, [
        superAdmin,
        organizationSearch,
    ])

    useEffect(() => {
        const sequence =
            ++requestSequenceRef.current

        const controller =
            new AbortController()

        async function loadReport() {
            setLoading(true)
            setLoadError('')

            try {
                const loaded =
                    await loadUsageReport(
                        tab,
                        page,
                        appliedFilter,
                        appliedOrganizationId,
                        controller.signal,
                    )

                if (
                    sequence
                    !== requestSequenceRef.current
                ) {
                    return
                }

                if (
                    loaded.page.totalPages
                        === 0
                    && page !== 0
                ) {
                    setPage(0)
                    return
                }

                if (
                    loaded.page.totalPages
                        > 0
                    && page
                        >= loaded.page.totalPages
                ) {
                    setPage(
                        loaded.page
                            .totalPages - 1,
                    )
                    return
                }

                setReport(loaded)
            } catch (error) {
                if (
                    sequence
                    === requestSequenceRef.current
                    && !isRequestAborted(
                        error,
                    )
                ) {
                    setReport(null)
                    setLoadError(
                        getUsageLoadError(
                            error,
                            appliedOrganizationId,
                            tab,
                        ),
                    )
                }
            } finally {
                if (
                    sequence
                    === requestSequenceRef.current
                ) {
                    setLoading(false)
                }
            }
        }

        void loadReport()

        return () => {
            controller.abort()
            requestSequenceRef.current += 1
        }
    }, [
        tab,
        page,
        appliedFilter.dateFrom,
        appliedFilter.dateTo,
        appliedFilter.model,
        appliedOrganizationId,
        reloadToken,
    ])

    useEffect(() => {
        const query =
            buildUsageSearch(
                tab,
                appliedDraftFilter,
                page,
                superAdmin,
            )

        const nextUrl =
            `${window.location.pathname}`
            + query
            + window.location.hash

        window.history.replaceState(
            window.history.state,
            '',
            nextUrl,
        )
    }, [
        tab,
        appliedDraftFilter,
        page,
        superAdmin,
    ])

    function applyFilters() {
        setFilterError('')

        try {
            const normalized =
                toUsageFilter(
                    draftFilter,
                    superAdmin,
                )

            setLoading(true)
            setPage(0)
            setDraftFilter(
                normalized.draft,
            )
            setAppliedDraftFilter(
                normalized.draft,
            )
            setAppliedFilter(
                normalized.filter,
            )
            setAppliedOrganizationId(
                normalized.organizationId,
            )
            setReloadToken(
                (value) => value + 1,
            )
        } catch (error) {
            setFilterError(
                error instanceof Error
                    ? error.message
                    : (
                        'Некорректные '
                        + 'фильтры usage.'
                    ),
            )
        }
    }

    function resetFilters() {
        const reset =
            createDefaultUsageDraftFilter()

        const normalized =
            toUsageFilter(
                reset,
                superAdmin,
            )

        setLoading(true)
        setDraftFilter(
            normalized.draft,
        )
        setAppliedDraftFilter(
            normalized.draft,
        )
        setAppliedFilter(
            normalized.filter,
        )
        setAppliedOrganizationId('')
        setOrganizationSearch('')
        setFilterError('')
        setPage(0)
        setReloadToken(
            (value) => value + 1,
        )
    }

    function changeTab(
        nextTab: UsageTab,
    ) {
        if (loading || nextTab === tab) {
            return
        }

        setLoading(true)
        setTab(nextTab)
        setPage(0)
    }

    const currentPage =
        report?.page ?? null

    const scopeLabel =
        getScopeLabel(
            superAdmin,
            currentUser?.organizationId
                ?? null,
            appliedOrganizationId,
            selectedOrganization?.name
                ?? null,
        )

    return (
        <div className="page">
            <h1>Использование AI</h1>

            <div
                className="card"
                role="status"
                aria-live="polite"
            >
                <strong>
                    Scope:
                    {' '}
                    {scopeLabel}
                </strong>

                <p className="muted">
                    Период интерпретируется
                    как UTC calendar days.
                    DateTo является exclusive.
                    Денежные суммы не
                    агрегируются в JavaScript.
                </p>
            </div>

            <section
                className="card form-card"
                aria-labelledby={
                    'usage-filters-title'
                }
            >
                <h2 id="usage-filters-title">
                    Фильтры
                </h2>

                <div className="form">
                    <label>
                        Дата с

                        <input
                            type="date"
                            value={
                                draftFilter.dateFrom
                            }
                            max={
                                draftFilter.dateTo
                                || undefined
                            }
                            required
                            disabled={loading}
                            onChange={(event) =>
                                setDraftFilter(
                                    (current) => ({
                                        ...current,
                                        dateFrom:
                                            event.target.value,
                                    }),
                                )
                            }
                        />
                    </label>

                    <label>
                        Дата по

                        <input
                            type="date"
                            value={
                                draftFilter.dateTo
                            }
                            min={
                                draftFilter.dateFrom
                                || undefined
                            }
                            required
                            disabled={loading}
                            onChange={(event) =>
                                setDraftFilter(
                                    (current) => ({
                                        ...current,
                                        dateTo:
                                            event.target.value,
                                    }),
                                )
                            }
                        />
                    </label>

                    <label>
                        Модель

                        <input
                            value={
                                draftFilter.model
                            }
                            maxLength={100}
                            disabled={
                                loading
                                || tab !== 'summary'
                            }
                            placeholder="mock-safeai"
                            onChange={(event) =>
                                setDraftFilter(
                                    (current) => ({
                                        ...current,
                                        model:
                                            event.target.value,
                                    }),
                                )
                            }
                        />
                        <small className="muted">
                            Фильтр модели применяется
                            только к вкладке «Сводка».
                        </small>
                    </label>

                    {superAdmin && (
                        <>
                            <label>
                                Найти организацию

                                <input
                                    type="search"
                                    value={
                                        organizationSearch
                                    }
                                    maxLength={255}
                                    disabled={
                                        loading
                                    }
                                    onChange={(event) =>
                                        setOrganizationSearch(
                                            event.target.value,
                                        )
                                    }
                                />
                            </label>

                            <label>
                                Scope организации

                                <select
                                    value={
                                        draftFilter
                                            .organizationId
                                    }
                                    disabled={
                                        loading
                                        || organizationsLoading
                                    }
                                    onChange={(event) =>
                                        setDraftFilter(
                                            (current) => ({
                                                ...current,
                                                organizationId:
                                                    event.target.value,
                                            }),
                                        )
                                    }
                                >
                                    <option value="">
                                        GLOBAL —
                                        все организации
                                    </option>

                                    {includeSelectedOrganization(
                                        organizations,
                                        draftFilter
                                            .organizationId,
                                    ).map(
                                        (
                                            organization,
                                        ) => (
                                            <option
                                                key={
                                                    organization.id
                                                }
                                                value={
                                                    organization.id
                                                }
                                            >
                                                {
                                                    organization.name
                                                }
                                                {' '}
                                                (
                                                {
                                                    organization.id
                                                }
                                                )
                                            </option>
                                        ),
                                    )}
                                </select>
                            </label>
                        </>
                    )}

                    {filtersDirty && (
                        <div
                            className="warning"
                            role="status"
                            aria-live="polite"
                        >
                            Фильтры изменены.
                            Отчёт ещё показывает
                            применённые значения.
                        </div>
                    )}

                    {organizationsError && (
                        <div
                            className="error"
                            role="alert"
                            aria-live="assertive"
                        >
                            {organizationsError}
                        </div>
                    )}

                    {filterError && (
                        <div
                            className="error"
                            role="alert"
                            aria-live="assertive"
                        >
                            {filterError}
                        </div>
                    )}

                    <div className="filter-actions">
                        <button
                            type="button"
                            disabled={
                                loading
                                || !filtersDirty
                            }
                            onClick={
                                applyFilters
                            }
                        >
                            Применить фильтры
                        </button>

                        <button
                            type="button"
                            className={
                                'secondary-button'
                            }
                            disabled={loading}
                            onClick={
                                resetFilters
                            }
                        >
                            Сбросить к 30 дням
                        </button>
                    </div>
                </div>
            </section>

            <nav
                className="user-toolbar"
                aria-label={
                    'Отчёты использования'
                }
            >
                {USAGE_TABS.map(
                    ([value, label]) => (
                        <button
                            key={value}
                            type="button"
                            className={
                                tab === value
                                    ? (
                                        'filter-button '
                                        + 'active'
                                    )
                                    : 'filter-button'
                            }
                            aria-pressed={
                                tab === value
                            }
                            disabled={loading}
                            onClick={() =>
                                changeTab(value)
                            }
                        >
                            {label}
                        </button>
                    ),
                )}
            </nav>

            {tab !== 'summary'
                && appliedFilter.model
                && (
                    <div
                        className="warning"
                        role="status"
                        aria-live="polite"
                    >
                        Применённый фильтр модели
                        относится только к вкладке
                        «Сводка» и не отправляется
                        в текущий отчёт.
                    </div>
                )}

            {loading && (
                <LoadingState
                    message={
                        'Загрузка usage-отчёта...'
                    }
                />
            )}

            {!loading
                && loadError
                && (
                    <ErrorState
                        title="Ошибка загрузки"
                        message={loadError}
                        action={
                            <button
                                type="button"
                                onClick={() => {
                                    setLoading(true)
                                    setReloadToken(
                                        (value) =>
                                            value + 1,
                                    )
                                }}
                            >
                                Повторить
                            </button>
                        }
                    />
                )}

            {!loading
                && !loadError
                && report
                && (
                    <UsageReportTable
                        report={report}
                    />
                )}

            {!loading
                && !loadError
                && currentPage
                && currentPage.totalPages
                    > 1
                && (
                    <UsagePagination
                        page={
                            currentPage.page
                        }
                        totalPages={
                            currentPage
                                .totalPages
                        }
                        totalElements={
                            currentPage
                                .totalElements
                        }
                        onPageChange={
                            (nextPage) => {
                                setLoading(true)
                                setPage(nextPage)
                            }
                        }
                    />
                )}
        </div>
    )
}

const USAGE_TABS:
    readonly [
        UsageTab,
        string,
    ][] = [
    ['summary', 'Сводка'],
    ['users', 'По пользователям'],
    ['models', 'По моделям'],
    ['daily', 'По дням'],
]

async function loadUsageReport(
    tab: UsageTab,
    page: number,
    filter: UsageFilter,
    organizationId: string,
    signal: AbortSignal,
): Promise<UsageReport> {
    const dateOnlyFilter = {
        dateFrom: filter.dateFrom,
        dateTo: filter.dateTo,
    }

    if (organizationId) {
        switch (tab) {
            case 'summary':
                return {
                    tab,
                    page:
                        await getUsageByOrganization(
                            organizationId,
                            page,
                            PAGE_SIZE,
                            filter,
                            {
                                signal,
                            },
                        ),
                }

            case 'users':
                return {
                    tab,
                    page:
                        await getOrganizationUsageUsers(
                            organizationId,
                            page,
                            PAGE_SIZE,
                            dateOnlyFilter,
                            {
                                signal,
                            },
                        ),
                }

            case 'models':
                return {
                    tab,
                    page: pageFromArray(
                        await getOrganizationUsageModels(
                            organizationId,
                            dateOnlyFilter,
                            {
                                signal,
                            },
                        ),
                    ),
                }

            case 'daily':
                return {
                    tab,
                    page: pageFromArray(
                        await getOrganizationUsageDaily(
                            organizationId,
                            dateOnlyFilter,
                            {
                                signal,
                            },
                        ),
                    ),
                }
        }
    }

    switch (tab) {
        case 'summary':
            return {
                tab,
                page:
                    await getUsageSummary(
                        page,
                        PAGE_SIZE,
                        filter,
                        {
                            signal,
                        },
                    ),
            }

        case 'users':
            return {
                tab,
                page:
                    await getUsageByUsers(
                        page,
                        PAGE_SIZE,
                        dateOnlyFilter,
                        {
                            signal,
                        },
                    ),
            }

        case 'models':
            return {
                tab,
                page: pageFromArray(
                    await getUsageByModels(
                        dateOnlyFilter,
                        {
                            signal,
                        },
                    ),
                ),
            }

        case 'daily':
            return {
                tab,
                page: pageFromArray(
                    await getUsageDaily(
                        dateOnlyFilter,
                        {
                            signal,
                        },
                    ),
                ),
            }
    }
}

function UsageReportTable({
    report,
}: {
    report: UsageReport
}) {
    switch (report.tab) {
        case 'summary':
            return (
                <UsageTable
                    rows={
                        report.page.content
                    }
                    columns={
                        summaryColumns
                    }
                    emptyText={
                        'Сводка использования не найдена.'
                    }
                />
            )

        case 'users':
            return (
                <UsageTable
                    rows={
                        report.page.content
                    }
                    columns={
                        userColumns
                    }
                    emptyText={
                        'Статистика по пользователям не найдена.'
                    }
                />
            )

        case 'models':
            return (
                <UsageTable
                    rows={
                        report.page.content
                    }
                    columns={
                        modelColumns
                    }
                    emptyText={
                        'Статистика по моделям не найдена.'
                    }
                />
            )

        case 'daily':
            return (
                <UsageTable
                    rows={
                        report.page.content
                    }
                    columns={
                        dailyColumns
                    }
                    emptyText={
                        'Дневная статистика не найдена.'
                    }
                />
            )
    }
}

type UsageTableColumn<
    T extends object,
> = {
    key: string
    title: string
    render: (row: T) => ReactNode
}

function UsageTable<
    T extends UsageRow,
>({
    rows,
    columns,
    emptyText,
}: {
    rows: T[]
    columns:
        UsageTableColumn<T>[]
    emptyText: string
}) {
    if (rows.length === 0) {
        return (
            <div className="card table-card">
                <EmptyState
                    variant="inline"
                    message={emptyText}
                />
            </div>
        )
    }

    return (
        <div className="card table-card">
            <div className="admin-table-wrapper">
                <table
                    className={
                        'admin-table usage-table'
                    }
                >
                    <thead>
                        <tr>
                            {columns.map(
                                (column) => (
                                    <th
                                        key={
                                            column.key
                                        }
                                    >
                                        {
                                            column.title
                                        }
                                    </th>
                                ),
                            )}
                        </tr>
                    </thead>

                    <tbody>
                        {rows.map(
                            (row, index) => (
                                <tr
                                    key={
                                        getUsageRowKey(
                                            row,
                                            index,
                                        )
                                    }
                                >
                                    {columns.map(
                                        (column) => (
                                            <td
                                                key={
                                                    column.key
                                                }
                                            >
                                                {
                                                    column.render(
                                                        row,
                                                    )
                                                }
                                            </td>
                                        ),
                                    )}
                                </tr>
                            ),
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    )
}

function UsagePagination({
    page,
    totalPages,
    totalElements,
    onPageChange,
}: {
    page: number
    totalPages: number
    totalElements: number
    onPageChange:
        (page: number) => void
}) {
    return (
        <nav
            className="pagination"
            aria-label={
                'Пагинация usage-отчёта'
            }
        >
            <button
                type="button"
                className="secondary-button"
                disabled={page === 0}
                onClick={() =>
                    onPageChange(
                        Math.max(
                            0,
                            page - 1,
                        ),
                    )
                }
            >
                Назад
            </button>

            <span>
                Страница
                {' '}
                {page + 1}
                {' '}
                из
                {' '}
                {totalPages}
                .
                {' '}
                Всего строк:
                {' '}
                {totalElements}
            </span>

            <button
                type="button"
                className="secondary-button"
                disabled={
                    page + 1
                        >= totalPages
                }
                onClick={() =>
                    onPageChange(
                        page + 1,
                    )
                }
            >
                Вперёд
            </button>
        </nav>
    )
}

const summaryColumns:
    UsageTableColumn<UsageSummary>[] = [
    {
        key: 'userEmail',
        title: 'Пользователь',
        render: (row) =>
            row.userEmail,
    },
    {
        key: 'model',
        title: 'Модель',
        render: (row) =>
            row.model,
    },
    ...amountColumns<
        UsageSummary
    >(),
]

const userColumns:
    UsageTableColumn<UsageUserSummary>[] = [
    {
        key: 'userEmail',
        title: 'Пользователь',
        render: (row) =>
            row.userEmail,
    },
    ...amountColumns<
        UsageUserSummary
    >(),
]

const modelColumns:
    UsageTableColumn<UsageModelSummary>[] = [
    {
        key: 'model',
        title: 'Модель',
        render: (row) =>
            row.model,
    },
    ...amountColumns<
        UsageModelSummary
    >(),
]

const dailyColumns:
    UsageTableColumn<UsageDailySummary>[] = [
    {
        key: 'usageDate',
        title: 'Дата UTC',
        render: (row) =>
            formatDate(
                row.usageDate,
            ),
    },
    ...amountColumns<
        UsageDailySummary
    >(),
]

function amountColumns<
    T extends {
        inputTokens: string
        outputTokens: string
        totalTokens: string
        costUsd: string | null
        coverage: UsageCoverage
    },
>(): UsageTableColumn<T>[] {
    return [
        {
            key: 'inputTokens',
            title: 'Входные токены',
            render: (row) =>
                formatIntegerValue(
                    row.inputTokens,
                ),
        },
        {
            key: 'outputTokens',
            title: 'Выходные токены',
            render: (row) =>
                formatIntegerValue(
                    row.outputTokens,
                ),
        },
        {
            key: 'totalTokens',
            title: 'Всего токенов',
            render: (row) =>
                formatIntegerValue(
                    row.totalTokens,
                ),
        },
        {
            key: 'costUsd',
            title: 'Известная стоимость USD',
            render: (row) =>
                formatUsageCost(row),
        },
        {
            key: 'usageCoverage',
            title: 'Полнота usage',
            render: (row) =>
                formatUsageCoverage(
                    row.coverage,
                ),
        },
        {
            key: 'pricingCoverage',
            title: 'Полнота pricing',
            render: (row) =>
                formatPricingCoverage(
                    row.coverage,
                ),
        },
        {
            key: 'ambiguous',
            title: 'Неоднозначные операции',
            render: (row) =>
                formatIntegerValue(
                    row.coverage
                        .ambiguousProviderOperations,
                ),
        },
    ]
}

function formatUsageCost(
    row: {
        costUsd: string | null
        coverage: UsageCoverage
    },
): string {
    const formatted =
        formatUsd(row.costUsd)

    if (
        row.coverage.pricingComplete
            === true
    ) {
        return formatted
    }

    if (
        row.coverage
            .pricingFailedMessages
        && row.coverage
            .pricingFailedMessages
            !== '0'
    ) {
        return (
            `${formatted} — имеются `
            + 'ошибки расчёта'
        )
    }

    if (
        row.coverage.pricingComplete
            === false
    ) {
        return (
            `${formatted} — неполные `
            + 'pricing-данные'
        )
    }

    return (
        `${formatted} — coverage `
        + 'неизвестен'
    )
}

function formatUsageCoverage(
    coverage: UsageCoverage,
): string {
    if (
        coverage.usageComplete
            === true
    ) {
        return 'Полные'
    }

    if (
        coverage.usageComplete
            === false
    ) {
        return [
            `missing: ${
                coverage.missingUsageMessages
                ?? '—'
            }`,
            `partial: ${
                coverage.partialUsageMessages
                ?? '—'
            }`,
        ].join(', ')
    }

    return 'Coverage неизвестен'
}

function formatPricingCoverage(
    coverage: UsageCoverage,
): string {
    if (
        coverage.pricingComplete
            === true
    ) {
        return 'Полные'
    }

    if (
        coverage.pricingComplete
            === false
    ) {
        return [
            `unpriced: ${
                coverage.unpricedMessages
                ?? '—'
            }`,
            `failed: ${
                coverage.pricingFailedMessages
                ?? '—'
            }`,
        ].join(', ')
    }

    return 'Coverage неизвестен'
}

function getUsageRowKey(
    row: UsageRow,
    index: number,
): string {
    if ('usageDate' in row) {
        return row.usageDate
    }

    if (
        'userId' in row
        && 'model' in row
    ) {
        return `${row.userId}:${row.model}`
    }

    if ('userId' in row) {
        return row.userId
    }

    if ('model' in row) {
        return row.model
    }

    return String(index)
}

function getScopeLabel(
    superAdmin: boolean,
    currentOrganizationId:
        string | null,
    selectedOrganizationId: string,
    selectedOrganizationName:
        string | null,
): string {
    if (!superAdmin) {
        return (
            'ORGANIZATION — '
            + (
                currentOrganizationId
                ?? 'текущая организация'
            )
        )
    }

    if (!selectedOrganizationId) {
        return 'GLOBAL — вся платформа'
    }

    return (
        'ORGANIZATION — '
        + (
            selectedOrganizationName
            ?? selectedOrganizationId
        )
    )
}

function includeSelectedOrganization(
    organizations:
        OrganizationDirectoryItem[],
    selectedId: string,
): OrganizationDirectoryItem[] {
    if (
        !selectedId
        || organizations.some(
            (organization) =>
                organization.id
                === selectedId,
        )
    ) {
        return organizations
    }

    return [
        {
            id: selectedId,
            name:
                'Выбранная организация',
            enabled: true,
            type: 'UNKNOWN',
            protected: null,
        },
        ...organizations,
    ]
}

function getUsageLoadError(
    error: unknown,
    organizationId: string,
    tab: UsageTab,
): string {
    if (
        organizationId
        && tab !== 'summary'
        && error instanceof ApiError
        && error.status === 404
    ) {
        return (
            'Backend не реализовал обязательный '
            + 'organization-scoped aggregate endpoint '
            + `для отчёта «${tab}». `
            + 'Global данные намеренно не показаны.'
        )
    }

    const base =
        getApiErrorMessage(
            error,
            'Не удалось загрузить статистику использования.',
        )

    if (
        error instanceof ApiError
        && error.retryAfterSeconds
    ) {
        return (
            `${base} Повторите через `
            + `${error.retryAfterSeconds} сек.`
        )
    }

    return base
}

function isRequestAborted(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && error.errorCode
            === 'REQUEST_ABORTED'
}

export default AdminUsagePage
