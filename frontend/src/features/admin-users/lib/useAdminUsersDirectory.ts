// frontend/src/features/admin-users/lib/useAdminUsersDirectory.ts
import {
    useEffect,
    useCallback,
    useRef,
    useState,
} from 'react'
import {
    getUserDetails,
    getUserStatistics,
    searchUsers,
} from '../../../entities/user/api/userApi'
import type {
    User,
    UserDetails,
    UserStatistics,
} from '../../../entities/user/api/userApi'
import type {
    AuthUser,
} from '../../auth/api/authApi'
import {
    isProtectedOrganization,
    searchOrganizationDirectory,
} from '../../../entities/organization/api/organizationApi'
import type {
    OrganizationDirectoryItem,
} from '../../../entities/organization/api/organizationApi'
import {
    getApiErrorMessage,
} from '../../../shared/api/http'
import {
    normalizePageResponse,
} from '../../../shared/lib/page'
import { directoryPage, directoryUuid, useDirectoryUrl } from '../../../shared/lib/adminDirectoryUrl'
import type {
    AdminUsersFilter,
} from '../ui/AdminUsersView'
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
    const [displayOrganizations, setDisplayOrganizations] = useState<OrganizationDirectoryItem[]>([])

    const [loadError, setLoadError] =
        useState('')
    const [organizationsError, setOrganizationsError] =
        useState('')
    const [loading, setLoading] =
        useState(true)
    const [organizationsLoading, setOrganizationsLoading] =
        useState(false)

    const { params, change } = useDirectoryUrl()
    const organizationId = currentUserIsSuperAdmin ? directoryUuid(params.get('organizationId')) : ''
    const page = directoryPage(params.get('page'))
    const setPage = useCallback((next: number) => change({page: next ? String(next) : ''}), [change])
    const [totalPages, setTotalPages] =
        useState(0)
    const [reloadToken, setReloadToken] =
        useState(0)
    const rawRole = params.get('role')
    const filter: AdminUsersFilter = rawRole === 'ADMIN' || rawRole === 'USER'
        || (rawRole === 'SUPER_ADMIN' && currentUserIsSuperAdmin) ? rawRole : 'ALL'
    const rawStatus = params.get('status')
    const status: 'ALL' | 'ACTIVE' | 'DISABLED' = rawStatus === 'ACTIVE' || rawStatus === 'DISABLED' ? rawStatus : 'ALL'
    const appliedSearch = (params.get('query') ?? '').slice(0, 120)
    const [searchDraft, setSearchDraft] = useState(appliedSearch)
    useEffect(() => { setSearchDraft(appliedSearch) }, [appliedSearch])
    const [totalMatched, setTotalMatched] = useState(0)

    // Query is applied automatically after a pause. Deleting the text
    // must immediately clear the previous server-side filter.
    useEffect(() => {
        const query = searchDraft.trim()
        if (query === appliedSearch) return
        if (query.length < 2 || query.length > 120) return
        const timeout = window.setTimeout(() => {
            change({query, page: ''}, true)
        }, 350)
        return () => window.clearTimeout(timeout)
    }, [searchDraft, appliedSearch, change])

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
                            ...(organizationId ? {organizationId} : {}),
                        },
                        {signal: controller.signal},
                    ),
                    getUserStatistics({
                        ...(currentUserIsSuperAdmin ? {directory: true, organizationId: organizationId || undefined} : {}),
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
        organizationId,
        currentUserIsSuperAdmin,
        currentUser.id,
        currentUser.organizationId,
        setPage,
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
                setDisplayOrganizations([])
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
                setDisplayOrganizations(loaded)

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

    // The creation picker stays restricted to enabled client organizations.
    // Resolve missing display names separately, including PLATFORM and disabled tenants.
    useEffect(() => {
        if (!currentUserIsSuperAdmin || !users.length) return
        const controller = new AbortController()
        const known = new Set(displayOrganizations.map(row => row.id))
        const missing = [...new Set(users.map(row => row.organizationId))].filter(id => !known.has(id))
        if (!missing.length) return
        async function resolveMissing() {
            const resolved: OrganizationDirectoryItem[] = []
            for (let i = 0; i < missing.length && !controller.signal.aborted; i += 5) {
                const batch = await Promise.all(missing.slice(i, i + 5).map(id =>
                    searchOrganizationDirectory(id, 1, {signal: controller.signal})
                        .then(rows => rows.filter(row => row.id === id)).catch(() => [])))
                resolved.push(...batch.flat())
            }
            if (!controller.signal.aborted && resolved.length) {
                setDisplayOrganizations(previous => [...new Map([...previous, ...resolved].map(row => [row.id, row])).values()])
            }
        }
        void resolveMissing()
        return () => controller.abort()
    }, [currentUserIsSuperAdmin, users, displayOrganizations])

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
        change({role: nextFilter === 'ALL' ? '' : nextFilter, page: ''})
    }

    function changeStatus(next: 'ALL' | 'ACTIVE' | 'DISABLED') {
        change({status: next === 'ALL' ? '' : next, page: ''})
    }

    function changeSearchDraft(value: string) {
        setSearchDraft(value)
        // The native clear icon must reset the applied server query immediately.
        if (!value.trim()) {
            change({query: '', page: ''})
        }
    }

    function applySearch() {
        const next = searchDraft.trim()
        if (next.length > 120) {
            return
        }
        change({query: next, page: ''})
    }

    function clearSearch() {
        setSearchDraft('')
        change({query: '', page: ''})
    }

    function selectSearchSuggestion(user: User) {
        // Exact email is unambiguous even for users sharing full names.
        setSearchDraft(user.email)
        change({query: user.email, page: ''})
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
        displayOrganizations: currentUserIsSuperAdmin ? displayOrganizations : [],
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
        organizationId,
        changeOrganization: (id: string) => {
            if (currentUserIsSuperAdmin) change({organizationId: directoryUuid(id), page: ''})
        },
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
