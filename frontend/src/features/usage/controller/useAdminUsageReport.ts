// frontend/src/features/usage/controller/useAdminUsageReport.ts

import {
    useEffect,
    useRef,
    useState,
} from 'react'
import {
    useSearchParams,
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
import { REPORT_EXPORT_LIMIT, useBoundedReportExport } from '../../../shared/lib/useBoundedReportExport'
import { loadUsageReport } from '../lib/adminUsageReportLoader'
import type { UsageAmounts } from '../api/usageApi'
import { downloadUsageCsv } from '../ui/usageCsv'
import { useUsageBudget } from './useUsageBudget'

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

    const [initial] = useState(() =>
        readUsageUrlState(
            searchParams.toString(),
            isSuperAdmin,
        ),
    )

    const [tab, setTab] =
        useState<UsageReportTab>(
            initial.tab,
        )
    const [page, setPage] =
        useState(initial.page)
    const [filterError, setFilterError] =
        useState('')

    const [draftDateFrom, setDraftDateFrom] =
        useState(initial.draft.dateFrom)
    const [draftDateTo, setDraftDateTo] =
        useState(initial.draft.dateTo)
    const [draftModel, setDraftModel] =
        useState(initial.draft.model)
    const [draftOrganizationId, setDraftOrganizationId] =
        useState(initial.draft.organizationId)
    const [draftUserId, setDraftUserId] = useState(initial.draft.userId ?? '')

    const [appliedDateFrom, setAppliedDateFrom] =
        useState(initial.draft.dateFrom)
    const [appliedDateTo, setAppliedDateTo] =
        useState(initial.draft.dateTo)
    const [appliedModel, setAppliedModel] =
        useState(initial.draft.model)
    const [appliedOrganizationId, setAppliedOrganizationId] =
        useState(initial.draft.organizationId)
    const [appliedUserId, setAppliedUserId] = useState(initial.draft.userId ?? '')
    const [userLabel, setUserLabel] = useState<{ id: string; email: string } | null>(null)

    const [reloadToken, setReloadToken] =
        useState(0)

    /*
     * Auth may complete after the first render. Restore tenant URL state only
     * when SUPER_ADMIN authority becomes known; remove it when authority is
     * lost so an ADMIN can never drive cross-tenant filtering from the URL.
     */
    const previousSuperAdminRef =
        useRef(isSuperAdmin)

    useEffect(() => {
        const wasSuperAdmin =
            previousSuperAdminRef.current

        if (!wasSuperAdmin && isSuperAdmin) {
            const recovered = readUsageUrlState(
                searchParams.toString(),
                true,
            )

            setDraftOrganizationId(
                recovered.draft.organizationId,
            )
            setAppliedOrganizationId(
                recovered.draft.organizationId,
            )
        } else if (wasSuperAdmin && !isSuperAdmin) {
            setDraftOrganizationId('')
            setAppliedOrganizationId('')
        }

        previousSuperAdminRef.current =
            isSuperAdmin
    }, [
        isSuperAdmin,
        searchParams,
    ])

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
        enabled: currentUser !== null,
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
    })
    const totals = useAdminUsageReportQuery({
        enabled: currentUser !== null && tab !== 'daily',
        userId: currentUser?.id ?? '',
        userOrganizationId: currentUser?.organizationId ?? '',
        tab: 'daily', page: 0, dateFrom: appliedDateFrom, dateTo: appliedDateTo,
        model: appliedModel, filterUserId: appliedUserId,
        organizationId: appliedOrganizationId, isSuperAdmin, reloadToken,
    })
    const budget = useUsageBudget(currentUser?.id ?? '',
        isSuperAdmin ? appliedOrganizationId : currentUser?.organizationId ?? '', reloadToken)
    const exportTask = useBoundedReportExport(JSON.stringify([
        currentUser?.id, currentUser?.organizationId, isSuperAdmin, tab,
        appliedDateFrom, appliedDateTo, appliedModel, appliedUserId, appliedOrganizationId, reloadToken,
    ]))

    function exportFiltered() {
        if (!currentUser || query.loading || query.error || filtersDirty || (tab !== 'summary' && tab !== 'users')) return
        return exportTask.run<UsageAmounts>(async signal => {
            const normalized = toUsageFilter({
                dateFrom: appliedDateFrom, dateTo: appliedDateTo, model: appliedModel,
                userId: appliedUserId, organizationId: appliedOrganizationId,
            }, isSuperAdmin)
            const result = await loadUsageReport({
                tab, page: 0, pageSize: REPORT_EXPORT_LIMIT, filter: normalized.filter,
                organizationId: normalized.organizationId || null, superAdmin: isSuperAdmin, signal,
            })
            return !result.complete || result.hasNext || result.rows.length > REPORT_EXPORT_LIMIT ? null : result.rows
        }, rows => downloadUsageCsv(tab, rows, 'filtered'))
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

            setFilterError('')
            setTab(nextTab)
            setPage(0)
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
            setAppliedDateFrom(
                normalized.draft.dateFrom,
            )
            setAppliedDateTo(
                normalized.draft.dateTo,
            )
            setAppliedModel(
                normalized.draft.model,
            )
            setAppliedOrganizationId(
                normalized.organizationId,
            )
            setDraftUserId(normalized.draft.userId ?? '')
            setAppliedUserId(normalized.draft.userId ?? '')

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
        setAppliedUserId('')
        setUserLabel(null)
        organizations.setQuery('')
        setAppliedDateFrom(defaults.dateFrom)
        setAppliedDateTo(defaults.dateTo)
        setAppliedModel('')
        setAppliedOrganizationId('')
        setPage(0)

        updateUrl({
            tab,
            page: 0,
            dateFrom: defaults.dateFrom,
            dateTo: defaults.dateTo,
            model: '',
            organizationId: '',
        })
    }

    function selectTab(
        nextTab: UsageReportTab,
    ) {
        if (appliedUserId && appliedUserLabel) {
            setUserLabel({ id: appliedUserId, email: appliedUserLabel })
        }
        setTab(nextTab)
        setPage(0)

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

        setPage(safePage)

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
        setSearchParams(
            buildUsageSearchParams(values),
            {
                replace: true,
            },
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
        reportIdentity: currentUser?.id ?? '', reloadToken,
        totals, budget, exportFiltered, exportPending: exportTask.pending, filteredExportError: exportTask.error,
        tab,
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
