import {
    useEffect,
    useRef,
    useState,
} from 'react'
import {
    getUserDetails,
    getUserStatistics,
    searchUsers,
} from '../api/userApi'
import type {
    User,
    UserDetails,
    UserStatistics,
} from '../api/userApi'
import type {
    AuthUser,
} from '../api/authApi'
import {
    isProtectedOrganization,
    searchOrganizationDirectory,
} from '../api/organizationApi'
import type {
    OrganizationDirectoryItem,
} from '../api/organizationApi'
import {
    getApiErrorMessage,
} from '../api/http'
import {
    normalizePageResponse,
} from '../utils/page'
import type {
    AdminUsersFilter,
} from '../components/admin/users/AdminUsersView'
import {
    findOrganizationName,
    isRequestAborted,
} from './adminUsersSupport'

const PAGE_SIZE = 50

const EMPTY_STATISTICS: UserStatistics = {
    total: 0,
    administrators: 0,
    users: 0,
    enabled: 0,
    disabled: 0,
}

export function useAdminUsersDirectory(
    currentUser: AuthUser,
    currentUserIsSuperAdmin: boolean,
) {
    const [users, setUsers] =
        useState<User[]>([])
    const [statistics, setStatistics] =
        useState<UserStatistics>(
            EMPTY_STATISTICS,
        )
    const [organizations, setOrganizations] =
        useState<OrganizationDirectoryItem[]>([])

    const [loadError, setLoadError] =
        useState('')
    const [organizationsError, setOrganizationsError] =
        useState('')
    const [loading, setLoading] =
        useState(true)
    const [organizationsLoading, setOrganizationsLoading] =
        useState(false)

    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] =
        useState(0)
    const [reloadToken, setReloadToken] =
        useState(0)
    const [filter, setFilter] =
        useState<AdminUsersFilter>('ALL')
    const [status, setStatus] =
        useState<'ALL' | 'ACTIVE' | 'DISABLED'>('ALL')
    const [searchDraft, setSearchDraft] = useState('')
    const [appliedSearch, setAppliedSearch] = useState('')
    const [totalMatched, setTotalMatched] = useState(0)

    // Query is applied automatically after a pause. Deleting the text
    // must immediately clear the previous server-side filter.
    useEffect(() => {
        const query = searchDraft.trim()
        if (query === appliedSearch) return
        if (query.length < 2 || query.length > 120) return
        const timeout = window.setTimeout(() => {
            setAppliedSearch(query)
            setPage(0)
        }, 350)
        return () => window.clearTimeout(timeout)
    }, [searchDraft, appliedSearch])

    const [detailsUser, setDetailsUser] =
        useState<UserDetails | null>(null)
    const [detailsLoadingUserId, setDetailsLoadingUserId] =
        useState<string | null>(null)
    const [detailsError, setDetailsError] =
        useState('')

    const usersSequenceRef = useRef(0)
    const usersControllerRef =
        useRef<AbortController | null>(null)
    const detailsSequenceRef = useRef(0)
    const detailsControllerRef =
        useRef<AbortController | null>(null)
    const organizationsControllerRef =
        useRef<AbortController | null>(null)

    useEffect(() => {
        const sequence =
            ++usersSequenceRef.current

        usersControllerRef.current?.abort()

        const controller =
            new AbortController()

        usersControllerRef.current = controller

        async function loadUsers() {
            setLoading(true)
            setLoadError('')

            try {
                const [
                    response,
                    loadedStatistics,
                ] = await Promise.all([
                    searchUsers(
                        page,
                        PAGE_SIZE,
                        {
                            role: filter === 'ALL' ? undefined : filter,
                            enabled: status === 'ALL'
                                ? undefined
                                : status === 'ACTIVE',
                            query: appliedSearch || undefined,
                        },
                        {signal: controller.signal},
                    ),
                    getUserStatistics({
                        signal:
                            controller.signal,
                    }),
                ])

                if (
                    sequence
                    !== usersSequenceRef.current
                ) {
                    return
                }

                const normalized =
                    normalizePageResponse(response)

                if (
                    normalized.totalPages === 0
                    && page !== 0
                ) {
                    setPage(0)
                    return
                }

                if (
                    normalized.totalPages > 0
                    && page
                        >= normalized.totalPages
                ) {
                    setPage(
                        normalized.totalPages - 1,
                    )
                    return
                }

                setUsers(normalized.content)
                setTotalMatched(normalized.totalElements)
                setTotalPages(
                    normalized.totalPages,
                )
                setStatistics(
                    loadedStatistics,
                )
            } catch (error) {
                if (
                    sequence
                    === usersSequenceRef.current
                    && !isRequestAborted(error)
                ) {
                    setUsers([])
                    setTotalMatched(0)
                    setTotalPages(0)
                    setStatistics(
                        EMPTY_STATISTICS,
                    )
                    setLoadError(
                        getApiErrorMessage(
                            error,
                            'Не удалось загрузить пользователей.',
                        ),
                    )
                }
            } finally {
                if (
                    sequence
                    === usersSequenceRef.current
                ) {
                    setLoading(false)
                }
            }
        }

        void loadUsers()

        return () => {
            controller.abort()
            usersSequenceRef.current += 1
        }
    }, [
        page,
        reloadToken,
        filter,
        status,
        appliedSearch,
    ])

    useEffect(() => {
        organizationsControllerRef.current?.abort()

        if (!currentUserIsSuperAdmin) {
            let active = true

            queueMicrotask(() => {
                if (!active) {
                    return
                }

                setOrganizations([])
                setOrganizationsError('')
                setOrganizationsLoading(false)
            })

            return () => {
                active = false
            }
        }

        const controller =
            new AbortController()

        organizationsControllerRef.current =
            controller

        async function loadOrganizations() {
            setOrganizationsLoading(true)
            setOrganizationsError('')

            try {
                const loaded =
                    await searchOrganizationDirectory(
                        '',
                        50,
                        {
                            signal:
                                controller.signal,
                        },
                    )

                if (controller.signal.aborted) {
                    return
                }

                setOrganizations(
                    loaded.filter(
                        (organization) =>
                            organization.enabled
                            && !isProtectedOrganization({
                                type:
                                    organization.type,
                                protected:
                                    organization.protected,
                            }),
                    ),
                )
            } catch (error) {
                if (!isRequestAborted(error)) {
                    setOrganizations([])
                    setOrganizationsError(
                        getApiErrorMessage(
                            error,
                            'Не удалось загрузить каталог организаций.',
                        ),
                    )
                }
            } finally {
                if (!controller.signal.aborted) {
                    setOrganizationsLoading(false)
                }
            }
        }

        void loadOrganizations()

        return () => {
            controller.abort()
        }
    }, [currentUserIsSuperAdmin])

    useEffect(() => {
        return () => {
            usersControllerRef.current?.abort()
            detailsControllerRef.current?.abort()
            organizationsControllerRef.current?.abort()
        }
    }, [])

    function requestReloadFromFirstPage() {
        if (page === 0) {
            setReloadToken(
                (value) => value + 1,
            )
        } else {
            setPage(0)
        }
    }

    function changeFilter(
        nextFilter: AdminUsersFilter,
    ) {
        setFilter(nextFilter)
        setPage(0)
    }

    function changeStatus(next: 'ALL' | 'ACTIVE' | 'DISABLED') {
        setStatus(next)
        setPage(0)
    }

    function changeSearchDraft(value: string) {
        setSearchDraft(value)
        // The native clear icon must reset the applied server query immediately.
        if (!value.trim()) {
            setAppliedSearch('')
            setPage(0)
        }
    }

    function applySearch() {
        const next = searchDraft.trim()
        if (next.length > 120) {
            return
        }
        setAppliedSearch(next)
        setPage(0)
    }

    function clearSearch() {
        setSearchDraft('')
        setAppliedSearch('')
        setPage(0)
    }

    function selectSearchSuggestion(user: User) {
        // Exact email is unambiguous even for users sharing full names.
        setSearchDraft(user.email)
        setAppliedSearch(user.email)
        setPage(0)
    }

    async function openDetailsModal(
        user: User,
    ) {
        const sequence =
            ++detailsSequenceRef.current

        detailsControllerRef.current?.abort()

        const controller =
            new AbortController()

        detailsControllerRef.current =
            controller

        setDetailsLoadingUserId(user.id)
        setDetailsError('')

        try {
            const details = await getUserDetails(
                user.id,
                {
                    signal:
                        controller.signal,
                },
            )

            if (
                sequence
                !== detailsSequenceRef.current
            ) {
                return
            }

            setDetailsUser(details)
        } catch (error) {
            if (
                sequence
                === detailsSequenceRef.current
                && !isRequestAborted(error)
            ) {
                setDetailsError(
                    getApiErrorMessage(
                        error,
                        'Не удалось загрузить сведения о пользователе.',
                    ),
                )
                setDetailsUser({
                    ...user,
                    organizationName:
                        findOrganizationName(
                            user.organizationId,
                            organizations,
                        ),
                })
            }
        } finally {
            if (
                sequence
                === detailsSequenceRef.current
            ) {
                setDetailsLoadingUserId(null)
            }
        }
    }

    function closeDetailsModal() {
        detailsSequenceRef.current += 1
        detailsControllerRef.current?.abort()
        setDetailsUser(null)
        setDetailsError('')
        setDetailsLoadingUserId(null)
    }

    return {
        currentUser,
        users,
        statistics,
        organizations,
        loadError,
        organizationsError,
        loading,
        organizationsLoading,
        page,
        totalPages,
        filter,
        status,
        searchDraft,
        appliedSearch,
        totalMatched,
        setSearchDraft: changeSearchDraft,
        changeStatus,
        applySearch,
        clearSearch,
        selectSearchSuggestion,
        detailsUser,
        detailsLoadingUserId,
        detailsError,
        setPage,
        changeFilter,
        requestReloadFromFirstPage,
        reload: () => {
            setReloadToken(
                (value) => value + 1,
            )
        },
        openDetailsModal,
        closeDetailsModal,
    }
}
