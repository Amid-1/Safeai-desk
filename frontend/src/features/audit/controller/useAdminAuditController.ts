// frontend/src/features/audit/controller/useAdminAuditController.ts
// ============================================================
// frontend/src/features/audit/controller/useAdminAuditController.ts
// Контроллер журнала аудита: фильтры, адресная строка, пагинация и права.
// ============================================================
import { useCallback, useEffect, useMemo, useState } from 'react'
import type { AuditEvent } from '../../admin/api/adminApi'
import { useAuth } from '../../auth/state/useAuth'
import { auditDraftFiltersEqual } from '../ui/types'
import type { AuditDraftFilter, DatePreset } from '../ui/types'
import useAuditDirectories from '../hooks/useAuditDirectories'
import useAuditEvents from '../hooks/useAuditEvents'
import {
    applyAuditDatePreset,
    buildAuditSearch,
    createResetAuditFilter,
    readAuditUrlState,
    toAuditEventFilter,
} from '../lib/adminAudit.helpers'

export function useAdminAuditController() {
    const {
        currentUser,
    } = useAuth()

    const superAdmin =
        currentUser?.roles.includes(
            'SUPER_ADMIN',
        ) ?? false

    const initialState =
        useMemo(
            () =>
                readAuditUrlState(
                    window.location.search,
                    superAdmin,
                ),
            [
                superAdmin,
            ],
        )

    const [
        selectedEvent,
        setSelectedEvent,
    ] = useState<AuditEvent | null>(
        null,
    )

    const [
        page,
        setPage,
    ] = useState(
        initialState.page,
    )

    const [
        reloadToken,
        setReloadToken,
    ] = useState(0)

    const [
        requestTransitioning,
        setRequestTransitioning,
    ] = useState(false)

    const [
        filterError,
        setFilterError,
    ] = useState('')

    const [
        draftFilter,
        setDraftFilter,
    ] = useState<AuditDraftFilter>(
        initialState.draftFilter,
    )

    const [
        appliedDraftFilter,
        setAppliedDraftFilter,
    ] = useState<AuditDraftFilter>(
        initialState.draftFilter,
    )

    const [
        appliedFilter,
        setAppliedFilter,
    ] = useState(
        () =>
            toAuditEventFilter(
                initialState.draftFilter,
                superAdmin,
            ).filter,
    )

    const {
        eventTypes,
        organizations,
        actors,

        loading:
            directoriesLoading,

        eventTypesError,
        organizationsError,
        actorsError,

        searchOrganizations,
        searchActors,
    } = useAuditDirectories(
        superAdmin,
    )

    const handlePageOutOfRange =
        useCallback(
            (
                correctedPage: number,
            ) => {
                setPage(
                    correctedPage,
                )
            },
            [],
        )

    // После отзыва SUPER_ADMIN в запрос не передаётся фильтр чужой организации.
    const effectiveAppliedFilter = useMemo(
        () => superAdmin
            ? appliedFilter
            : { ...appliedFilter, targetOrganizationId: undefined },
        [appliedFilter, superAdmin],
    )

    const {
        events,
        pageResponse,
        loading,
        error:
            loadError,
    } = useAuditEvents({
        page,
        filter:
            effectiveAppliedFilter,
        reloadToken,
        onPageOutOfRange:
            handlePageOutOfRange,
    })

    const effectiveLoading =
        loading
        || requestTransitioning

    useEffect(() => {
        if (!loading) {
            return
        }

        queueMicrotask(() => {
            setRequestTransitioning(
                false,
            )
        })
    }, [
        loading,
    ])

    const filtersDirty =
        useMemo(
            () =>
                !auditDraftFiltersEqual(
                    draftFilter,
                    appliedDraftFilter,
                ),
            [
                draftFilter,
                appliedDraftFilter,
            ],
        )

    const organizationNameById =
        useMemo(
            () =>
                new Map(
                    organizations.map(
                        (
                            organization,
                        ) => [
                            organization
                                .targetOrganizationId,
                            organization
                                .targetOrganizationName,
                        ],
                    ),
                ),
            [
                organizations,
            ],
        )

    useEffect(() => {
        if (superAdmin) {
            return
        }

        let active =
            true

        queueMicrotask(() => {
            if (!active) {
                return
            }

            setDraftFilter(
                (
                    current,
                ) => ({
                    ...current,
                    targetOrganizationId:
                        '',
                }),
            )

            setAppliedDraftFilter(
                (
                    current,
                ) => ({
                    ...current,
                    targetOrganizationId:
                        '',
                }),
            )

            setAppliedFilter(
                (
                    current,
                ) => ({
                    ...current,
                    targetOrganizationId:
                        undefined,
                }),
            )
        })

        return () => {
            active =
                false
        }
    }, [
        superAdmin,
    ])

    useEffect(() => {
        const search =
            buildAuditSearch(
                appliedDraftFilter,
                page,
                superAdmin,
            )

        const nextUrl =
            `${window.location.pathname}`
            + search
            + window.location.hash

        window.history.replaceState(
            window.history.state,
            '',
            nextUrl,
        )
    }, [
        appliedDraftFilter,
        page,
        superAdmin,
    ])

    function applyFilters() {
        setFilterError('')

        try {
            const normalized =
                toAuditEventFilter(
                    draftFilter,
                    superAdmin,
                )

            setRequestTransitioning(
                true,
            )

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

            setReloadToken(
                (
                    value,
                ) =>
                    value + 1,
            )
        } catch (
            error
        ) {
            setFilterError(
                error instanceof Error
                    ? error.message
                    : (
                        'Некорректные '
                        + 'фильтры аудита.'
                    ),
            )
        }
    }

    function resetFilters() {
        const reset =
            createResetAuditFilter()

        const normalized =
            toAuditEventFilter(
                reset,
                superAdmin,
            )

        setRequestTransitioning(
            true,
        )

        setDraftFilter(
            normalized.draft,
        )

        setAppliedDraftFilter(
            normalized.draft,
        )

        setAppliedFilter(
            normalized.filter,
        )

        setFilterError('')
        setPage(0)

        setReloadToken(
            (
                value,
            ) =>
                value + 1,
        )
    }

    function applyDatePreset(
        preset:
            DatePreset,
    ) {
        setFilterError('')

        setDraftFilter(
            (
                current,
            ) =>
                applyAuditDatePreset(
                    current,
                    preset,
                ),
        )
    }

    function retryLoad() {
        setRequestTransitioning(
            true,
        )

        setReloadToken(
            (
                value,
            ) =>
                value + 1,
        )
    }

    function changePage(
        nextPage: number,
    ) {
        setRequestTransitioning(
            true,
        )

        setPage(
            nextPage,
        )
    }

    const selectedOrganizationFallback =
        selectedEvent
            ? organizationNameById.get(
                selectedEvent
                    .targetOrganizationId,
            ) ?? undefined
            : undefined

    const showTable =
        events.length > 0

    return {
        superAdmin,
        selectedEvent,
        draftFilter,
        eventTypes,
        organizations,
        actors,
        directoriesLoading,
        eventTypesError,
        organizationsError,
        actorsError,
        filterError,
        filtersDirty,
        effectiveLoading,
        events,
        loadError,
        pageResponse,
        selectedOrganizationFallback,
        showTable,
        searchActors,
        searchOrganizations,
        setDraftFilter,
        setSelectedEvent,
        applyDatePreset,
        applyFilters,
        resetFilters,
        retryLoad,
        changePage,
    }
}

export type AdminAuditController = ReturnType<typeof useAdminAuditController>
