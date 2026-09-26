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
    useAuth,
} from '../auth/useAuth'
import {
    defaultUtcRange,
    isAbortError,
    parsePage,
    parseTab,
    validateFilters,
} from './adminUsagePageSupport'
import type {
    UsageReportTab,
} from './adminUsagePageSupport'

const PAGE_SIZE = 50

type UsageReportRows =
    | UsageSummary[]
    | UsageUserSummary[]
    | UsageModelSummary[]
    | UsageDailySummary[]

type UsageQueryState = {
    key: string
    loading: boolean
    error: string
    rows: UsageReportRows
    hasNext: boolean
    hasPrevious: boolean
}

function createQueryKey(
    tab: UsageReportTab,
    page: number,
    dateFrom: string,
    dateTo: string,
    model: string,
    organizationId: string | null,
): string {
    return JSON.stringify([
        tab,
        page,
        dateFrom,
        dateTo,
        model,
        organizationId ?? '',
    ])
}

export function useAdminUsageReport() {
    const {
        currentUser,
    } = useAuth()

    const [
        searchParams,
        setSearchParams,
    ] = useSearchParams()

    const isSuperAdmin = Boolean(
        currentUser?.roles.includes(
            'SUPER_ADMIN',
        ),
    )

    const initialRange = useMemo(
        () => defaultUtcRange(),
        [],
    )

    const [tab, setTab] = useState<UsageReportTab>(
        () => parseTab(
            searchParams.get('report'),
        ),
    )

    const [page, setPage] = useState(
        () => parsePage(
            searchParams.get('page'),
        ),
    )

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
        useState(
            searchParams.get('model')
            ?? '',
        )
    const [
        draftOrganizationId,
        setDraftOrganizationId,
    ] = useState(
        isSuperAdmin
            ? (
                searchParams.get(
                    'organizationId',
                )
                ?? ''
            )
            : '',
    )

    const [appliedDateFrom, setAppliedDateFrom] =
        useState(draftDateFrom)
    const [appliedDateTo, setAppliedDateTo] =
        useState(draftDateTo)
    const [appliedModel, setAppliedModel] =
        useState(draftModel.trim())
    const [
        appliedOrganizationId,
        setAppliedOrganizationId,
    ] = useState(
        draftOrganizationId.trim(),
    )
    const [reloadToken, setReloadToken] =
        useState(0)

    const effectiveOrganizationId =
        isSuperAdmin
            ? appliedOrganizationId || null
            : currentUser?.organizationId ?? null

    const queryKey = useMemo(
        () => createQueryKey(
            tab,
            page,
            appliedDateFrom,
            appliedDateTo,
            appliedModel,
            effectiveOrganizationId,
        ),
        [
            tab,
            page,
            appliedDateFrom,
            appliedDateTo,
            appliedModel,
            effectiveOrganizationId,
        ],
    )

    const [queryState, setQueryState] =
        useState<UsageQueryState>(() => ({
            key: queryKey,
            loading: true,
            error: '',
            rows: [],
            hasNext: false,
            hasPrevious: false,
        }))

    const queryStateIsCurrent =
        queryState.key === queryKey

    const rows = queryStateIsCurrent
        ? queryState.rows
        : []
    const loading = !queryStateIsCurrent
        || queryState.loading
    const error = queryStateIsCurrent
        ? queryState.error
        : ''
    const hasNext = queryStateIsCurrent
        ? queryState.hasNext
        : false
    const hasPrevious = queryStateIsCurrent
        ? queryState.hasPrevious
        : false

    useEffect(() => {
        const controller =
            new AbortController()

        async function load() {
            setQueryState({
                key: queryKey,
                loading: true,
                error: '',
                rows: [],
                hasNext: false,
                hasPrevious: false,
            })

            try {
                const dateFilter = {
                    dateFrom:
                        toUtcStartOfDayIso(
                            appliedDateFrom,
                        ),
                    dateTo:
                        toUtcExclusiveEndOfDayIso(
                            appliedDateTo,
                        ),
                }

                if (tab === 'summary') {
                    const filter = {
                        ...dateFilter,
                        model:
                            appliedModel
                            || undefined,
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
                        normalizePageResponse(
                            response,
                        )

                    if (!controller.signal.aborted) {
                        setQueryState({
                            key: queryKey,
                            loading: false,
                            error: '',
                            rows: normalized.content,
                            hasNext:
                                page + 1
                                < normalized.totalPages,
                            hasPrevious: page > 0,
                        })
                    }
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
                        normalizePageResponse(
                            response,
                        )

                    if (!controller.signal.aborted) {
                        setQueryState({
                            key: queryKey,
                            loading: false,
                            error: '',
                            rows: normalized.content,
                            hasNext:
                                page + 1
                                < normalized.totalPages,
                            hasPrevious: page > 0,
                        })
                    }
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

                    if (!controller.signal.aborted) {
                        setQueryState({
                            key: queryKey,
                            loading: false,
                            error: '',
                            rows: data,
                            hasNext: false,
                            hasPrevious: false,
                        })
                    }
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

                if (!controller.signal.aborted) {
                    setQueryState({
                        key: queryKey,
                        loading: false,
                        error: '',
                        rows: data,
                        hasNext: false,
                        hasPrevious: false,
                    })
                }
            } catch (loadError) {
                if (
                    controller.signal.aborted
                    || isAbortError(loadError)
                ) {
                    return
                }

                setQueryState({
                    key: queryKey,
                    loading: false,
                    error: getApiErrorMessage(
                        loadError,
                        'Не удалось загрузить статистику использования.',
                    ),
                    rows: [],
                    hasNext: false,
                    hasPrevious: false,
                })
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
        queryKey,
    ])

    function updateUrl(
        values: {
            tab: UsageReportTab
            page: number
            dateFrom: string
            dateTo: string
            model: string
            organizationId: string
        },
    ) {
        const next = new URLSearchParams()

        next.set('report', values.tab)
        next.set('dateFrom', values.dateFrom)
        next.set('dateTo', values.dateTo)

        if (values.page > 0) {
            next.set(
                'page',
                String(values.page),
            )
        }

        // Preserve the summary-only model filter while the user inspects
        // another report tab. This keeps URL state reproducible after refresh
        // and when a link to the current report is copied.
        if (values.model) {
            next.set('model', values.model)
        }

        if (values.organizationId) {
            next.set(
                'organizationId',
                values.organizationId,
            )
        }

        setSearchParams(
            next,
            {
                replace: true,
            },
        )
    }

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

        const nextModel = draftModel.trim()
        const nextOrganizationId =
            isSuperAdmin
                ? draftOrganizationId.trim()
                : ''

        setFilterError('')
        setPage(0)
        setAppliedDateFrom(draftDateFrom)
        setAppliedDateTo(draftDateTo)
        setAppliedModel(nextModel)
        setAppliedOrganizationId(
            nextOrganizationId,
        )

        updateUrl({
            tab,
            page: 0,
            dateFrom: draftDateFrom,
            dateTo: draftDateTo,
            model: nextModel,
            organizationId:
                nextOrganizationId,
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

    function selectTab(
        nextTab: UsageReportTab,
    ) {
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
        const safePage = Math.max(
            0,
            nextPage,
        )

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

    return {
        tab,
        page,
        hasNext,
        hasPrevious,
        rows,
        loading,
        error,
        filterError,
        draftDateFrom,
        draftDateTo,
        draftModel,
        draftOrganizationId,
        effectiveOrganizationId,
        isSuperAdmin,
        setDraftDateFrom,
        setDraftDateTo,
        setDraftModel,
        setDraftOrganizationId,
        applyFilters,
        resetFilters,
        selectTab,
        goToPage,
        reload: () => {
            setReloadToken(
                (value) => value + 1,
            )
        },
    }
}
