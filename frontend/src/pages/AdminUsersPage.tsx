// frontend/src/pages/AdminUsersPage.tsx
import {
    useEffect,
    useRef,
    useState,
} from 'react'
import type {
    ReactNode,
    SyntheticEvent,
} from 'react'
import {
    createUser,
    getUserDetails,
    getUserStatistics,
    getUsers,
    permanentlyDeleteUser,
    resetUserPassword,
    updateUser,
    updateUserEnabled,
    updateUserRoles,
} from '../api/userApi'
import type {
    User,
    UserDetails,
    UserListRoleFilter,
    UserStatistics,
} from '../api/userApi'
import type { AuthUser } from '../api/authApi'
import type { UserRole } from '../api/types'
import {
    isProtectedOrganization,
    searchOrganizationDirectory,
} from '../api/organizationApi'
import type {
    OrganizationDirectoryItem,
} from '../api/organizationApi'
import {
    ApiError,
    getApiErrorMessage,
} from '../api/http'
import { formatDateTime } from '../utils/format'
import {
    normalizePageResponse,
} from '../utils/page'
import {
    validatePassword,
} from '../utils/password'
import {
    useAutoClearMessage,
} from '../hooks/useAutoClearMessage'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import UserActionsMenu from '../components/admin/UserActionsMenu'
import UserIdentityCell from '../components/admin/UserIdentityCell'
import UserRoleBadge from '../components/admin/UserRoleBadge'
import {
    FixedUserRole,
    UserRoleSelector,
} from '../components/admin/UserRoleSelector'
import {
    getUserManagementRolePolicy,
    resolveManagedUserRole,
} from '../domain/userManagementRolePolicy'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import PageErrorBoundary
    from '../components/PageErrorBoundary'
import './AdminUsersPage.css'

const PAGE_SIZE = 50
const SUCCESS_MESSAGE_TIMEOUT_MS = 4_000

type AssignableRole = Exclude<
    UserRole,
    'SUPER_ADMIN'
>

type UserFilter =
    | 'ALL'
    | UserListRoleFilter

type AdminUsersPageProps = {
    currentUser: AuthUser
}

type ConfirmState = {
    user: User
    nextEnabled: boolean
} | null

type PendingMutation = {
    userId: string
    type:
        | 'EDIT'
        | 'ROLES'
        | 'RESET_PASSWORD'
        | 'ENABLED'
        | 'DELETE'
} | null

const EMPTY_STATISTICS: UserStatistics = {
    total: 0,
    administrators: 0,
    users: 0,
    enabled: 0,
    disabled: 0,
}

function AdminUsersPage(
    props: AdminUsersPageProps,
) {
    return (
        <PageErrorBoundary>
            <AdminUsersPageContent
                {...props}
            />
        </PageErrorBoundary>
    )
}

function AdminUsersPageContent({
    currentUser,
}: AdminUsersPageProps) {
    const [users, setUsers] =
        useState<User[]>([])
    const [statistics, setStatistics] =
        useState<UserStatistics>(
            EMPTY_STATISTICS,
        )
    const [
        organizations,
        setOrganizations,
    ] = useState<
        OrganizationDirectoryItem[]
    >([])

    const [loadError, setLoadError] =
        useState('')
    const [createError, setCreateError] =
        useState('')
    const [
        mutationError,
        setMutationError,
    ] = useState('')
    const [modalError, setModalError] =
        useState('')
    const [success, setSuccess] =
        useState('')

    const [loading, setLoading] =
        useState(true)
    const [creating, setCreating] =
        useState(false)
    const [
        createModalOpen,
        setCreateModalOpen,
    ] = useState(false)
    const [
        organizationsLoading,
        setOrganizationsLoading,
    ] = useState(false)
    const [
        pendingMutation,
        setPendingMutation,
    ] = useState<PendingMutation>(null)

    const [page, setPage] =
        useState(0)
    const [totalPages, setTotalPages] =
        useState(0)
    const [reloadToken, setReloadToken] =
        useState(0)
    const [filter, setFilter] =
        useState<UserFilter>('ALL')

    const [email, setEmail] =
        useState('')
    const [password, setPassword] =
        useState('')
    const [
        passwordConfirm,
        setPasswordConfirm,
    ] = useState('')
    const [fullName, setFullName] =
        useState('')
    const [
        createRole,
        setCreateRole,
    ] = useState<AssignableRole>(
        'USER',
    )
    const [
        selectedOrganizationId,
        setSelectedOrganizationId,
    ] = useState('')

    const [
        detailsUser,
        setDetailsUser,
    ] = useState<UserDetails | null>(null)
    const [
        detailsLoadingUserId,
        setDetailsLoadingUserId,
    ] = useState<string | null>(null)
    const [
        detailsError,
        setDetailsError,
    ] = useState('')

    const [editUser, setEditUser] =
        useState<User | null>(null)
    const [editEmail, setEditEmail] =
        useState('')
    const [
        editFullName,
        setEditFullName,
    ] = useState('')

    const [rolesUser, setRolesUser] =
        useState<User | null>(null)
    const [
        selectedRole,
        setSelectedRole,
    ] = useState<AssignableRole>(
        'USER',
    )
    const [
        adminElevationConfirmed,
        setAdminElevationConfirmed,
    ] = useState(false)

    const [
        resetPasswordUser,
        setResetPasswordUser,
    ] = useState<User | null>(null)
    const [
        resetPasswordValue,
        setResetPasswordValue,
    ] = useState('')
    const [
        resetPasswordConfirm,
        setResetPasswordConfirm,
    ] = useState('')

    const [
        confirmState,
        setConfirmState,
    ] = useState<ConfirmState>(null)

    const [deleteUser, setDeleteUser] =
        useState<User | null>(null)
    const [
        deleteConfirmationEmail,
        setDeleteConfirmationEmail,
    ] = useState('')

    const usersSequenceRef = useRef(0)
    const usersControllerRef =
        useRef<AbortController | null>(null)
    const detailsSequenceRef = useRef(0)
    const detailsControllerRef =
        useRef<AbortController | null>(null)
    const organizationsControllerRef =
        useRef<AbortController | null>(null)

    const creatingRef = useRef(false)
    const pendingMutationRef =
        useRef<PendingMutation>(null)

    const rolePolicy =
        getUserManagementRolePolicy(
            currentUser.roles,
        )

    const currentUserIsSuperAdmin =
        rolePolicy.canChooseRole

    const hasPendingMutation =
        pendingMutation !== null

    useEffect(() => {
        pendingMutationRef.current =
            pendingMutation
    }, [pendingMutation])

    useEffect(() => {
        const sequence =
            ++usersSequenceRef.current

        usersControllerRef.current?.abort()

        const controller =
            new AbortController()

        usersControllerRef.current =
            controller

        async function loadUsers() {
            setLoading(true)
            setLoadError('')

            try {
                const [
                    response,
                    loadedStatistics,
                ] = await Promise.all([
                    getUsers(
                        page,
                        PAGE_SIZE,
                        filter === 'ALL'
                            ? undefined
                            : filter,
                        {
                            signal:
                                controller.signal,
                        },
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
    ])

    useEffect(() => {
        organizationsControllerRef.current
            ?.abort()

        if (!currentUserIsSuperAdmin) {
            setOrganizations([])
            setSelectedOrganizationId('')
            setOrganizationsLoading(false)
            return
        }

        const controller =
            new AbortController()

        organizationsControllerRef.current =
            controller

        async function loadOrganizations() {
            setOrganizationsLoading(true)
            setCreateError('')

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

                const safeOrganizations =
                    loaded.filter(
                        (organization) =>
                            organization.enabled
                            && !isProtectedOrganization(
                                {
                                    type:
                                        organization.type,
                                    protected:
                                        organization.protected,
                                },
                            ),
                    )

                setOrganizations(
                    safeOrganizations,
                )

                setSelectedOrganizationId(
                    (current) =>
                        safeOrganizations.some(
                            (organization) =>
                                organization.id
                                === current,
                        )
                            ? current
                            : '',
                )
            } catch (error) {
                if (!isRequestAborted(error)) {
                    setOrganizations([])
                    setCreateError(
                        getApiErrorMessage(
                            error,
                            'Не удалось загрузить каталог организаций.',
                        ),
                    )
                }
            } finally {
                setOrganizationsLoading(false)
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
            organizationsControllerRef.current
                ?.abort()
        }
    }, [])

    useAutoClearMessage(
        success,
        setSuccess,
        SUCCESS_MESSAGE_TIMEOUT_MS,
    )

    function requestReloadFromFirstPage() {
        if (page === 0) {
            setReloadToken(
                (value) => value + 1,
            )
        } else {
            setPage(0)
        }
    }

    function openCreateModal() {
        clearCreateSensitiveFields()
        setEmail('')
        setFullName('')
        setCreateRole('USER')
        setSelectedOrganizationId('')
        setCreateError('')
        setCreateModalOpen(true)
    }

    function closeCreateModal() {
        if (creatingRef.current) {
            return
        }

        clearCreateSensitiveFields()
        setCreateModalOpen(false)
        setCreateError('')
    }

    async function handleCreateUser(
        event: SyntheticEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        if (creatingRef.current) {
            return
        }

        setCreateError('')
        setSuccess('')

        const normalizedEmail =
            normalizeEmail(email)
        const passwordError =
            validatePassword(password)

        const targetOrganizationId =
            currentUserIsSuperAdmin
                ? selectedOrganizationId
                : currentUser.organizationId

        if (!normalizedEmail) {
            setCreateError(
                'Введите email пользователя.',
            )
            return
        }

        if (passwordError) {
            setCreateError(passwordError)
            return
        }

        if (password !== passwordConfirm) {
            setCreateError(
                'Пароли не совпадают.',
            )
            return
        }

        if (!targetOrganizationId) {
            setCreateError(
                'Выберите организацию.',
            )
            return
        }

        const requestedRole =
            resolveManagedUserRole(
                currentUser.roles,
                createRole,
            )

        creatingRef.current = true
        setCreating(true)

        try {
            const created = await createUser({
                organizationId:
                    targetOrganizationId,
                email: normalizedEmail,
                password,
                fullName:
                    normalizeOptionalText(
                        fullName,
                    ),
                roles: [requestedRole],
            })

            clearCreateSensitiveFields()
            setEmail('')
            setFullName('')
            setCreateRole('USER')
            setSelectedOrganizationId('')
            setCreateModalOpen(false)
            setSuccess(
                `Пользователь ${created.email} создан.`,
            )
            requestReloadFromFirstPage()
        } catch (error) {
            setCreateError(
                getApiErrorMessage(
                    error,
                    'Не удалось создать пользователя.',
                ),
            )
        } finally {
            creatingRef.current = false
            setCreating(false)
        }
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
            const details =
                await getUserDetails(
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

    function openEditModal(user: User) {
        if (!ensureVersionAvailable(user)) {
            return
        }

        closeMutationModals()
        setEditUser(user)
        setEditEmail(user.email)
        setEditFullName(
            user.fullName ?? '',
        )
        setModalError('')
    }

    function openRolesModal(user: User) {
        if (!currentUserIsSuperAdmin) {
            setMutationError(
                'Только SUPER_ADMIN может изменять системную роль пользователя.',
            )
            return
        }

        if (!ensureVersionAvailable(user)) {
            return
        }

        const role = getAssignableRole(user)

        if (!role) {
            setMutationError(
                'Роль пользователя недоступна для изменения через user-management.',
            )
            return
        }

        closeMutationModals()

        setRolesUser(user)
        setSelectedRole(role)
        setAdminElevationConfirmed(false)
        setModalError('')
    }

    function openResetPasswordModal(
        user: User,
    ) {
        if (!ensureVersionAvailable(user)) {
            return
        }

        closeMutationModals()

        setResetPasswordUser(user)
        clearMutationSensitiveFields()
        setModalError('')
    }

    function openDeleteModal(user: User) {
        if (!ensureVersionAvailable(user)) {
            return
        }

        closeMutationModals()

        setDeleteUser(user)
        setDeleteConfirmationEmail('')
        setModalError('')
    }

    function closeMutationModals() {
        if (pendingMutationRef.current) {
            return
        }

        clearMutationSensitiveFields()
        setEditUser(null)
        setRolesUser(null)
        setResetPasswordUser(null)
        setDeleteUser(null)
        setModalError('')
        setAdminElevationConfirmed(false)
    }

    async function submitEditUser(
        event: SyntheticEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        if (!editUser) {
            return
        }

        const expectedVersion =
            requireVersion(editUser)

        if (expectedVersion === null) {
            return
        }

        const normalizedEmail =
            normalizeEmail(editEmail)

        if (!normalizedEmail) {
            setModalError(
                'Введите email пользователя.',
            )
            return
        }

        await runUserMutation(
            {
                userId: editUser.id,
                type: 'EDIT',
            },
            'Не удалось обновить пользователя.',
            async () => {
                await updateUser(
                    editUser.id,
                    {
                        email:
                            normalizedEmail,
                        fullName:
                            normalizeOptionalText(
                                editFullName,
                            ),
                        expectedVersion,
                    },
                )

                setEditUser(null)
                setSuccess(
                    `Пользователь ${normalizedEmail} обновлён.`,
                )
                requestReloadFromFirstPage()
            },
        )
    }

    async function submitRoles(
        event: SyntheticEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        if (!rolesUser) {
            return
        }

        const expectedVersion =
            requireVersion(rolesUser)

        if (expectedVersion === null) {
            return
        }

        if (!currentUserIsSuperAdmin) {
            setModalError(
                'Только SUPER_ADMIN может изменять системную роль пользователя.',
            )
            return
        }

        const addingAdmin =
            selectedRole === 'ADMIN'
            && !rolesUser.roles.includes(
                'ADMIN',
            )

        if (
            addingAdmin
            && !adminElevationConfirmed
        ) {
            setModalError(
                'Подтвердите повышение привилегий до ADMIN.',
            )
            return
        }

        await runUserMutation(
            {
                userId: rolesUser.id,
                type: 'ROLES',
            },
            'Не удалось изменить роли пользователя.',
            async () => {
                await updateUserRoles(
                    rolesUser.id,
                    {
                        roles: [selectedRole],
                        expectedVersion,
                    },
                )

                setRolesUser(null)
                setAdminElevationConfirmed(false)
                setSuccess(
                    `Роль пользователя ${rolesUser.email} изменена. `
                    + 'Активные сессии должны быть завершены backend.',
                )
                requestReloadFromFirstPage()
            },
        )
    }

    async function submitResetPassword(
        event: SyntheticEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        if (!resetPasswordUser) {
            return
        }

        const expectedVersion =
            requireVersion(
                resetPasswordUser,
            )

        if (expectedVersion === null) {
            return
        }

        const passwordError =
            validatePassword(
                resetPasswordValue,
            )

        if (passwordError) {
            setModalError(passwordError)
            return
        }

        if (
            resetPasswordValue
            !== resetPasswordConfirm
        ) {
            setModalError(
                'Пароли не совпадают.',
            )
            return
        }

        await runUserMutation(
            {
                userId:
                    resetPasswordUser.id,
                type: 'RESET_PASSWORD',
            },
            'Не удалось установить новый пароль.',
            async () => {
                await resetUserPassword(
                    resetPasswordUser.id,
                    {
                        password:
                            resetPasswordValue,
                        expectedVersion,
                    },
                )

                const emailValue =
                    resetPasswordUser.email

                clearMutationSensitiveFields()
                setResetPasswordUser(null)
                setSuccess(
                    `Для ${emailValue} установлен новый пароль. `
                    + 'Активные сессии должны быть завершены backend.',
                )
                requestReloadFromFirstPage()
            },
        )
    }

    async function submitPermanentDelete(
        event: SyntheticEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        if (!deleteUser) {
            return
        }

        if (!canPermanentlyDelete(deleteUser)) {
            setModalError(
                'Удаление недоступно. '
                + 'Сначала отключите пользователя '
                + 'и убедитесь, что его организация не защищена.',
            )
            return
        }

        const expectedVersion =
            requireVersion(deleteUser)

        if (expectedVersion === null) {
            return
        }

        const canonicalConfirmation =
            normalizeEmail(
                deleteConfirmationEmail,
            )

        if (
            canonicalConfirmation
            !== normalizeEmail(
                deleteUser.email,
            )
        ) {
            setModalError(
                'Введите email пользователя полностью и без ошибок.',
            )
            return
        }

        await runUserMutation(
            {
                userId: deleteUser.id,
                type: 'DELETE',
            },
            'Не удалось удалить пользователя.',
            async () => {
                await permanentlyDeleteUser(
                    deleteUser.id,
                    {
                        confirmationEmail:
                            canonicalConfirmation,
                        expectedVersion,
                    },
                )

                const deletedEmail =
                    deleteUser.email

                clearMutationSensitiveFields()
                setDeleteUser(null)
                setSuccess(
                    `Пользователь ${deletedEmail} удалён навсегда.`,
                )
                requestReloadFromFirstPage()
            },
        )
    }

    async function confirmEnabledChange() {
        if (!confirmState) {
            return
        }

        const expectedVersion =
            requireVersion(
                confirmState.user,
            )

        if (expectedVersion === null) {
            return
        }

        const target =
            confirmState.user

        await runUserMutation(
            {
                userId: target.id,
                type: 'ENABLED',
            },
            'Не удалось изменить статус пользователя.',
            async () => {
                await updateUserEnabled(
                    target.id,
                    {
                        enabled:
                            confirmState.nextEnabled,
                        expectedVersion,
                    },
                )

                setConfirmState(null)
                setSuccess(
                    confirmState.nextEnabled
                        ? (
                            `Пользователь ${target.email} включён.`
                        )
                        : (
                            `Пользователь ${target.email} отключён. `
                            + 'Запущен отзыв активных сессий.'
                        ),
                )
                requestReloadFromFirstPage()
            },
            false,
        )
    }

    async function runUserMutation(
        mutation: Exclude<
            PendingMutation,
            null
        >,
        fallbackError: string,
        action: () => Promise<void>,
        modal = true,
    ) {
        if (pendingMutationRef.current) {
            return
        }

        pendingMutationRef.current =
            mutation
        setPendingMutation(mutation)

        if (modal) {
            setModalError('')
        } else {
            setMutationError('')
        }

        try {
            await action()
        } catch (error) {
            const message =
                getMutationErrorMessage(
                    error,
                    fallbackError,
                )

            if (modal) {
                setModalError(message)
            } else {
                setMutationError(message)
            }

            if (isVersionConflict(error)) {
                requestReloadFromFirstPage()
            }
        } finally {
            pendingMutationRef.current =
                null
            setPendingMutation(null)
        }
    }

    function canManageUser(
        user: User,
    ): boolean {
        const targetIsAdmin =
            user.roles.includes('ADMIN')

        return !user.roles.includes(
            'SUPER_ADMIN',
        )
            && user.id !== currentUser.id
            && (
                !targetIsAdmin
                || currentUserIsSuperAdmin
            )
    }

    function canPermanentlyDelete(
        user: User,
    ): boolean {
        if (
            !currentUserIsSuperAdmin
            || !canManageUser(user)
            || user.enabled
            || user.version === null
        ) {
            return false
        }

        const organization =
            organizations.find(
                (item) =>
                    item.id
                    === user.organizationId,
            )

        return Boolean(
            organization
            && !isProtectedOrganization({
                type:
                    organization.type,
                protected:
                    organization.protected,
            }),
        )
    }

    function ensureVersionAvailable(
        user: User,
    ): boolean {
        if (user.version !== null) {
            return true
        }

        setMutationError(
            'Backend не вернул version пользователя. '
            + 'Mutation заблокирована fail-closed, '
            + 'пока не реализован optimistic concurrency contract.',
        )
        return false
    }

    function requireVersion(
        user: User,
    ): number | null {
        if (user.version !== null) {
            return user.version
        }

        setModalError(
            'Backend не вернул version пользователя. '
            + 'Операция заблокирована.',
        )
        return null
    }

    function clearCreateSensitiveFields() {
        setPassword('')
        setPasswordConfirm('')
    }

    function clearMutationSensitiveFields() {
        setResetPasswordValue('')
        setResetPasswordConfirm('')
        setDeleteConfirmationEmail('')
    }

    return (
        <div className="page users-page">
            <div className="users-page-header">
                <div>
                    <h1>Пользователи</h1>
                    <p className="users-page-subtitle">
                        Управление пользователями системы
                    </p>
                </div>

                <button
                    type="button"
                    className="users-create-button"
                    onClick={openCreateModal}
                    disabled={
                        creating
                        || hasPendingMutation
                    }
                >
                    <span aria-hidden="true">
                        ＋
                    </span>
                    Создать пользователя
                </button>
            </div>

            {mutationError && (
                <div
                    className="error"
                    role="alert"
                    aria-live="assertive"
                >
                    {mutationError}
                </div>
            )}

            {success && (
                <div
                    className="success"
                    role="status"
                    aria-live="polite"
                >
                    {success}
                </div>
            )}

            <div
                className="users-filter-bar"
                aria-label={
                    'Фильтр пользователей по роли'
                }
            >
                <FilterButton
                    active={filter === 'ALL'}
                    label="Все"
                    count={statistics.total}
                    disabled={hasPendingMutation}
                    onClick={() => {
                        setFilter('ALL')
                        setPage(0)
                    }}
                />
                <FilterButton
                    active={filter === 'ADMIN'}
                    label="Администраторы"
                    count={
                        statistics.administrators
                    }
                    disabled={hasPendingMutation}
                    onClick={() => {
                        setFilter('ADMIN')
                        setPage(0)
                    }}
                />
                <FilterButton
                    active={filter === 'USER'}
                    label="Пользователи"
                    count={statistics.users}
                    disabled={hasPendingMutation}
                    onClick={() => {
                        setFilter('USER')
                        setPage(0)
                    }}
                />
            </div>

            {loading && (
                <LoadingState
                    message="Загрузка пользователей..."
                />
            )}

            {!loading && loadError && (
                <ErrorState
                    title="Ошибка загрузки пользователей"
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

            {!loading
                && !loadError
                && users.length === 0
                && (
                    <EmptyState
                        message={
                            'Пользователи не найдены.'
                        }
                    />
                )}

            {!loading
                && !loadError
                && users.length > 0
                && (
                    <div className="users-table-card">
                        <div className="users-table-scroll">
                            <table className="users-table">
                                <thead>
                                    <tr>
                                        <th>
                                            Пользователь
                                        </th>
                                        {currentUserIsSuperAdmin
                                            && (
                                                <th>
                                                    Организация
                                                </th>
                                            )}
                                        <th>Роли</th>
                                        <th>Статус</th>
                                        <th>
                                            Дата создания
                                        </th>
                                        <th>Действия</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {users.map(
                                        (user) => {
                                            const manageable =
                                                canManageUser(
                                                    user,
                                                )

                                            return (
                                                <tr
                                                    key={
                                                        user.id
                                                    }
                                                    className={
                                                        user.enabled
                                                            ? undefined
                                                            : 'users-table__row--disabled'
                                                    }
                                                >
                                                    <td>
                                                        <button
                                                            type="button"
                                                            className={
                                                                'user-identity-link'
                                                            }
                                                            disabled={
                                                                detailsLoadingUserId
                                                                    === user.id
                                                            }
                                                            onClick={() =>
                                                                void openDetailsModal(
                                                                    user,
                                                                )
                                                            }
                                                        >
                                                            <UserIdentityCell
                                                                fullName={
                                                                    user.fullName
                                                                }
                                                                email={
                                                                    user.email
                                                                }
                                                                roles={
                                                                    user.roles
                                                                }
                                                            />
                                                        </button>
                                                    </td>

                                                    {currentUserIsSuperAdmin
                                                        && (
                                                            <td>
                                                                {
                                                                    getOrganizationName(
                                                                        user.organizationId,
                                                                        organizations,
                                                                    )
                                                                }
                                                            </td>
                                                        )}

                                                    <td>
                                                        <div className="role-list">
                                                            {user.roles.map(
                                                                (role) => (
                                                                    <UserRoleBadge
                                                                        key={
                                                                            role
                                                                        }
                                                                        role={
                                                                            role
                                                                        }
                                                                    />
                                                                ),
                                                            )}
                                                        </div>
                                                    </td>

                                                    <td>
                                                        <UserStatusBadge
                                                            enabled={
                                                                user.enabled
                                                            }
                                                        />
                                                    </td>

                                                    <td>
                                                        {formatDateTime(
                                                            user.createdAt,
                                                        )}
                                                    </td>

                                                    <td className="actions-cell">
                                                        <UserActionsMenu
                                                            disabled={
                                                                hasPendingMutation
                                                            }
                                                            canManage={
                                                                manageable
                                                            }
                                                            canChangeRole={
                                                                currentUserIsSuperAdmin
                                                                && manageable
                                                            }
                                                            canDelete={
                                                                canPermanentlyDelete(
                                                                    user,
                                                                )
                                                            }
                                                            enabled={
                                                                user.enabled
                                                            }
                                                            onDetails={() =>
                                                                void openDetailsModal(
                                                                    user,
                                                                )
                                                            }
                                                            onEdit={() =>
                                                                openEditModal(
                                                                    user,
                                                                )
                                                            }
                                                            onRoles={() =>
                                                                openRolesModal(
                                                                    user,
                                                                )
                                                            }
                                                            onResetPassword={() =>
                                                                openResetPasswordModal(
                                                                    user,
                                                                )
                                                            }
                                                            onToggleEnabled={() =>
                                                                setConfirmState({
                                                                    user,
                                                                    nextEnabled:
                                                                        !user.enabled,
                                                                })
                                                            }
                                                            onDelete={() =>
                                                                openDeleteModal(
                                                                    user,
                                                                )
                                                            }
                                                        />

                                                        {!manageable && (
                                                            <span className="muted">
                                                                {
                                                                    getUnmanageableReason(
                                                                        user,
                                                                        currentUser,
                                                                    )
                                                                }
                                                            </span>
                                                        )}

                                                        {user.version
                                                            === null
                                                            && manageable
                                                            && (
                                                                <span className="muted">
                                                                    Backend version отсутствует
                                                                </span>
                                                            )}
                                                    </td>
                                                </tr>
                                            )
                                        },
                                    )}
                                </tbody>
                            </table>
                        </div>

                        <Pagination
                            page={page}
                            totalPages={
                                totalPages
                            }
                            disabled={
                                loading
                                || hasPendingMutation
                            }
                            onPrevious={() =>
                                setPage(
                                    (value) =>
                                        Math.max(
                                            0,
                                            value - 1,
                                        ),
                                )
                            }
                            onNext={() =>
                                setPage(
                                    (value) =>
                                        value + 1,
                                )
                            }
                        />
                    </div>
                )}

            {createModalOpen && (
                <Modal
                    title="Создать пользователя"
                    onClose={closeCreateModal}
                    closeDisabled={creating}
                    size="md"
                >
                    <form
                        className="form users-create-form"
                        onSubmit={
                            handleCreateUser
                        }
                    >
                        <div className="users-create-form__grid">
                            <label>
                                Email
                                <input
                                    value={email}
                                    onChange={(event) =>
                                        setEmail(
                                            event.target.value,
                                        )
                                    }
                                    type="email"
                                    autoComplete="username"
                                    maxLength={255}
                                    placeholder="user@company.ru"
                                    required
                                    disabled={creating}
                                />
                            </label>

                            <label>
                                Полное имя
                                <input
                                    value={fullName}
                                    onChange={(event) =>
                                        setFullName(
                                            event.target.value,
                                        )
                                    }
                                    maxLength={255}
                                    placeholder="Иван Иванов"
                                    disabled={creating}
                                />
                            </label>

                            <PasswordFields
                                password={password}
                                passwordConfirm={
                                    passwordConfirm
                                }
                                onPasswordChange={
                                    setPassword
                                }
                                onPasswordConfirmChange={
                                    setPasswordConfirm
                                }
                                disabled={creating}
                            />

                            {currentUserIsSuperAdmin && (
                                <label className="users-create-form__wide">
                                    Организация
                                    <select
                                        value={
                                            selectedOrganizationId
                                        }
                                        onChange={(event) =>
                                            setSelectedOrganizationId(
                                                event.target.value,
                                            )
                                        }
                                        disabled={
                                            creating
                                            || organizationsLoading
                                        }
                                        required
                                    >
                                        <option value="">
                                            {organizationsLoading
                                                ? 'Загрузка организаций...'
                                                : 'Выберите организацию'}
                                        </option>
                                        {organizations.map(
                                            (
                                                organization,
                                            ) => (
                                                <option
                                                    key={
                                                        organization.id
                                                    }
                                                    value={
                                                        organization.id
                                                    }
                                                >
                                                    {
                                                        organization.name
                                                    }
                                                </option>
                                            ),
                                        )}
                                    </select>
                                </label>
                            )}
                        </div>

                        {currentUserIsSuperAdmin
                            ? (
                                <>
                                    <UserRoleSelector
                                        name="create-user-role"
                                        value={createRole}
                                        disabled={creating}
                                        onChange={
                                            setCreateRole
                                        }
                                        legend="Роль пользователя"
                                    />

                                    {createRole === 'ADMIN' && (
                                        <div
                                            className="users-privilege-notice"
                                            role="note"
                                        >
                                            Администратор сможет управлять
                                            пользователями выбранной организации,
                                            но не сможет создавать других
                                            администраторов.
                                        </div>
                                    )}
                                </>
                            )
                            : (
                                <FixedUserRole
                                    role="USER"
                                    title="Роль пользователя"
                                    description={
                                        'Пользователь будет создан '
                                        + 'в вашей организации.'
                                    }
                                />
                            )}

                        {createError && (
                            <div
                                className="error"
                                role="alert"
                                aria-live="assertive"
                            >
                                {createError}
                            </div>
                        )}

                        <div className="modal-actions">
                            <button
                                type="button"
                                className={
                                    'secondary-button'
                                }
                                disabled={creating}
                                onClick={
                                    closeCreateModal
                                }
                            >
                                Отмена
                            </button>
                            <button
                                type="submit"
                                disabled={
                                    creating
                                    || organizationsLoading
                                }
                            >
                                {creating
                                    ? 'Создание...'
                                    : (
                                        'Создать '
                                        + 'пользователя'
                                    )}
                            </button>
                        </div>
                    </form>
                </Modal>
            )}

            {detailsUser && (
                <Modal
                    title="Подробнее о пользователе"
                    onClose={
                        closeDetailsModal
                    }
                    size="md"
                >
                    {detailsError && (
                        <div
                            className="error"
                            role="alert"
                        >
                            {detailsError}
                        </div>
                    )}

                    <dl className="user-details">
                        <Detail
                            term="Email"
                            value={
                                detailsUser.email
                            }
                        />
                        <Detail
                            term="Полное имя"
                            value={
                                detailsUser.fullName
                                ?? '—'
                            }
                        />
                        <Detail
                            term="Организация"
                            value={
                                <div className="user-details__organization">
                                    <strong className="user-details__organization-name">
                                        {
                                            detailsUser.organizationName
                                            ?? findOrganizationName(
                                                detailsUser.organizationId,
                                                organizations,
                                            )
                                            ?? 'Название недоступно'
                                        }
                                    </strong>

                                    <span className="user-details__organization-id">
                                        {detailsUser.organizationId}
                                    </span>
                                </div>
                            }
                        />
                        <Detail
                            term="Роли"
                            value={
                                detailsUser.roles
                                    .map(
                                        getRoleLabel,
                                    )
                                    .join(', ')
                            }
                        />
                        <Detail
                            term="Статус"
                            value={
                                <UserStatusBadge
                                    enabled={
                                        detailsUser.enabled
                                    }
                                />
                            }
                        />
                        <Detail
                            term="Версия"
                            value={
                                detailsUser.version
                                    ?.toString()
                                ?? 'не предоставлена'
                            }
                        />
                        <Detail
                            term="Дата создания"
                            value={
                                formatDateTime(
                                    detailsUser.createdAt,
                                )
                            }
                        />
                        <Detail
                            term="Последнее изменение"
                            value={
                                formatDateTime(
                                    detailsUser.updatedAt,
                                )
                            }
                        />
                        <Detail
                            term="Последний вход"
                            value={
                                detailsUser.lastLoginAt
                                    ? formatDateTime(
                                        detailsUser.lastLoginAt,
                                    )
                                    : 'Ещё не входил'
                            }
                        />
                    </dl>
                </Modal>
            )}

            {editUser && (
                <Modal
                    title="Редактирование пользователя"
                    size="sm"
                    onClose={
                        closeMutationModals
                    }
                    closeDisabled={
                        hasPendingMutation
                    }
                >
                    <form
                        className="form"
                        onSubmit={submitEditUser}
                    >
                        <label>
                            Email
                            <input
                                value={editEmail}
                                onChange={(event) =>
                                    setEditEmail(
                                        event.target.value,
                                    )
                                }
                                type="email"
                                maxLength={255}
                                required
                                disabled={
                                    hasPendingMutation
                                }
                            />
                        </label>

                        <label>
                            Полное имя
                            <input
                                value={editFullName}
                                onChange={(event) =>
                                    setEditFullName(
                                        event.target.value,
                                    )
                                }
                                maxLength={255}
                                disabled={
                                    hasPendingMutation
                                }
                            />
                        </label>

                        <ModalError
                            message={modalError}
                        />

                        <ModalActions
                            busy={
                                hasPendingMutation
                            }
                            onCancel={
                                closeMutationModals
                            }
                            submitLabel={
                                'Сохранить изменения'
                            }
                        />
                    </form>
                </Modal>
            )}

            {rolesUser && currentUserIsSuperAdmin && (
                <Modal
                    title="Роли и доступ"
                    size="sm"
                    onClose={
                        closeMutationModals
                    }
                    closeDisabled={
                        hasPendingMutation
                    }
                >
                    <form
                        className="form"
                        onSubmit={submitRoles}
                    >
                        <p className="modal-subtitle">
                            {rolesUser.email}
                        </p>

                        <UserRoleSelector
                            name="edit-user-role"
                            value={selectedRole}
                            disabled={
                                hasPendingMutation
                            }
                            onChange={(role) => {
                                setSelectedRole(role)
                                setAdminElevationConfirmed(
                                    false,
                                )
                            }}
                            legend="Системная роль"
                        />

                        {selectedRole === 'ADMIN'
                            && !rolesUser.roles.includes(
                                'ADMIN',
                            )
                            && (
                                <label className="danger-notice">
                                    <input
                                        type="checkbox"
                                        checked={
                                            adminElevationConfirmed
                                        }
                                        onChange={(
                                            event,
                                        ) =>
                                            setAdminElevationConfirmed(
                                                event.target.checked,
                                            )
                                        }
                                        disabled={
                                            hasPendingMutation
                                        }
                                    />
                                    Подтверждаю повышение
                                    привилегий до ADMIN.
                                    Пользователь получит
                                    административный доступ.
                                </label>
                            )}

                        <ModalError
                            message={modalError}
                        />

                        <ModalActions
                            busy={
                                hasPendingMutation
                            }
                            onCancel={
                                closeMutationModals
                            }
                            submitLabel={
                                'Сохранить изменения'
                            }
                        />
                    </form>
                </Modal>
            )}

            {resetPasswordUser && (
                <Modal
                    title="Установить новый пароль"
                    size="sm"
                    onClose={
                        closeMutationModals
                    }
                    closeDisabled={
                        hasPendingMutation
                    }
                >
                    <form
                        className="form"
                        onSubmit={
                            submitResetPassword
                        }
                    >
                        <PasswordFields
                            password={
                                resetPasswordValue
                            }
                            passwordConfirm={
                                resetPasswordConfirm
                            }
                            onPasswordChange={
                                setResetPasswordValue
                            }
                            onPasswordConfirmChange={
                                setResetPasswordConfirm
                            }
                            disabled={
                                hasPendingMutation
                            }
                            passwordLabel={
                                'Новый пароль'
                            }
                        />

                        <ModalError
                            message={modalError}
                        />

                        <ModalActions
                            busy={
                                hasPendingMutation
                            }
                            onCancel={
                                closeMutationModals
                            }
                            submitLabel={
                                'Установить новый пароль'
                            }
                        />
                    </form>
                </Modal>
            )}

            {deleteUser && (
                <Modal
                    title="Удалить пользователя навсегда?"
                    size="sm"
                    onClose={
                        closeMutationModals
                    }
                    closeDisabled={
                        hasPendingMutation
                    }
                >
                    <div className="danger-notice">
                        Пользователь должен быть
                        предварительно отключён.
                        Backend дополнительно проверяет
                        зависимости, retention policy,
                        last-admin invariant и защиту
                        платформенной организации.
                    </div>

                    <form
                        className="form"
                        onSubmit={
                            submitPermanentDelete
                        }
                    >
                        <label>
                            Введите email пользователя
                            <input
                                type="email"
                                value={
                                    deleteConfirmationEmail
                                }
                                onChange={(event) =>
                                    setDeleteConfirmationEmail(
                                        event.target.value,
                                    )
                                }
                                autoComplete="off"
                                required
                                disabled={
                                    hasPendingMutation
                                }
                            />
                        </label>

                        <ModalError
                            message={modalError}
                        />

                        <ModalActions
                            busy={
                                hasPendingMutation
                            }
                            onCancel={
                                closeMutationModals
                            }
                            submitLabel={
                                'Удалить навсегда'
                            }
                            danger
                            submitDisabled={
                                normalizeEmail(
                                    deleteConfirmationEmail,
                                )
                                !== normalizeEmail(
                                    deleteUser.email,
                                )
                            }
                        />
                    </form>
                </Modal>
            )}

            {confirmState && (
                <ConfirmDialog
                    title={
                        confirmState.nextEnabled
                            ? 'Включить пользователя'
                            : 'Отключить пользователя'
                    }
                    message={
                        confirmState.nextEnabled
                            ? (
                                'Включить пользователя '
                                + `${confirmState.user.email}?`
                            )
                            : (
                                'Отключить пользователя '
                                + `${confirmState.user.email}? `
                                + 'Будет запущен отзыв '
                                + 'активных сессий.'
                            )
                    }
                    confirmText={
                        confirmState.nextEnabled
                            ? 'Включить пользователя'
                            : 'Отключить пользователя'
                    }
                    danger={
                        !confirmState.nextEnabled
                    }
                    loading={
                        hasPendingMutation
                    }
                    onCancel={() => {
                        if (!hasPendingMutation) {
                            setConfirmState(null)
                        }
                    }}
                    onConfirm={
                        confirmEnabledChange
                    }
                />
            )}
        </div>
    )
}

type FilterButtonProps = {
    active: boolean
    label: string
    count: number
    disabled: boolean
    onClick: () => void
}

function FilterButton({
    active,
    label,
    count,
    disabled,
    onClick,
}: FilterButtonProps) {
    return (
        <button
            type="button"
            className={
                active
                    ? (
                        'users-filter-button '
                        + 'is-active'
                    )
                    : 'users-filter-button'
            }
            aria-pressed={active}
            disabled={disabled}
            onClick={onClick}
        >
            {label}
            {' '}
            <span className="users-filter-count">
                {count}
            </span>
        </button>
    )
}

type PaginationProps = {
    page: number
    totalPages: number
    disabled: boolean
    onPrevious: () => void
    onNext: () => void
}

function Pagination({
    page,
    totalPages,
    disabled,
    onPrevious,
    onNext,
}: PaginationProps) {
    return (
        <div className="pagination">
            <button
                type="button"
                className="secondary-button"
                disabled={
                    disabled || page === 0
                }
                onClick={onPrevious}
            >
                Назад
            </button>

            <span>
                Страница
                {' '}
                {page + 1}
                {' '}
                из
                {' '}
                {Math.max(totalPages, 1)}
            </span>

            <button
                type="button"
                className="secondary-button"
                disabled={
                    disabled
                    || page + 1 >= totalPages
                }
                onClick={onNext}
            >
                Вперёд
            </button>
        </div>
    )
}

type PasswordFieldsProps = {
    password: string
    passwordConfirm: string
    onPasswordChange:
        (value: string) => void
    onPasswordConfirmChange:
        (value: string) => void
    disabled: boolean
    passwordLabel?: string
}

function PasswordFields({
    password,
    passwordConfirm,
    onPasswordChange,
    onPasswordConfirmChange,
    disabled,
    passwordLabel = 'Пароль',
}: PasswordFieldsProps) {
    return (
        <>
            <label>
                {passwordLabel}
                <input
                    value={password}
                    onChange={(event) =>
                        onPasswordChange(
                            event.target.value,
                        )
                    }
                    type="password"
                    minLength={12}
                    autoComplete="new-password"
                    required
                    disabled={disabled}
                />
            </label>

            <label>
                Повторите пароль
                <input
                    value={passwordConfirm}
                    onChange={(event) =>
                        onPasswordConfirmChange(
                            event.target.value,
                        )
                    }
                    type="password"
                    minLength={12}
                    autoComplete="new-password"
                    required
                    disabled={disabled}
                />
            </label>

            <small className="muted">
                Максимум 72 байта UTF-8.
                Требуются ASCII: a-z, A-Z,
                цифра и спецсимвол.
            </small>
        </>
    )
}

type ModalActionsProps = {
    busy: boolean
    onCancel: () => void
    submitLabel: string
    danger?: boolean
    submitDisabled?: boolean
}

function ModalActions({
    busy,
    onCancel,
    submitLabel,
    danger = false,
    submitDisabled = false,
}: ModalActionsProps) {
    return (
        <div className="modal-actions">
            <button
                type="button"
                className="secondary-button"
                disabled={busy}
                onClick={onCancel}
            >
                Отмена
            </button>

            <button
                type="submit"
                className={
                    danger
                        ? 'danger-button'
                        : undefined
                }
                disabled={
                    busy || submitDisabled
                }
            >
                {busy
                    ? 'Выполнение...'
                    : submitLabel}
            </button>
        </div>
    )
}

function ModalError({
    message,
}: {
    message: string
}) {
    return message ? (
        <div
            className="error"
            role="alert"
            aria-live="assertive"
        >
            {message}
        </div>
    ) : null
}

function UserStatusBadge({
    enabled,
}: {
    enabled: boolean
}) {
    return (
        <span
            className={
                enabled
                    ? (
                        'status-chip '
                        + 'status-chip--enabled'
                    )
                    : (
                        'status-chip '
                        + 'status-chip--disabled'
                    )
            }
        >
            <span
                className="status-chip__dot"
                aria-hidden="true"
            />

            {enabled
                ? 'Включён'
                : 'Отключён'}
        </span>
    )
}

function Detail({
    term,
    value,
}: {
    term: string
    value: ReactNode
}) {
    return (
        <div className="user-details__row">
            <dt>{term}</dt>
            <dd>{value}</dd>
        </div>
    )
}

function getAssignableRole(
    user: User,
): AssignableRole | null {
    const role = user.roles[0]

    if (role === 'USER' || role === 'ADMIN') {
        return role
    }

    return null
}

function findOrganizationName(
    id: string,
    organizations:
        OrganizationDirectoryItem[],
): string | null {
    return organizations.find(
        (organization) =>
            organization.id === id,
    )?.name ?? null
}

function getOrganizationName(
    id: string,
    organizations:
        OrganizationDirectoryItem[],
): string {
    return findOrganizationName(
        id,
        organizations,
    ) ?? id
}

function getRoleLabel(
    role: UserRole,
): string {
    switch (role) {
        case 'SUPER_ADMIN':
            return 'Суперадминистратор'
        case 'ADMIN':
            return 'Администратор'
        case 'USER':
            return 'Пользователь'
    }
}

function getUnmanageableReason(
    user: User,
    currentUser: AuthUser,
): string {
    if (
        user.roles.includes('SUPER_ADMIN')
    ) {
        return 'Платформенный администратор'
    }

    if (user.id === currentUser.id) {
        return 'Текущий пользователь'
    }

    return 'Недоступно для ADMIN'
}

function normalizeEmail(
    value: string,
): string {
    return value
        .trim()
        .toLowerCase()
}

function normalizeOptionalText(
    value: string,
): string | null {
    const normalized = value
        .trim()
        .replace(/\s+/g, ' ')

    return normalized || null
}

function getMutationErrorMessage(
    error: unknown,
    fallback: string,
): string {
    if (isVersionConflict(error)) {
        return (
            'Пользователь был изменён другим администратором. '
            + 'Список обновлён; повторите решение по свежим данным.'
        )
    }

    return getApiErrorMessage(
        error,
        fallback,
    )
}

function isVersionConflict(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && (
            error.status === 409
            || error.status === 412
        )
        && (
            error.errorCode
                === 'USER_VERSION_CONFLICT'
            || error.errorCode
                === 'OPTIMISTIC_LOCK_CONFLICT'
        )
}

function isRequestAborted(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && error.errorCode
            === 'REQUEST_ABORTED'
}

export default AdminUsersPage
