import {
    useEffect,
    useMemo,
    useState,
} from 'react'
import {
    useSearchParams,
} from 'react-router-dom'
import {
    getOrganizationUsageDaily,
    getOrganizationUsageModels,
    getOrganizationUsageUsers,
    getUsageByModels,
    getUsageByOrganization,
    getUsageByUsers,
    getUsageDaily,
    getUsageSummary,
} from '../api/usageApi'
import type {
    UsageAmounts,
    UsageCoverage,
    UsageDailySummary,
    UsageModelSummary,
    UsageSummary,
    UsageUserSummary,
} from '../api/usageApi'
import {
    getApiErrorMessage,
} from '../api/http'
import {
    normalizePageResponse,
} from '../utils/page'
import {
    toUtcExclusiveEndOfDayIso,
    toUtcStartOfDayIso,
} from '../utils/date'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import PageErrorBoundary
    from '../components/PageErrorBoundary'
import {
    useAuth,
} from '../auth/AuthContext'
import './AdminUsagePage.css'

const PAGE_SIZE = 50
const UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

type Tab =
    | 'summary'
    | 'users'
    | 'models'
    | 'daily'

type PagedRows =
    | UsageSummary[]
    | UsageUserSummary[]

function AdminUsagePage() {
    return (
        <PageErrorBoundary>
            <AdminUsagePageContent />
        </PageErrorBoundary>
    )
}

function AdminUsagePageContent() {
    const { currentUser } = useAuth()
    const [searchParams, setSearchParams] =
        useSearchParams()

    const isSuperAdmin = Boolean(
        currentUser?.roles.includes('SUPER_ADMIN'),
    )

    const initialRange = useMemo(
        () => defaultUtcRange(),
        [],
    )

    const initialTab = parseTab(
        searchParams.get('report'),
    )

    const [tab, setTab] =
        useState<Tab>(initialTab)
    const [page, setPage] = useState(
        parsePage(searchParams.get('page')),
    )
    const [hasNext, setHasNext] =
        useState(false)
    const [hasPrevious, setHasPrevious] =
        useState(false)

    const [rows, setRows] = useState<
        PagedRows
        | UsageModelSummary[]
        | UsageDailySummary[]
    >([])

    const [loading, setLoading] =
        useState(true)
    const [error, setError] =
        useState('')
    const [filterError, setFilterError] =
        useState('')

    const [draftDateFrom, setDraftDateFrom] =
        useState(
            searchParams.get('dateFrom')
            ?? initialRange.dateFrom,
        )
    const [draftDateTo, setDraftDateTo] =
        useState(
            searchParams.get('dateTo')
            ?? initialRange.dateTo,
        )
    const [draftModel, setDraftModel] =
        useState(searchParams.get('model') ?? '')
    const [draftOrganizationId, setDraftOrganizationId] =
        useState(
            isSuperAdmin
                ? searchParams.get('organizationId') ?? ''
                : '',
        )

    const [appliedDateFrom, setAppliedDateFrom] =
        useState(draftDateFrom)
    const [appliedDateTo, setAppliedDateTo] =
        useState(draftDateTo)
    const [appliedModel, setAppliedModel] =
        useState(draftModel.trim())
    const [appliedOrganizationId, setAppliedOrganizationId] =
        useState(draftOrganizationId.trim())
    const [reloadToken, setReloadToken] =
        useState(0)

    const effectiveOrganizationId =
        isSuperAdmin
            ? appliedOrganizationId || null
            : currentUser?.organizationId ?? null

    useEffect(() => {
        const controller = new AbortController()

        async function load() {
            setLoading(true)
            setError('')

            try {
                // В интерфейсе обе календарные даты включительны.
                // Backend по-прежнему получает exclusive dateTo.
                const dateFilter = {
                    dateFrom: toUtcStartOfDayIso(appliedDateFrom),
                    dateTo: toUtcExclusiveEndOfDayIso(appliedDateTo),
                }

                if (tab === 'summary') {
                    const filter = {
                        ...dateFilter,
                        model:
                            appliedModel || undefined,
                    }

                    const response =
                        effectiveOrganizationId
                        && isSuperAdmin
                            ? await getUsageByOrganization(
                                effectiveOrganizationId,
                                page,
                                PAGE_SIZE,
                                filter,
                                {
                                    signal:
                                        controller.signal,
                                },
                            )
                            : await getUsageSummary(
                                page,
                                PAGE_SIZE,
                                filter,
                                {
                                    signal:
                                        controller.signal,
                                },
                            )

                    const normalized =
                        normalizePageResponse(response)

                    setRows(normalized.content)
                    setHasNext(
                        page + 1 < normalized.totalPages,
                    )
                    setHasPrevious(page > 0)
                    return
                }

                if (tab === 'users') {
                    const response =
                        effectiveOrganizationId
                        && isSuperAdmin
                            ? await getOrganizationUsageUsers(
                                effectiveOrganizationId,
                                page,
                                PAGE_SIZE,
                                dateFilter,
                                {
                                    signal:
                                        controller.signal,
                                },
                            )
                            : await getUsageByUsers(
                                page,
                                PAGE_SIZE,
                                dateFilter,
                                {
                                    signal:
                                        controller.signal,
                                },
                            )

                    const normalized =
                        normalizePageResponse(response)

                    setRows(normalized.content)
                    setHasNext(
                        page + 1 < normalized.totalPages,
                    )
                    setHasPrevious(page > 0)
                    return
                }

                if (tab === 'models') {
                    const data =
                        effectiveOrganizationId
                        && isSuperAdmin
                            ? await getOrganizationUsageModels(
                                effectiveOrganizationId,
                                dateFilter,
                                {
                                    signal:
                                        controller.signal,
                                },
                            )
                            : await getUsageByModels(
                                dateFilter,
                                {
                                    signal:
                                        controller.signal,
                                },
                            )

                    setRows(data)
                    setHasNext(false)
                    setHasPrevious(false)
                    return
                }

                const data =
                    effectiveOrganizationId
                    && isSuperAdmin
                        ? await getOrganizationUsageDaily(
                            effectiveOrganizationId,
                            dateFilter,
                            {
                                signal:
                                    controller.signal,
                            },
                        )
                        : await getUsageDaily(
                            dateFilter,
                            {
                                signal:
                                    controller.signal,
                            },
                        )

                setRows(data)
                setHasNext(false)
                setHasPrevious(false)
            } catch (loadError) {
                if (isAbortError(loadError)) {
                    return
                }

                setRows([])
                setHasNext(false)
                setHasPrevious(false)
                setError(
                    getApiErrorMessage(
                        loadError,
                        'Не удалось загрузить статистику использования.',
                    ),
                )
            } finally {
                if (!controller.signal.aborted) {
                    setLoading(false)
                }
            }
        }

        void load()

        return () => {
            controller.abort()
        }
    }, [
        tab,
        page,
        appliedDateFrom,
        appliedDateTo,
        appliedModel,
        effectiveOrganizationId,
        isSuperAdmin,
        reloadToken,
    ])

    function applyFilters() {
        const validationError = validateFilters(
            draftDateFrom,
            draftDateTo,
            isSuperAdmin
                ? draftOrganizationId
                : '',
        )

        if (validationError) {
            setFilterError(validationError)
            return
        }

        setFilterError('')
        setPage(0)
        setAppliedDateFrom(draftDateFrom)
        setAppliedDateTo(draftDateTo)
        setAppliedModel(draftModel.trim())
        setAppliedOrganizationId(
            isSuperAdmin
                ? draftOrganizationId.trim()
                : '',
        )

        updateUrl({
            tab,
            page: 0,
            dateFrom: draftDateFrom,
            dateTo: draftDateTo,
            model: draftModel.trim(),
            organizationId:
                isSuperAdmin
                    ? draftOrganizationId.trim()
                    : '',
        })
    }

    function resetFilters() {
        const range = defaultUtcRange()

        setFilterError('')
        setDraftDateFrom(range.dateFrom)
        setDraftDateTo(range.dateTo)
        setDraftModel('')
        setDraftOrganizationId('')
        setAppliedDateFrom(range.dateFrom)
        setAppliedDateTo(range.dateTo)
        setAppliedModel('')
        setAppliedOrganizationId('')
        setPage(0)

        updateUrl({
            tab,
            page: 0,
            dateFrom: range.dateFrom,
            dateTo: range.dateTo,
            model: '',
            organizationId: '',
        })
    }

    function selectTab(nextTab: Tab) {
        setTab(nextTab)
        setPage(0)

        updateUrl({
            tab: nextTab,
            page: 0,
            dateFrom: appliedDateFrom,
            dateTo: appliedDateTo,
            model: appliedModel,
            organizationId:
                isSuperAdmin
                    ? appliedOrganizationId
                    : '',
        })
    }

    function goToPage(nextPage: number) {
        const safePage = Math.max(0, nextPage)
        setPage(safePage)

        updateUrl({
            tab,
            page: safePage,
            dateFrom: appliedDateFrom,
            dateTo: appliedDateTo,
            model: appliedModel,
            organizationId:
                isSuperAdmin
                    ? appliedOrganizationId
                    : '',
        })
    }

    function updateUrl(values: {
        tab: Tab
        page: number
        dateFrom: string
        dateTo: string
        model: string
        organizationId: string
    }) {
        const next = new URLSearchParams()
        next.set('report', values.tab)
        next.set('dateFrom', values.dateFrom)
        next.set('dateTo', values.dateTo)

        if (values.page > 0) {
            next.set('page', String(values.page))
        }

        if (
            values.tab === 'summary'
            && values.model
        ) {
            next.set('model', values.model)
        }

        if (values.organizationId) {
            next.set(
                'organizationId',
                values.organizationId,
            )
        }

        setSearchParams(next, {
            replace: true,
        })
    }

    return (
        <div className="page usage-page">
            <header className="usage-page__header">
                <div>
                    <h1>Использование AI</h1>
                    <p className="muted">
                        Контролируйте расход токенов, стоимость и качество данных.
                    </p>
                </div>
                <div className="usage-scope" aria-label="Область отчёта">
                    <span className="usage-scope__label">Отчёт</span>
                    <strong>
                        {isSuperAdmin
                            ? effectiveOrganizationId
                                ? 'Выбранная организация'
                                : 'Все организации'
                            : 'Моя организация'}
                    </strong>
                </div>
            </header>

            <section
                className="card usage-filters"
                aria-labelledby="usage-filters-title"
            >
                <div className="usage-section-heading">
                    <div>
                        <h2 id="usage-filters-title">Период и фильтры</h2>
                        <p className="muted">Обе даты включены в отчёт.</p>
                    </div>
                </div>

                <div className="usage-filter-grid">
                    <label>
                        С
                        <input
                            type="date"
                            value={draftDateFrom}
                            onChange={(event) =>
                                setDraftDateFrom(
                                    event.target.value,
                                )
                            }
                        />
                    </label>

                    <label>
                        По
                        <input
                            type="date"
                            value={draftDateTo}
                            onChange={(event) =>
                                setDraftDateTo(
                                    event.target.value,
                                )
                            }
                        />
                    </label>

                    {tab === 'summary' && (
                        <label>
                            Модель
                            <input
                                value={draftModel}
                                onChange={(event) =>
                                    setDraftModel(event.target.value)
                                }
                                maxLength={100}
                                placeholder="Все модели"
                            />
                        </label>
                    )}

                    {isSuperAdmin && (
                        <label>
                            Организация
                            <input
                                value={draftOrganizationId}
                                onChange={(event) =>
                                    setDraftOrganizationId(
                                        event.target.value,
                                    )
                                }
                                maxLength={36}
                                placeholder="UUID или все организации"
                            />
                            <small className="muted">
                                Оставьте пустым для общего отчёта.
                            </small>
                        </label>
                    )}
                </div>

                {filterError && (
                    <div className="error" role="alert">
                        {filterError}
                    </div>
                )}

                <div className="usage-filter-actions">
                    <button
                        type="button"
                        disabled={loading}
                        onClick={applyFilters}
                    >
                        Показать
                    </button>
                    <button
                        type="button"
                        className="secondary-button"
                        disabled={loading}
                        onClick={resetFilters}
                    >
                        Последние 30 дней
                    </button>
                </div>
            </section>

            <section
                className="usage-report"
                aria-labelledby="usage-report-title"
            >
                <div
                    className="usage-tabs"
                    role="tablist"
                    aria-label="Вид отчёта"
                >
                    <TabButton
                        active={tab === 'summary'}
                        onClick={() => selectTab('summary')}
                    >
                        Сводка
                    </TabButton>
                    <TabButton
                        active={tab === 'users'}
                        onClick={() => selectTab('users')}
                    >
                        По пользователям
                    </TabButton>
                    <TabButton
                        active={tab === 'models'}
                        onClick={() => selectTab('models')}
                    >
                        По моделям
                    </TabButton>
                    <TabButton
                        active={tab === 'daily'}
                        onClick={() => selectTab('daily')}
                    >
                        По дням
                    </TabButton>
                </div>
                <div className="usage-report-heading">
                    <h2 id="usage-report-title">
                        {reportTitle(tab)}
                    </h2>
                    <p className="muted">
                        {reportDescription(tab)}
                    </p>
                </div>

            {loading && (
                <LoadingState
                    message="Загрузка статистики использования..."
                />
            )}

            {!loading && error && (
                <ErrorState
                    title="Ошибка загрузки"
                    message={error}
                    action={
                        <button
                            type="button"
                            onClick={() =>
                                setReloadToken(
                                    (value) => value + 1,
                                )
                            }
                        >
                            Повторить
                        </button>
                    }
                />
            )}

            {!loading && !error && (
                <div className="card table-card">
                    <UsageRows
                        tab={tab}
                        rows={rows}
                    />

                    {(tab === 'summary'
                        || tab === 'users') && rows.length > 0
                        && (
                            <div className="pagination">
                                <button
                                    type="button"
                                    className="secondary-button"
                                    disabled={!hasPrevious}
                                    onClick={() =>
                                        goToPage(page - 1)
                                    }
                                >
                                    Назад
                                </button>
                                <span>
                                    Страница {page + 1}
                                </span>
                                <button
                                    type="button"
                                    className="secondary-button"
                                    disabled={!hasNext}
                                    onClick={() =>
                                        goToPage(page + 1)
                                    }
                                >
                                    Далее
                                </button>
                            </div>
                        )}
                </div>
            )}
            </section>
        </div>
    )
}

function UsageRows({
    tab,
    rows,
}: {
    tab: Tab
    rows:
        PagedRows
        | UsageModelSummary[]
        | UsageDailySummary[]
}) {
    if (rows.length === 0) {
        return (
            <EmptyState
                title="За этот период использования нет"
                message="Данные появятся после завершённых обращений к AI. Выберите другой период или покажите последние 30 дней."
            />
        )
    }

    if (tab === 'summary') {
        return (
            <table className="admin-table usage-table">
                <thead>
                    <tr>
                        <th>Пользователь</th>
                        <th>Модель</th>
                        <th>Вход</th>
                        <th>Выход</th>
                        <th>Всего</th>
                        <th>Известная стоимость</th>
                        <th>Качество данных</th>
                    </tr>
                </thead>
                <tbody>
                    {(rows as UsageSummary[]).map(
                        (row) => (
                            <UsageSummaryRow
                                key={`${row.userId}:${row.model}`}
                                row={row}
                                showUser
                                showModel
                            />
                        ),
                    )}
                </tbody>
            </table>
        )
    }

    if (tab === 'users') {
        return (
            <table className="admin-table usage-table">
                <thead>
                    <tr>
                        <th>Пользователь</th>
                        <th>Вход</th>
                        <th>Выход</th>
                        <th>Всего</th>
                        <th>Известная стоимость</th>
                        <th>Качество данных</th>
                    </tr>
                </thead>
                <tbody>
                    {(rows as UsageUserSummary[]).map(
                        (row) => (
                            <UsageSummaryRow
                                key={row.userId}
                                row={row}
                                showUser
                            />
                        ),
                    )}
                </tbody>
            </table>
        )
    }

    if (tab === 'models') {
        return (
            <table className="admin-table usage-table">
                <thead>
                    <tr>
                        <th>Модель</th>
                        <th>Вход</th>
                        <th>Выход</th>
                        <th>Всего</th>
                        <th>Известная стоимость</th>
                        <th>Качество данных</th>
                    </tr>
                </thead>
                <tbody>
                    {(rows as UsageModelSummary[]).map(
                        (row) => (
                            <UsageSummaryRow
                                key={row.model}
                                row={row}
                                showModel
                            />
                        ),
                    )}
                </tbody>
            </table>
        )
    }

    return (
        <table className="admin-table usage-table">
            <thead>
                <tr>
                    <th>Дата</th>
                    <th>Вход</th>
                    <th>Выход</th>
                    <th>Всего</th>
                    <th>Известная стоимость</th>
                    <th>Качество данных</th>
                </tr>
            </thead>
            <tbody>
                {(rows as UsageDailySummary[]).map(
                    (row) => (
                        <tr key={row.usageDate}>
                            <td>
                                {formatIsoDate(row.usageDate)}
                            </td>
                            <UsageAmountCells row={row} />
                        </tr>
                    ),
                )}
            </tbody>
        </table>
    )
}

function UsageSummaryRow({
    row,
    showUser = false,
    showModel = false,
}: {
    row: UsageAmounts & {
        userEmail?: string
        model?: string
    }
    showUser?: boolean
    showModel?: boolean
}) {
    return (
        <tr>
            {showUser && (
                <td>{row.userEmail ?? '—'}</td>
            )}
            {showModel && (
                <td>{row.model ?? '—'}</td>
            )}
            <UsageAmountCells row={row} />
        </tr>
    )
}

function UsageAmountCells({
    row,
}: {
    row: UsageAmounts
}) {
    return (
        <>
            <td>{formatCount(row.inputTokens)}</td>
            <td>{formatCount(row.outputTokens)}</td>
            <td>
                {formatCount(row.totalTokens)}
                {row.partialTotalTokens !== '0' && (
                    <div className="muted">
                        + известно из partial:
                        {' '}
                        {formatCount(row.partialTotalTokens)}
                    </div>
                )}
            </td>
            <td>
                {row.costUsd === null
                    ? '—'
                    : `${trimDecimal(row.costUsd)} ${row.currency}`}
                {row.coverage.pricingComplete === false && (
                    <div className="muted">
                        Это только известная часть стоимости.
                    </div>
                )}
            </td>
            <td>
                <CoverageView
                    coverage={row.coverage}
                />
            </td>
        </>
    )
}

function CoverageView({
    coverage,
}: {
    coverage: UsageCoverage
}) {
    const usageText = coverage.usageComplete === true
        ? 'Токены учтены полностью'
        : coverage.usageComplete === false
            ? 'Токены учтены частично'
            : 'Полнота токенов неизвестна'

    const pricingText = coverage.pricingComplete === true
        ? 'Стоимость рассчитана'
        : coverage.pricingComplete === false
            ? 'Стоимость рассчитана частично'
            : 'Полнота стоимости неизвестна'

    const details = [
        countPart(
            'частичных ответов',
            coverage.partialUsageMessages,
        ),
        countPart(
            'без данных',
            coverage.missingUsageMessages,
        ),
        countPart(
            'без цены',
            coverage.unpricedMessages,
        ),
        countPart(
            'ошибок расчёта',
            coverage.pricingFailedMessages,
        ),
    ].filter(Boolean)

    return (
        <div>
            <div>{usageText}</div>
            <div>{pricingText}</div>
            {details.length > 0 && (
                <small className="muted">
                    {details.join(', ')}
                </small>
            )}
        </div>
    )
}

function TabButton({
    active,
    onClick,
    children,
}: {
    active: boolean
    onClick: () => void
    children: string
}) {
    return (
        <button
            type="button"
            role="tab"
            aria-selected={active}
            className={
                active
                    ? 'filter-button active'
                    : 'filter-button'
            }
            onClick={onClick}
        >
            {children}
        </button>
    )
}

function validateFilters(
    dateFrom: string,
    dateTo: string,
    organizationId: string,
): string | null {
    if (!dateFrom || !dateTo) {
        return 'Обе даты обязательны.'
    }

    try {
        toUtcStartOfDayIso(dateFrom)
        toUtcStartOfDayIso(dateTo)
    } catch {
        return 'Некорректная календарная дата.'
    }

    if (dateFrom > dateTo) {
        return 'Дата начала не может быть позже даты окончания.'
    }

    const rangeDays = Math.round(
        (
            Date.parse(`${dateTo}T00:00:00Z`)
            - Date.parse(`${dateFrom}T00:00:00Z`)
        ) / 86_400_000,
    )

    if (rangeDays + 1 > 366) {
        return 'Период не должен превышать 366 дней.'
    }

    const normalizedOrganizationId =
        organizationId.trim()

    if (
        normalizedOrganizationId
        && !UUID_PATTERN.test(
            normalizedOrganizationId,
        )
    ) {
        return 'Некорректный UUID организации.'
    }

    return null
}

function defaultUtcRange(): {
    dateFrom: string
    dateTo: string
} {
    const dateTo = new Date()
    dateTo.setUTCHours(0, 0, 0, 0)

    const dateFrom = new Date(dateTo)
    dateFrom.setUTCDate(dateFrom.getUTCDate() - 29)

    return {
        dateFrom: dateFrom
            .toISOString()
            .slice(0, 10),
        dateTo: dateTo
            .toISOString()
            .slice(0, 10),
    }
}

function parseTab(value: string | null): Tab {
    switch (value) {
        case 'users':
        case 'models':
        case 'daily':
            return value
        default:
            return 'summary'
    }
}

function reportTitle(tab: Tab): string {
    switch (tab) {
        case 'users':
            return 'Расход по сотрудникам'
        case 'models':
            return 'Расход по моделям'
        case 'daily':
            return 'Динамика использования'
        default:
            return 'Детальная сводка'
    }
}

function reportDescription(tab: Tab): string {
    switch (tab) {
        case 'users':
            return 'Суммарные токены и стоимость для каждого сотрудника.'
        case 'models':
            return 'Сравнение объёма и стоимости используемых AI-моделей.'
        case 'daily':
            return 'Изменение расхода токенов и стоимости по календарным дням.'
        default:
            return 'Использование с разбивкой по сотрудникам и моделям.'
    }
}

function parsePage(value: string | null): number {
    if (!value) {
        return 0
    }

    const parsed = Number(value)
    return Number.isSafeInteger(parsed)
        && parsed >= 0
        ? parsed
        : 0
}

function formatCount(value: string): string {
    try {
        return new Intl.NumberFormat(
            'ru-RU',
        ).format(BigInt(value))
    } catch {
        return value
    }
}

function trimDecimal(value: string): string {
    if (!value.includes('.')) {
        return value
    }

    const normalized = value
        .replace(/0+$/, '')
        .replace(/\.$/, '')

    return normalized || '0'
}

function formatIsoDate(value: string): string {
    const [year, month, day] = value.split('-')
    return `${day}.${month}.${year}`
}

function countPart(
    label: string,
    value: string | null,
): string {
    return value && value !== '0'
        ? `${label}: ${formatCount(value)}`
        : ''
}

function isAbortError(error: unknown): boolean {
    return error instanceof Error
        && (
            error.name === 'AbortError'
            || (
                'errorCode' in error
                && (
                    error as {
                        errorCode?: string
                    }
                ).errorCode === 'REQUEST_ABORTED'
            )
        )
}

export default AdminUsagePage
