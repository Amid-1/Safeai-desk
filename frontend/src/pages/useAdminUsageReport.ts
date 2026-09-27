// frontend/src/pages/useAdminUsageReport.ts

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
} from '../auth/useAuth'
import type {
    UsageFilterOption,
} from '../components/admin/usage/UsageFilterCombobox'
import {
    buildUsageSearchParams,
    createDefaultUsageDraftFilter,
    readUsageUrlState,
    toUsageFilter,
} from './adminUsage.helpers'
import type {
    UsageReportTab,
} from './adminUsage.helpers'
import {
    useAdminUsageModelOptions,
} from './useAdminUsageModelOptions'
import {
    useAdminUsageOrganizationOptions,
} from './useAdminUsageOrganizationOptions'
import {
    useAdminUsageReportQuery,
} from './useAdminUsageReportQuery'

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

    const [appliedDateFrom, setAppliedDateFrom] =
        useState(initial.draft.dateFrom)
    const [appliedDateTo, setAppliedDateTo] =
        useState(initial.draft.dateTo)
    const [appliedModel, setAppliedModel] =
        useState(initial.draft.model)
    const [appliedOrganizationId, setAppliedOrganizationId] =
        useState(initial.draft.organizationId)

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
        enabled: tab === 'summary',
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
        organizationId:
            appliedOrganizationId,
        isSuperAdmin,
        reloadToken,
    })

    function changeDraftDateFrom(
        value: string,
    ) {
        setDraftDateFrom(value)
        setDraftModel('')
        setFilterError('')
    }

    function changeDraftDateTo(
        value: string,
    ) {
        setDraftDateTo(value)
        setDraftModel('')
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
        organizations.setQuery('')
        setDraftModel('')
        setFilterError('')
    }

    function applyFilters() {
        try {
            const normalized = toUsageFilter(
                {
                    dateFrom: draftDateFrom,
                    dateTo: draftDateTo,
                    model: draftModel,
                    organizationId:
                        draftOrganizationId,
                },
                isSuperAdmin,
            )

            setFilterError('')
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

            updateUrl({
                tab,
                page: 0,
                dateFrom:
                    normalized.draft.dateFrom,
                dateTo:
                    normalized.draft.dateTo,
                model:
                    normalized.draft.model,
                organizationId:
                    normalized.organizationId,
            })
        } catch (validationError) {
            setFilterError(
                validationError instanceof Error
                    ? validationError.message
                    : 'Некорректные параметры отчёта.',
            )
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
        setTab(nextTab)
        setPage(0)

        updateUrl({
            tab: nextTab,
            page: 0,
            dateFrom: appliedDateFrom,
            dateTo: appliedDateTo,
            model: appliedModel,
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

    return {
        tab,
        page,
        hasNext: query.hasNext,
        hasPrevious: query.hasPrevious,
        rows: query.rows,
        loading: query.loading,
        error: query.error,
        filterError,
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
