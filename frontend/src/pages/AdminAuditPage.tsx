// frontend/src/pages/AdminAuditPage.tsx
import {
    useCallback,
    useEffect,
    useMemo,
    useState,
} from 'react'
import type {
    AuditEvent,
} from '../api/adminApi'
import {
    useAuth,
} from '../auth/AuthContext'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import PageErrorBoundary
    from '../components/PageErrorBoundary'
import AuditDetailsModal
    from '../components/admin/audit/AuditDetailsModal'
import AuditFilters
    from '../components/admin/audit/AuditFilters'
import AuditTable
    from '../components/admin/audit/AuditTable'
import {
    auditDraftFiltersEqual,
} from '../components/admin/audit/types'
import type {
    AuditDraftFilter,
    DatePreset,
} from '../components/admin/audit/types'
import useAuditDirectories
    from '../hooks/admin/useAuditDirectories'
import useAuditEvents
    from '../hooks/admin/useAuditEvents'
import {
    applyAuditDatePreset,
    buildAuditSearch,
    createResetAuditFilter,
    readAuditUrlState,
    toAuditEventFilter,
} from './adminAudit.helpers'

function AdminAuditPage() {
    return (
        <PageErrorBoundary>
            <AdminAuditPageContent />
        </PageErrorBoundary>
    )
}

function AdminAuditPageContent() {
    const { currentUser } = useAuth()

    const superAdmin =
        currentUser?.roles.includes(
            'SUPER_ADMIN',
        ) ?? false

    const initialState = useMemo(
        () =>
            readAuditUrlState(
                window.location.search,
                superAdmin,
            ),
        [superAdmin],
    )

    const [
        selectedEvent,
        setSelectedEvent,
    ] = useState<AuditEvent | null>(
        null,
    )

    const [page, setPage] =
        useState(initialState.page)

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
            (correctedPage: number) => {
                setPage(correctedPage)
            },
            [],
        )

    const {
        events,
        pageResponse,
        loading,
        error: loadError,
    } = useAuditEvents({
        page,
        filter: appliedFilter,
        reloadToken,
        onPageOutOfRange:
            handlePageOutOfRange,
    })

    const effectiveLoading =
        loading || requestTransitioning

    useEffect(() => {
        if (loading) {
            setRequestTransitioning(
                false,
            )
        }
    }, [loading])

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
                        (organization) => [
                            organization
                                .targetOrganizationId,
                            organization
                                .targetOrganizationName,
                        ],
                    ),
                ),
            [organizations],
        )

    useEffect(() => {
        if (superAdmin) {
            return
        }

        setDraftFilter(
            (current) => ({
                ...current,
                targetOrganizationId:
                    '',
            }),
        )

        setAppliedDraftFilter(
            (current) => ({
                ...current,
                targetOrganizationId:
                    '',
            }),
        )

        setAppliedFilter(
            (current) => ({
                ...current,
                targetOrganizationId:
                    undefined,
            }),
        )
    }, [superAdmin])

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
                (value) => value + 1,
            )
        } catch (error) {
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
            (value) => value + 1,
        )
    }

    function applyDatePreset(
        preset: DatePreset,
    ) {
        setFilterError('')

        setDraftFilter(
            (current) =>
                applyAuditDatePreset(
                    current,
                    preset,
                ),
        )
    }

    const selectedOrganizationFallback =
        selectedEvent
            ? organizationNameById.get(
                selectedEvent
                    .targetOrganizationId,
            ) ?? undefined
            : undefined

    return (
        <div className="page">
            <h1>Аудит событий</h1>

            <p className="muted">
                Время отображается в
                часовом поясе браузера.
                Actor и target organization
                являются независимыми
                измерениями.
            </p>

            <AuditFilters
                draftFilter={
                    draftFilter
                }

                eventTypes={
                    eventTypes
                }
                organizations={
                    organizations
                }
                actors={actors}

                superAdmin={
                    superAdmin
                }
                loading={
                    effectiveLoading
                }
                directoriesLoading={
                    directoriesLoading
                }

                eventTypesError={
                    eventTypesError
                }
                organizationsError={
                    organizationsError
                }
                actorsError={
                    actorsError
                }
                filterError={
                    filterError
                }
                filtersDirty={
                    filtersDirty
                }

                onFilterChange={
                    setDraftFilter
                }
                onActorSearch={
                    searchActors
                }
                onOrganizationSearch={
                    searchOrganizations
                }
                onDatePreset={
                    applyDatePreset
                }
                onApply={
                    applyFilters
                }
                onReset={
                    resetFilters
                }
            />

            {effectiveLoading && (
                <LoadingState
                    message={
                        'Загрузка событий аудита...'
                    }
                />
            )}

            {!effectiveLoading
                && loadError
                && (
                    <ErrorState
                        title="Ошибка загрузки"
                        message={
                            loadError
                        }
                        action={
                            <button
                                type="button"
                                onClick={() => {
                                    setRequestTransitioning(
                                        true,
                                    )
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

            {!effectiveLoading
                && !loadError
                && events.length === 0
                && (
                    <EmptyState
                        message={
                            'События аудита '
                            + 'не найдены.'
                        }
                    />
                )}

            {!effectiveLoading
                && !loadError
                && events.length > 0
                && (
                    <AuditTable
                        events={events}
                        organizations={
                            organizations
                        }

                        page={
                            pageResponse.page
                        }
                        totalPages={
                            pageResponse
                                .totalPages
                        }
                        totalElements={
                            pageResponse
                                .totalElements
                        }
                        loading={
                            effectiveLoading
                        }

                        onOpenDetails={
                            setSelectedEvent
                        }
                        onPageChange={
                            (nextPage) => {
                                setRequestTransitioning(
                                    true,
                                )
                                setPage(nextPage)
                            }
                        }
                    />
                )}

            {selectedEvent && (
                <AuditDetailsModal
                    event={
                        selectedEvent
                    }
                    organizationFallbackName={
                        selectedOrganizationFallback
                    }
                    onClose={() =>
                        setSelectedEvent(
                            null,
                        )
                    }
                />
            )}
        </div>
    )
}

export default AdminAuditPage
