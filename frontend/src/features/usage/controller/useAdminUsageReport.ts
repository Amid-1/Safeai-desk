// frontend/src/features/usage/controller/useAdminUsageReport.ts

import {
    useEffect,
    useMemo,
    useRef,
    useState,
} from 'react'
import {
    useSearchParams,
    useLocation,
    useNavigationType,
} from 'react-router-dom'
import {
    useAuth,
} from '../../auth/state/useAuth'
import type {
    UsageFilterOption,
} from '../ui/UsageFilterCombobox'
import {
    buildUsageSearchParams,
    createDefaultUsageDraftFilter,
    readUsageUrlState,
    toUsageFilter,
} from '../lib/adminUsage.helpers'
import type {
    UsageReportTab,
} from '../lib/adminUsage.helpers'
import {
    useAdminUsageModelOptions,
} from './useAdminUsageModelOptions'
import {
    useAdminUsageOrganizationOptions,
} from './useAdminUsageOrganizationOptions'
import {
    useAdminUsageReportQuery,
} from './useAdminUsageReportQuery'
import { useUsageExport } from './useUsageExport'
import type { UsageAmounts, UsageSummaryFilter } from '../api/usageApi'
import type { UsageCsvContext } from '../ui/usageCsv'
import { useUsageBudget } from './useUsageBudget'

export function useAdminUsageReport() {
    const {
        currentUser,
    } = useAuth()

    const [
        searchParams,
        setSearchParams,
    ] = useSearchParams()
    const location = useLocation()
    const navigationType = useNavigationType()

    const isSuperAdmin = Boolean(
        currentUser?.roles.includes(
            'SUPER_ADMIN',
        ),
    )

    // The applied report is the URL, not a second independently mutable state.
    const urlState = useMemo(() =>
        readUsageUrlState(
            location.search,
            isSuperAdmin,
        ),
        [location.search, isSuperAdmin],
    )
    const {tab, page, budgetStatus} = urlState
    const order = urlState.order ?? 'tokens'
    const {dateFrom: appliedDateFrom, dateTo: appliedDateTo, model: appliedModel,
        organizationId: appliedOrganizationId, userId: appliedUserId = ''} = urlState.draft
    const [filterError, setFilterError] =
        useState('')

    const [draftDateFrom, setDraftDateFrom] =
        useState(urlState.draft.dateFrom)
    const [draftDateTo, setDraftDateTo] =
        useState(urlState.draft.dateTo)
    const [draftModel, setDraftModel] =
        useState(urlState.draft.model)
    const [draftOrganizationId, setDraftOrganizationId] =
        useState(urlState.draft.organizationId)
    const [draftUserId, setDraftUserId] = useState(urlState.draft.userId ?? '')
    const [userLabel, setUserLabel] = useState<{ id: string; email: string } | null>(null)

    const [reloadToken, setReloadToken] =
        useState(0)
    // Preserve unapplied edits when merely switching tabs or sorting. Restore
    // the editor on Back/Forward, a different applied scope, or authority changes.
    const draftKey = JSON.stringify([isSuperAdmin, urlState.draft])
    const previousDraftLocation = useRef({key: location.key, draftKey})
    useEffect(() => {
        const previous = previousDraftLocation.current
        previousDraftLocation.current = {key: location.key, draftKey}
        if (previous.draftKey === draftKey && (navigationType !== 'POP' || previous.key === location.key)) return
        setDraftDateFrom(urlState.draft.dateFrom)
        setDraftDateTo(urlState.draft.dateTo)
        setDraftModel(urlState.draft.model)
        setDraftOrganizationId(urlState.draft.organizationId)
        setDraftUserId(urlState.draft.userId ?? '')
        setFilterError('')
    }, [draftKey, location.key, navigationType, urlState])

    const organizations =
        useAdminUsageOrganizationOptions({
            enabled: isSuperAdmin,
            draftOrganizationId,
            appliedOrganizationId,
        })

    const models = useAdminUsageModelOptions({
        enabled: currentUser !== null,
        isSuperAdmin,
        dateFrom: draftDateFrom,
        dateTo: draftDateTo,
        organizationId:
            draftOrganizationId,
    })

    const query = useAdminUsageReportQuery({
        enabled: currentUser !== null && (tab !== 'organizations' || (isSuperAdmin && !appliedOrganizationId)),
        userId: currentUser?.id ?? '',
        userOrganizationId:
            currentUser?.organizationId ?? '',
        tab,
        page,
        dateFrom: appliedDateFrom,
        dateTo: appliedDateTo,
        model: appliedModel,
        filterUserId: appliedUserId,
        organizationId:
            appliedOrganizationId,
        isSuperAdmin,
        reloadToken,
        order: tab === 'daily' || tab === 'models' ? undefined : order,
        budgetStatus,
    })
    const totals = useAdminUsageReportQuery({
        enabled: currentUser !== null && tab !== 'daily' && !budgetStatus,
        userId: currentUser?.id ?? '',
        userOrganizationId: currentUser?.organizationId ?? '',
        tab: 'daily', page: 0, dateFrom: appliedDateFrom, dateTo: appliedDateTo,
        model: appliedModel, filterUserId: appliedUserId,
        organizationId: appliedOrganizationId, isSuperAdmin, reloadToken,
    })
    const budget = useUsageBudget(currentUser?.id ?? '',
        isSuperAdmin ? appliedOrganizationId : currentUser?.organizationId ?? '', reloadToken)
    const exportTask = useUsageExport(JSON.stringify([
        currentUser?.id, currentUser?.organizationId, isSuperAdmin, tab,
        appliedDateFrom, appliedDateTo, appliedModel, appliedUserId, appliedOrganizationId, reloadToken, order, budgetStatus,
    ]))
    const csvContext: UsageCsvContext = {
        dateFrom: appliedDateFrom, dateTo: appliedDateTo,
        organizationId: isSuperAdmin ? appliedOrganizationId || null : currentUser?.organizationId ?? '',
        model: appliedModel, userId: appliedUserId, budgetStatus: budgetStatus ?? '',
        order: tab === 'daily' ? 'date-desc' : tab === 'models' ? 'model-asc' : order,
        page: tab === 'summary' || tab === 'users' || tab === 'organizations' ? page : null,
        scope: 'displayed',
    }

    function exportFiltered() {
        if (!currentUser || query.loading || query.error || filtersDirty) return
        return exportTask.run({kind: tab, dateFrom: appliedDateFrom, dateTo: appliedDateTo,
            organizationId: csvContext.organizationId || null, userId: appliedUserId,
            model: appliedModel, order, budgetStatus})
    }

    function changeDraftDateFrom(
        value: string,
    ) {
        setDraftDateFrom(value)
        setFilterError('')
    }

    function changeDraftDateTo(
        value: string,
    ) {
        setDraftDateTo(value)
        setFilterError('')
    }

    function changeDraftModel(
        value: string,
    ) {
        setDraftModel(value)
        setFilterError('')
    }

    function changeDraftOrganization(
        value: string,
        _option: UsageFilterOption | null,
    ) {
        setDraftOrganizationId(value)
        setDraftUserId('')
        organizations.setQuery('')
        setDraftModel('')
        setFilterError('')
    }

    const filtersDirty = draftDateFrom !== appliedDateFrom
        || draftDateTo !== appliedDateTo
        || draftModel !== appliedModel
        || draftUserId !== appliedUserId
        || (isSuperAdmin && draftOrganizationId !== appliedOrganizationId)

    function applyFilters(
        draft = {
            dateFrom: draftDateFrom,
            dateTo: draftDateTo,
            model: draftModel,
            organizationId: draftOrganizationId,
            userId: draftUserId,
        },
        nextTab = tab,
    ) {
        try {
            const normalized = toUsageFilter(
                draft,
                isSuperAdmin,
            )
            if (nextTab === 'organizations' && (!isSuperAdmin || normalized.organizationId)) nextTab = 'summary'

            setFilterError('')
            setDraftDateFrom(
                normalized.draft.dateFrom,
            )
            setDraftDateTo(
                normalized.draft.dateTo,
            )
            setDraftModel(
                normalized.draft.model,
            )
            setDraftOrganizationId(
                normalized.organizationId,
            )
            setDraftUserId(normalized.draft.userId ?? '')

            updateUrl({
                tab: nextTab,
                page: 0,
                dateFrom:
                    normalized.draft.dateFrom,
                dateTo:
                    normalized.draft.dateTo,
                model:
                    normalized.draft.model,
                organizationId:
                    normalized.organizationId,
                userId: normalized.draft.userId,
            })
        } catch (validationError) {
            setFilterError(
                validationError instanceof Error
                    ? validationError.message
                    : 'Некорректные параметры отчёта.',
            )
        }
    }

    const applied = {
        dateFrom: appliedDateFrom, dateTo: appliedDateTo, model: appliedModel,
        organizationId: appliedOrganizationId, userId: appliedUserId,
    }

    function inspectModel(model: string) {
        if (filtersDirty || query.loading) return
        if (tab === 'summary' && model === appliedModel) return
        applyFilters({...applied, model}, 'summary')
    }

    function inspectUser(userId: string, email: string) {
        if (filtersDirty || query.loading) return
        if (tab === 'summary' && userId === appliedUserId) return
        setUserLabel({ id: userId, email })
        applyFilters({...applied, userId}, 'summary')
    }

    function inspectDay(date: string) {
        if (filtersDirty || query.loading) return
        applyFilters({...applied, dateFrom: date, dateTo: date}, 'summary')
    }

    function inspectRange(dateFrom: string, dateTo: string) {
        if (filtersDirty || query.loading) return
        applyFilters({...applied, dateFrom, dateTo}, 'summary')
    }

    function inspectRow(row: UsageAmounts & {userId?: string; userEmail?: string; model?: string; organizationId?: string}) {
        if (filtersDirty || query.loading) return
        if (row.organizationId && !isSuperAdmin) return
        if (row.organizationId && row.coverage.assistantMessages === '0') return
        if (row.userId && row.userEmail) setUserLabel({id: row.userId, email: row.userEmail})
        applyFilters({...applied,
            ...(row.userId ? {userId: row.userId} : {}),
            ...(row.model ? {model: row.model} : {}),
            ...(row.organizationId ? {organizationId: row.organizationId} : {}),
        }, 'summary')
    }

    function clearFilter(filter: 'period' | 'model' | 'user' | 'organization') {
        if (filtersDirty || query.loading) return
        const defaults = createDefaultUsageDraftFilter()
        if (filter === 'period') {
            applyFilters({...applied, dateFrom: defaults.dateFrom, dateTo: defaults.dateTo})
        } else if (filter === 'model') {
            applyFilters({...applied, model: ''})
        } else if (filter === 'user') {
            setUserLabel(null)
            applyFilters({...applied, userId: ''})
        } else {
            organizations.setQuery('')
            applyFilters({...applied, organizationId: ''})
        }
    }

    function resetFilters() {
        const defaults =
            createDefaultUsageDraftFilter()

        setFilterError('')
        setDraftDateFrom(defaults.dateFrom)
        setDraftDateTo(defaults.dateTo)
        setDraftModel('')
        setDraftOrganizationId('')
        setDraftUserId('')
        setUserLabel(null)
        organizations.setQuery('')

        updateUrl({
            tab,
            page: 0,
            budgetStatus: undefined,
            dateFrom: defaults.dateFrom,
            dateTo: defaults.dateTo,
            model: '',
            organizationId: '',
        })
    }

    function selectTab(
        nextTab: UsageReportTab,
    ) {
        if (nextTab === 'organizations' && !isSuperAdmin) return
        if (nextTab === 'organizations' && appliedOrganizationId) {
            applyFilters({...applied, organizationId: ''}, 'organizations')
            return
        }
        if (appliedUserId && appliedUserLabel) {
            setUserLabel({ id: appliedUserId, email: appliedUserLabel })
        }
        updateUrl({
            tab: nextTab,
            page: 0,
            dateFrom: appliedDateFrom,
            dateTo: appliedDateTo,
            model: appliedModel,
            userId: appliedUserId,
            organizationId: isSuperAdmin
                ? appliedOrganizationId
                : '',
        })
    }

    function goToPage(
        nextPage: number,
    ) {
        const safePage = Math.max(
            0,
            nextPage,
        )

        updateUrl({
            tab,
            page: safePage,
            dateFrom: appliedDateFrom,
            dateTo: appliedDateTo,
            model: appliedModel,
            userId: appliedUserId,
            organizationId: isSuperAdmin
                ? appliedOrganizationId
                : '',
        })
    }

    function updateUrl(
        values: Parameters<
            typeof buildUsageSearchParams
        >[0],
    ) {
        const next = buildUsageSearchParams({order, budgetStatus, ...values})
        if (next.toString() === searchParams.toString()) return
        setSearchParams(
            next,
            {replace: false},
        )
    }

    const scopeLabel =
        isSuperAdmin
            ? appliedOrganizationId
                ? (
                    organizations
                        .appliedOrganizationName
                    || 'Выбранная организация'
                )
                : 'Все организации'
            : 'Моя организация'

    const matchingUser = query.rows.find(row =>
        'userId' in row && row.userId === appliedUserId && 'userEmail' in row)
    const appliedUserLabel = matchingUser && 'userEmail' in matchingUser
        ? matchingUser.userEmail
        : userLabel?.id === appliedUserId ? userLabel.email : appliedUserId

    return {
        reportIdentity: currentUser?.id ?? '', reloadToken, csvContext,
        totals, budget, exportFiltered, exportPending: exportTask.pending, filteredExportError: exportTask.error,
        exportReady: exportTask.ready, downloadExport: exportTask.download,
        tab,
        budgetStatus,
        clearBudgetStatus: () => updateUrl({tab, page: 0, ...applied, budgetStatus: undefined}),
        order,
        changeOrder: (value: NonNullable<UsageSummaryFilter['order']>) => {
            if (value !== order) {
                updateUrl({tab, page: 0, ...applied, order: value})
            }
        },
        page,
        hasNext: query.hasNext,
        hasPrevious: query.hasPrevious,
        rows: query.rows,
        loading: query.loading,
        error: query.error,
        filterError,
        filtersDirty,
        appliedModel,
        appliedDateFrom,
        appliedDateTo,
        appliedOrganizationId,
        appliedUserId,
        appliedUserLabel,
        isSuperAdmin,
        scopeLabel,

        draftDateFrom,
        draftDateTo,
        draftModel,
        draftOrganizationId,
        draftOrganizationName:
            organizations.draftOrganizationName,

        organizationOptions:
            organizations.options,
        organizationSearchPending:
            organizations.loading,
        organizationSearchError:
            organizations.error,
        modelPickerOptions:
            models.options,
        modelOptionsLoading:
            models.loading,
        modelOptionsError:
            models.error,

        setOrganizationQuery:
            organizations.setQuery,
        changeDraftDateFrom,
        changeDraftDateTo,
        changeDraftModel,
        changeDraftOrganization,
        applyFilters: () => applyFilters(),
        inspectModel,
        inspectUser,
        inspectDay,
        inspectRow,
        inspectRange,
        clearFilter,
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
