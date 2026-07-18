import {
    useCallback,
    useMemo,
    useState,
} from 'react'

import type {
    AuditEvent,
    AuditEventFilter,
} from '../api/adminApi'

import {
    useAuth,
} from '../auth/AuthContext'

import {
    toUtcExclusiveEndOfDayIso,
    toUtcStartOfDayIso,
} from '../utils/date'

import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'

import AuditDetailsModal
    from '../components/admin/audit/AuditDetailsModal'

import AuditFilters
    from '../components/admin/audit/AuditFilters'

import AuditTable
    from '../components/admin/audit/AuditTable'

import {
    EMPTY_AUDIT_DRAFT_FILTER,
} from '../components/admin/audit/types'

import type {
    AuditDraftFilter,
    DatePreset,
} from '../components/admin/audit/types'

import useAuditDirectories
    from '../hooks/admin/useAuditDirectories'

import useAuditEvents
    from '../hooks/admin/useAuditEvents'

function AdminAuditPage() {
    const { currentUser } = useAuth()

    const superAdmin =
        currentUser?.roles.includes(
            'SUPER_ADMIN',
        ) ?? false

    const [selectedEvent, setSelectedEvent] =
        useState<AuditEvent | null>(null)

    const [page, setPage] =
        useState(0)

    const [reloadToken, setReloadToken] =
        useState(0)

    const [filterError, setFilterError] =
        useState('')

    const [draftFilter, setDraftFilter] =
        useState<AuditDraftFilter>(
            EMPTY_AUDIT_DRAFT_FILTER,
        )

    const [
        appliedDraftFilter,
        setAppliedDraftFilter,
    ] = useState<AuditDraftFilter>(
        EMPTY_AUDIT_DRAFT_FILTER,
    )

    const [appliedFilter, setAppliedFilter] =
        useState<AuditEventFilter>({})

    const {
        organizations,
        users,
        loading: directoriesLoading,
        error: directoriesError,
    } = useAuditDirectories(superAdmin)

    const handlePageOutOfRange =
        useCallback((correctedPage: number) => {
            setPage(correctedPage)
        }, [])

    const {
        events,
        totalPages,
        loading,
        error: loadError,
    } = useAuditEvents({
        page,
        filter: appliedFilter,
        reloadToken,
        onPageOutOfRange:
        handlePageOutOfRange,
    })

    const closeDetailsModal =
        useCallback(() => {
            setSelectedEvent(null)
        }, [])

    const filtersDirty = useMemo(() => {
        return (
            draftFilter.eventType !==
            appliedDraftFilter.eventType ||
            draftFilter.userId !==
            appliedDraftFilter.userId ||
            draftFilter.dateFrom !==
            appliedDraftFilter.dateFrom ||
            draftFilter.dateTo !==
            appliedDraftFilter.dateTo ||
            draftFilter.organizationId !==
            appliedDraftFilter.organizationId
        )
    }, [
        draftFilter,
        appliedDraftFilter,
    ])

    const organizationNameById =
        useMemo(() => {
            return new Map(
                organizations.map(
                    (organization) => [
                        organization.id,
                        organization.name,
                    ],
                ),
            )
        }, [organizations])

    const visibleUsers = useMemo(() => {
        const effectiveOrganizationId =
            superAdmin
                ? draftFilter.organizationId
                : currentUser?.organizationId ?? ''

        return users
            .filter((user) => {
                if (!effectiveOrganizationId) {
                    return true
                }

                return (
                    user.organizationId ===
                    effectiveOrganizationId
                )
            })
            .sort((left, right) =>
                left.email.localeCompare(
                    right.email,
                    'ru',
                    {
                        sensitivity: 'base',
                    },
                ),
            )
    }, [
        users,
        superAdmin,
        draftFilter.organizationId,
        currentUser?.organizationId,
    ])

    function applyFilters(): void {
        setFilterError('')

        if (
            draftFilter.dateFrom &&
            draftFilter.dateTo &&
            draftFilter.dateFrom >
            draftFilter.dateTo
        ) {
            setFilterError(
                'Дата начала периода не может быть позже даты окончания.',
            )
            return
        }

        if (
            draftFilter.userId &&
            !visibleUsers.some(
                (user) =>
                    user.id ===
                    draftFilter.userId,
            )
        ) {
            setFilterError(
                'Выбранный пользователь не относится к выбранной организации.',
            )
            return
        }

        try {
            const nextFilter:
                AuditEventFilter = {
                eventType:
                    draftFilter.eventType ||
                    undefined,

                userId:
                    draftFilter.userId ||
                    undefined,

                dateFrom:
                    draftFilter.dateFrom
                        ? toUtcStartOfDayIso(
                            draftFilter.dateFrom,
                        )
                        : undefined,

                dateTo:
                    draftFilter.dateTo
                        ? toUtcExclusiveEndOfDayIso(
                            draftFilter.dateTo,
                        )
                        : undefined,

                organizationId:
                    superAdmin
                        ? draftFilter
                            .organizationId ||
                        undefined
                        : undefined,
            }

            setPage(0)
            setAppliedDraftFilter({
                ...draftFilter,
            })
            setAppliedFilter(nextFilter)
        } catch (error) {
            setFilterError(
                error instanceof Error
                    ? error.message
                    : 'Некорректный диапазон дат.',
            )
        }
    }

    function resetFilters(): void {
        setDraftFilter(
            EMPTY_AUDIT_DRAFT_FILTER,
        )
        setAppliedDraftFilter(
            EMPTY_AUDIT_DRAFT_FILTER,
        )
        setAppliedFilter({})
        setFilterError('')
        setPage(0)
    }

    function changeOrganization(
        organizationId: string,
    ): void {
        setDraftFilter((current) => ({
            ...current,
            organizationId,
            userId: '',
        }))
    }

    function applyDatePreset(
        preset: DatePreset,
    ): void {
        setFilterError('')

        if (preset === 'all') {
            setDraftFilter((current) => ({
                ...current,
                dateFrom: '',
                dateTo: '',
            }))
            return
        }

        const today =
            startOfLocalDay(new Date())

        if (preset === 'today') {
            const value =
                toDateInputValue(today)

            setDraftFilter((current) => ({
                ...current,
                dateFrom: value,
                dateTo: value,
            }))
            return
        }

        if (preset === 'yesterday') {
            const yesterday =
                addDays(today, -1)

            const value =
                toDateInputValue(yesterday)

            setDraftFilter((current) => ({
                ...current,
                dateFrom: value,
                dateTo: value,
            }))
            return
        }

        const daysBack =
            preset === 'last7Days'
                ? 6
                : 29

        setDraftFilter((current) => ({
            ...current,
            dateFrom:
                toDateInputValue(
                    addDays(
                        today,
                        -daysBack,
                    ),
                ),
            dateTo:
                toDateInputValue(today),
        }))
    }

    return (
        <div className="page">
            <h1>Аудит событий</h1>

            <AuditFilters
                draftFilter={draftFilter}
                organizations={organizations}
                visibleUsers={visibleUsers}
                organizationNameById={
                    organizationNameById
                }
                superAdmin={superAdmin}
                loading={loading}
                directoriesLoading={
                    directoriesLoading
                }
                directoriesError={
                    directoriesError
                }
                filterError={filterError}
                filtersDirty={filtersDirty}
                onFilterChange={
                    setDraftFilter
                }
                onOrganizationChange={
                    changeOrganization
                }
                onDatePreset={
                    applyDatePreset
                }
                onApply={applyFilters}
                onReset={resetFilters}
            />

            {loading && (
                <LoadingState message="Загрузка событий аудита..." />
            )}

            {!loading && loadError && (
                <ErrorState
                    title="Ошибка загрузки"
                    message={loadError}
                    action={
                        <button
                            type="button"
                            onClick={() =>
                                setReloadToken(
                                    (value) =>
                                        value + 1,
                                )
                            }
                        >
                            Повторить
                        </button>
                    }
                />
            )}

            {!loading &&
                !loadError &&
                events.length === 0 && (
                    <EmptyState message="События аудита не найдены." />
                )}

            {!loading &&
                !loadError &&
                events.length > 0 && (
                    <AuditTable
                        events={events}
                        organizationNameById={
                            organizationNameById
                        }
                        page={page}
                        totalPages={totalPages}
                        loading={loading}
                        onOpenDetails={
                            setSelectedEvent
                        }
                        onPageChange={setPage}
                    />
                )}

            {selectedEvent && (
                <AuditDetailsModal
                    event={selectedEvent}
                    organizationName={
                        organizationNameById.get(
                            selectedEvent
                                .organizationId,
                        ) ??
                        selectedEvent
                            .organizationId
                    }
                    onClose={
                        closeDetailsModal
                    }
                />
            )}
        </div>
    )
}

function startOfLocalDay(
    date: Date,
): Date {
    return new Date(
        date.getFullYear(),
        date.getMonth(),
        date.getDate(),
    )
}

function addDays(
    date: Date,
    days: number,
): Date {
    const result = new Date(date)

    result.setDate(
        result.getDate() + days,
    )

    return result
}

function toDateInputValue(
    date: Date,
): string {
    const year = date.getFullYear()

    const month = String(
        date.getMonth() + 1,
    ).padStart(2, '0')

    const day = String(
        date.getDate(),
    ).padStart(2, '0')

    return `${year}-${month}-${day}`
}

export default AdminAuditPage
