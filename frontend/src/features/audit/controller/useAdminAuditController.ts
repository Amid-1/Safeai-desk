// frontend/src/features/audit/controller/useAdminAuditController.ts
// ============================================================
// frontend/src/features/audit/controller/useAdminAuditController.ts
// Контроллер журнала аудита: фильтры, адресная строка, пагинация и права.
// ============================================================
import { useCallback, useEffect, useMemo, useState } from 'react'
import type { AuditEvent, AuditCategory, AuditMetric } from '../api/adminAuditApi'
import { getAuditEvents } from '../api/adminAuditApi'
import { REPORT_EXPORT_LIMIT, useBoundedReportExport } from '../../../shared/lib/useBoundedReportExport'
import { downloadAuditCsv } from '../ui/auditCsv'
import { useAuth } from '../../auth/state/useAuth'
import { auditDraftFiltersEqual } from '../ui/types'
import type { AuditDraftFilter, DatePreset } from '../ui/types'
import useAuditDirectories from '../hooks/useAuditDirectories'
import useAuditEvents from '../hooks/useAuditEvents'
import { useAuditSummary } from '../hooks/useAuditSummary'
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
    const authScope = JSON.stringify([currentUser?.id, currentUser?.organizationId, superAdmin])

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
        selectedEventState,
        setSelectedEventState,
    ] = useState<{ scope: string; event: AuditEvent | null }>({scope: authScope, event: null})
    const selectedEvent = selectedEventState.scope === authScope ? selectedEventState.event : null
    function setSelectedEvent(event: AuditEvent | null) {
        setSelectedEventState({scope: authScope, event})
    }

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
    const summary = useAuditSummary(authScope, effectiveAppliedFilter, reloadToken)

    const {
        events,
        pageResponse,
        loading,
        error:
            loadError,
    } = useAuditEvents({
        authScope,
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

    const exportTask = useBoundedReportExport(JSON.stringify([
        currentUser?.id, currentUser?.organizationId, superAdmin, effectiveAppliedFilter, reloadToken,
    ]))

    function exportFiltered() {
        if (!currentUser || effectiveLoading || loadError || filtersDirty) return
        return exportTask.run(async signal => {
            const result = await getAuditEvents(0, REPORT_EXPORT_LIMIT, effectiveAppliedFilter, { signal })
            return result.page !== 0 || result.totalElements > REPORT_EXPORT_LIMIT || result.totalPages > 1
                || result.content.length !== result.totalElements ? null : result.content
        }, rows => downloadAuditCsv(rows, 'filtered'))
    }

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

    const filtersDirty = !auditDraftFiltersEqual(draftFilter, appliedDraftFilter)

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
        applyDraftFilter(draftFilter)
    }

    function selectAuditMetric(metric?: AuditMetric) {
        if (filtersDirty || effectiveLoading) return
        applyDraftFilter({ ...appliedDraftFilter, metric })
    }

    function selectAuditCategory(category?: AuditCategory) {
        if (filtersDirty || effectiveLoading) return
        applyDraftFilter({ ...appliedDraftFilter, category, metric: undefined, eventType: '' })
    }

    function filterByActor(actorUserId: string) {
        // Do not discard unsubmitted edits or allow actions on stale results.
        if (!actorUserId || filtersDirty || effectiveLoading) return
        applyDraftFilter({ ...appliedDraftFilter, actorUserId, eventType: '', actorEmail: '' })
    }

    function applyDraftFilter(next: AuditDraftFilter) {
        setFilterError('')

        try {
            const normalized =
                toAuditEventFilter(
                    next,
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

    // The directory is paginated/searchable; a selected historical actor may be
    // absent. Reuse its already authorized event snapshot for the selected label.
    const selectedActorSnapshot = draftFilter.actorUserId && !actors.some(actor => actor.actorUserId === draftFilter.actorUserId)
        ? events.find(event => event.actorUserId === draftFilter.actorUserId)
        : undefined
    const visibleActors = selectedActorSnapshot ? [...actors, {
        actorUserId: selectedActorSnapshot.actorUserId,
        actorOrganizationId: selectedActorSnapshot.actorOrganizationId,
        actorEmail: selectedActorSnapshot.actorEmail,
        actorDisplayName: selectedActorSnapshot.actorDisplayName,
    }] : actors

    return {
        ...summary,
        selectAuditMetric,
        selectAuditCategory,
        exportFiltered, exportPending: exportTask.pending, filteredExportError: exportTask.error,
        superAdmin,
        selectedEvent,
        draftFilter,
        eventTypes,
        organizations,
        actors: visibleActors,
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
        filterByActor,
        resetFilters,
        retryLoad,
        changePage,
    }
}

export type AdminAuditController = ReturnType<typeof useAdminAuditController>
