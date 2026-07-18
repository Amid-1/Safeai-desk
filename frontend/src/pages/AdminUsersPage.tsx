// frontend/src/pages/AdminUsersPage.tsx
import { useEffect, useRef, useState } from 'react'
import type { SyntheticEvent } from 'react'
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
    UserStatistics,
    UserListRoleFilter,
} from '../api/userApi'
import type { AuthUser } from '../api/authApi'
import type { UserRole } from '../api/types'
import type { Organization } from '../api/organizationApi'
import { getApiErrorMessage } from '../api/http'
import { formatDateTime } from '../utils/format'
import { loadAllOrganizations } from '../utils/organizations'
import { normalizePageResponse } from '../utils/page'
import { useAutoClearMessage } from '../hooks/useAutoClearMessage'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import UserActionsMenu from '../components/admin/UserActionsMenu'
import UserIdentityCell from '../components/admin/UserIdentityCell'
import UserRoleBadge from '../components/admin/UserRoleBadge'
import './AdminUsersPage.css'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'

const PAGE_SIZE = 50
const SUCCESS_MESSAGE_TIMEOUT_MS = 4000
const PLATFORM_ORGANIZATION_ID = '00000000-0000-0000-0000-000000000001'

type AssignableRole = Exclude<UserRole, 'SUPER_ADMIN'>
type UserFilter = 'ALL' | UserListRoleFilter

type AdminUsersPageProps = {
    currentUser: AuthUser
}

type ConfirmState = {
    user: User
    nextEnabled: boolean
} | null

function AdminUsersPage({ currentUser }: AdminUsersPageProps) {
    const [users, setUsers] = useState<User[]>([])
    const [statistics, setStatistics] = useState<UserStatistics>({
        total: 0,
        administrators: 0,
        users: 0,
        enabled: 0,
        disabled: 0,
    })
    const [organizations, setOrganizations] = useState<Organization[]>([])
    const [loadError, setLoadError] = useState('')
    const [createError, setCreateError] = useState('')
    const [mutationError, setMutationError] = useState('')
    const [modalError, setModalError] = useState('')
    const [success, setSuccess] = useState('')
    const [loading, setLoading] = useState(true)
    const [creating, setCreating] = useState(false)
    const [createModalOpen, setCreateModalOpen] = useState(false)
    const [organizationsLoading, setOrganizationsLoading] = useState(false)
    const [actionUserId, setActionUserId] = useState<string | null>(null)
    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] = useState(0)
    const [reloadToken, setReloadToken] = useState(0)

    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [passwordConfirm, setPasswordConfirm] = useState('')
    const [fullName, setFullName] = useState('')
    const [createRoles, setCreateRoles] = useState<AssignableRole[]>(['USER'])
    const [selectedOrganizationId, setSelectedOrganizationId] = useState('')
    const [filter, setFilter] = useState<UserFilter>('ALL')

    const [detailsUser, setDetailsUser] = useState<User | null>(null)
    const [detailsLoadingUserId, setDetailsLoadingUserId] = useState<string | null>(null)
    const [detailsError, setDetailsError] = useState('')
    const [editUser, setEditUser] = useState<User | null>(null)
    const [editEmail, setEditEmail] = useState('')
    const [editFullName, setEditFullName] = useState('')
    const [rolesUser, setRolesUser] = useState<User | null>(null)
    const [selectedRoles, setSelectedRoles] = useState<AssignableRole[]>([])
    const [resetPasswordUser, setResetPasswordUser] = useState<User | null>(null)
    const [resetPasswordValue, setResetPasswordValue] = useState('')
    const [resetPasswordConfirm, setResetPasswordConfirm] = useState('')
    const [confirmState, setConfirmState] = useState<ConfirmState>(null)
    const [deleteUser, setDeleteUser] = useState<User | null>(null)
    const [deleteConfirmationEmail, setDeleteConfirmationEmail] = useState('')

    const usersSequenceRef = useRef(0)
    const organizationsSequenceRef = useRef(0)
    const currentUserIsSuperAdmin = currentUser.roles.includes('SUPER_ADMIN')

    useEffect(() => {
        const sequence = ++usersSequenceRef.current

        async function loadUsers() {
            setLoading(true)
            setLoadError('')

            try {
                const [response, loadedStatistics] = await Promise.all([
                    getUsers(
                        page,
                        PAGE_SIZE,
                        filter === 'ALL' ? undefined : filter,
                    ),
                    getUserStatistics(),
                ])

                if (sequence !== usersSequenceRef.current) {
                    return
                }

                const normalized = normalizePageResponse(response)
                setUsers(normalized.content)
                setTotalPages(normalized.totalPages)
                setStatistics(loadedStatistics)

                if (normalized.totalPages > 0 && page >= normalized.totalPages) {
                    setPage(normalized.totalPages - 1)
                }
            } catch (error) {
                if (sequence === usersSequenceRef.current) {
                    setUsers([])
                    setTotalPages(0)
                    setLoadError(
                        getApiErrorMessage(error, 'Не удалось загрузить пользователей.')
                    )
                }
            } finally {
                if (sequence === usersSequenceRef.current) {
                    setLoading(false)
                }
            }
        }

        void loadUsers()
        return () => {
            usersSequenceRef.current += 1
        }
    }, [page, reloadToken, filter])

    useEffect(() => {
        const sequence = ++organizationsSequenceRef.current

        async function loadOrganizations() {
            setOrganizationsLoading(true)

            try {
                const allOrganizations = await loadAllOrganizations()
                const loaded = currentUserIsSuperAdmin
                    ? allOrganizations.filter(
                        (organization) =>
                            organization.id !== PLATFORM_ORGANIZATION_ID
                    )
                    : allOrganizations

                if (sequence === organizationsSequenceRef.current) {
                    setOrganizations(loaded)

                    if (currentUserIsSuperAdmin) {
                        setSelectedOrganizationId((current) =>
                            loaded.some(
                                (organization) => organization.id === current
                            )
                                ? current
                                : ''
                        )
                    } else {
                        setSelectedOrganizationId('')
                    }
                }
            } catch (error) {
                if (sequence === organizationsSequenceRef.current) {
                    setOrganizations([])
                    setCreateError(
                        getApiErrorMessage(
                            error,
                            'Не удалось загрузить организации.'
                        )
                    )
                }
            } finally {
                if (sequence === organizationsSequenceRef.current) {
                    setOrganizationsLoading(false)
                }
            }
        }

        void loadOrganizations()

        return () => {
            organizationsSequenceRef.current += 1
        }
    }, [currentUserIsSuperAdmin])

    useAutoClearMessage(
        success,
        setSuccess,
        SUCCESS_MESSAGE_TIMEOUT_MS
    )

    function requestReloadFromFirstPage() {
        if (page === 0) {
            setReloadToken((value) => value + 1)
        } else {
            setPage(0)
        }
    }

    function toggleCreateRole(role: AssignableRole) {
        setCreateRoles((current) => toggleRole(current, role))
    }

    function toggleSelectedRole(role: AssignableRole) {
        setSelectedRoles((current) => toggleRole(current, role))
    }

    function openCreateModal() {
        setCreateError('')
        setCreateModalOpen(true)
    }

    function closeCreateModal() {
        if (creating) {
            return
        }

        setCreateModalOpen(false)
        setCreateError('')
    }

    async function handleCreateUser(
        event: SyntheticEvent<HTMLFormElement, SubmitEvent>
    ) {
        event.preventDefault()
        setCreateError('')
        setSuccess('')

        const normalizedEmail = email.trim()
        const passwordError = validatePassword(password)
        const targetOrganizationId = currentUserIsSuperAdmin
            ? selectedOrganizationId
            : currentUser.organizationId

        if (!normalizedEmail) {
            setCreateError('Введите email пользователя.')
            return
        }
        if (passwordError) {
            setCreateError(passwordError)
            return
        }
        if (password !== passwordConfirm) {
            setCreateError('Пароли не совпадают.')
            return
        }
        if (!targetOrganizationId) {
            setCreateError('Выберите организацию.')
            return
        }
        if (createRoles.length === 0) {
            setCreateError('Выберите хотя бы одну роль.')
            return
        }
        if (!currentUserIsSuperAdmin && createRoles.some((role) => role !== 'USER')) {
            setCreateError('ADMIN может назначать только роль USER.')
            return
        }

        setCreating(true)

        try {
            const created = await createUser({
                organizationId: targetOrganizationId,
                email: normalizedEmail,
                password,
                fullName: fullName.trim() || null,
                roles: createRoles,
            })

            setEmail('')
            setPassword('')
            setPasswordConfirm('')
            setFullName('')
            setCreateRoles(['USER'])
            setSelectedOrganizationId('')
            setCreateModalOpen(false)
            setSuccess(`Пользователь ${created.email} создан.`)
            requestReloadFromFirstPage()
        } catch (error) {
            setCreateError(getApiErrorMessage(error, 'Не удалось создать пользователя.'))
        } finally {
            setCreating(false)
        }
    }

    async function openDetailsModal(user: User): Promise<void> {
        setDetailsLoadingUserId(user.id)
        setDetailsError('')

        try {
            const details = await getUserDetails(user.id)
            setDetailsUser(details)
        } catch (error) {
            setDetailsError(
                getApiErrorMessage(
                    error,
                    'Не удалось загрузить сведения о пользователе.'
                )
            )
            setDetailsUser(user)
        } finally {
            setDetailsLoadingUserId(null)
        }
    }

    function closeDetailsModal() {
        setDetailsUser(null)
        setDetailsError('')
    }

    function openEditModal(user: User) {
        setEditUser(user)
        setEditEmail(user.email)
        setEditFullName(user.fullName ?? '')
        setModalError('')
    }

    function openRolesModal(user: User) {
        setRolesUser(user)
        setSelectedRoles(
            user.roles.filter((role): role is AssignableRole => role !== 'SUPER_ADMIN')
        )
        setModalError('')
    }

    function openResetPasswordModal(user: User) {
        setResetPasswordUser(user)
        setResetPasswordValue('')
        setResetPasswordConfirm('')
        setModalError('')
    }

    function openDeleteModal(user: User) {
        setDeleteUser(user)
        setDeleteConfirmationEmail('')
        setModalError('')
    }

    function closeMutationModals() {
        if (actionUserId) {
            return
        }
        setEditUser(null)
        setRolesUser(null)
        setResetPasswordUser(null)
        setDeleteUser(null)
        setModalError('')
    }

    async function submitEditUser(
        event: SyntheticEvent<HTMLFormElement, SubmitEvent>
    ) {
        event.preventDefault()

        if (!editUser) {
            return
        }

        const normalizedEmail = editEmail.trim()

        if (!normalizedEmail) {
            setModalError('Введите email пользователя.')
            return
        }

        await runUserModalMutation(
            editUser.id,
            'Не удалось обновить пользователя.',
            async () => {
                const updated = await updateUser(editUser.id, {
                    email: normalizedEmail,
                    fullName: editFullName.trim() || null,
                })

                replaceUser(updated)
                setSuccess(`Пользователь ${updated.email} обновлён.`)
                setEditUser(null)
            }
        )
    }

    async function submitRoles(
        event: SyntheticEvent<HTMLFormElement, SubmitEvent>
    ) {
        event.preventDefault()

        if (!rolesUser) {
            return
        }

        if (selectedRoles.length === 0) {
            setModalError('У пользователя должна быть хотя бы одна роль.')
            return
        }

        await runUserModalMutation(
            rolesUser.id,
            'Не удалось изменить роли пользователя.',
            async () => {
                const updated = await updateUserRoles(rolesUser.id, {
                    roles: selectedRoles,
                })

                replaceUser(updated)
                setSuccess(`Роли пользователя ${updated.email} изменены.`)
                setRolesUser(null)
            }
        )
    }

    async function submitResetPassword(
        event: SyntheticEvent<HTMLFormElement, SubmitEvent>
    ) {
        event.preventDefault()

        if (!resetPasswordUser) {
            return
        }

        const passwordError = validatePassword(resetPasswordValue)

        if (passwordError) {
            setModalError(passwordError)
            return
        }

        if (resetPasswordValue !== resetPasswordConfirm) {
            setModalError('Пароли не совпадают.')
            return
        }

        await runUserModalMutation(
            resetPasswordUser.id,
            'Не удалось установить новый пароль.',
            async () => {
                await resetUserPassword(resetPasswordUser.id, {
                    password: resetPasswordValue,
                })

                setSuccess(`Для ${resetPasswordUser.email} установлен новый пароль. Активные сессии завершены.`)
                setResetPasswordUser(null)
            }
        )
    }

    async function submitPermanentDelete(
        event: SyntheticEvent<HTMLFormElement, SubmitEvent>
    ) {
        event.preventDefault()

        if (!deleteUser) {
            return
        }

        if (
            deleteConfirmationEmail.trim().toLowerCase()
            !== deleteUser.email.toLowerCase()
        ) {
            setModalError(
                'Введите email пользователя полностью и без ошибок.'
            )
            return
        }

        await runUserModalMutation(
            deleteUser.id,
            'Не удалось удалить пользователя.',
            async () => {
                await permanentlyDeleteUser(deleteUser.id, {
                    confirmationEmail: deleteConfirmationEmail.trim(),
                })

                setSuccess(
                    `Пользователь ${deleteUser.email} удалён навсегда.`
                )
                setDeleteUser(null)
                requestReloadFromFirstPage()
            }
        )
    }

    async function confirmEnabledChange() {
        if (!confirmState) return

        setMutationError('')
        setActionUserId(confirmState.user.id)

        try {
            const updated = await updateUserEnabled(confirmState.user.id, {
                enabled: confirmState.nextEnabled,
            })
            replaceUser(updated)
            setSuccess(
                updated.enabled
                    ? `Пользователь ${updated.email} включён.`
                    : `Пользователь ${updated.email} отключён.`
            )
            setConfirmState(null)
        } catch (error) {
            setMutationError(
                getApiErrorMessage(error, 'Не удалось изменить статус пользователя.')
            )
        } finally {
            setActionUserId(null)
        }
    }

    async function runUserModalMutation(
        userId: string,
        fallbackError: string,
        action: () => Promise<void>
    ): Promise<void> {
        setActionUserId(userId)
        setModalError('')

        try {
            await action()
        } catch (error) {
            setModalError(getApiErrorMessage(error, fallbackError))
        } finally {
            setActionUserId(null)
        }
    }

    function replaceUser(updated: User) {
        setUsers((current) => current.map((user) => user.id === updated.id ? updated : user))
        setDetailsUser((current) => current?.id === updated.id ? updated : current)
    }

    function canManageUser(user: User): boolean {
        const targetIsAdmin = user.roles.includes('ADMIN')
        return !user.roles.includes('SUPER_ADMIN')
            && user.id !== currentUser.id
            && (!targetIsAdmin || currentUserIsSuperAdmin)
    }

    function canPermanentlyDelete(user: User): boolean {
        return currentUserIsSuperAdmin
            && canManageUser(user)
            && user.organizationId !== PLATFORM_ORGANIZATION_ID
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
                >
                    <span aria-hidden="true">＋</span>
                    Создать пользователя
                </button>
            </div>

            {mutationError && <div className="error">{mutationError}</div>}
            {success && <div className="success">{success}</div>}

            <div
                className="users-filter-bar"
                aria-label="Фильтр пользователей по роли"
            >
                <button
                    type="button"
                    className={filter === 'ALL' ? 'users-filter-button is-active' : 'users-filter-button'}
                    aria-pressed={filter === 'ALL'}
                    onClick={() => {
                        setFilter('ALL')
                        setPage(0)
                    }}
                >
                    Все <span className="users-filter-count">{statistics.total}</span>
                </button>
                <button
                    type="button"
                    className={filter === 'ADMIN' ? 'users-filter-button is-active' : 'users-filter-button'}
                    aria-pressed={filter === 'ADMIN'}
                    onClick={() => {
                        setFilter('ADMIN')
                        setPage(0)
                    }}
                >
                    Администраторы <span className="users-filter-count">{statistics.administrators}</span>
                </button>
                <button
                    type="button"
                    className={filter === 'USER' ? 'users-filter-button is-active' : 'users-filter-button'}
                    aria-pressed={filter === 'USER'}
                    onClick={() => {
                        setFilter('USER')
                        setPage(0)
                    }}
                >
                    Пользователи <span className="users-filter-count">{statistics.users}</span>
                </button>
            </div>

            {loading && <LoadingState message="Загрузка пользователей..." />}
            {!loading && loadError && (
                <ErrorState
                    title="Ошибка загрузки пользователей"
                    message={loadError}
                    action={
                        <button type="button" onClick={() => setReloadToken((value) => value + 1)}>
                            Повторить
                        </button>
                    }
                />
            )}
            {!loading && !loadError && users.length === 0 && (
                <EmptyState message="Пользователи не найдены." />
            )}

            {!loading && !loadError && users.length > 0 && (
                <div className="users-table-card">
                    <div className="users-table-scroll">
                        <table className="users-table">
                            <thead>
                            <tr>
                                <th className="users-table__user-column">Пользователь</th>
                                {currentUserIsSuperAdmin && (
                                    <th className="users-table__organization-column">Организация</th>
                                )}
                                <th className="users-table__roles-column">Роли</th>
                                <th className="users-table__status-column">Статус</th>
                                <th className="users-table__date-column">Дата создания</th>
                                <th className="users-table__actions-column">Действия</th>
                            </tr>
                            </thead>
                            <tbody>
                            {users.map((user) => {
                                const manageable = canManageUser(user)
                                const isBusy = actionUserId === user.id

                                return (
                                    <tr key={user.id}>
                                        <td>
                                            <button
                                                type="button"
                                                className="user-identity-link"
                                                disabled={detailsLoadingUserId === user.id}
                                                onClick={() => void openDetailsModal(user)}
                                            >
                                                <UserIdentityCell
                                                    fullName={user.fullName}
                                                    email={user.email}
                                                    roles={user.roles}
                                                />
                                            </button>
                                        </td>
                                        {currentUserIsSuperAdmin && (
                                            <td>
                                                {getOrganizationName(
                                                    user.organizationId,
                                                    organizations
                                                )}
                                            </td>
                                        )}
                                        <td>
                                            <div className="role-list">
                                                {user.roles.map((role) => (
                                                    <UserRoleBadge
                                                        key={role}
                                                        role={role}
                                                    />
                                                ))}
                                            </div>
                                        </td>
                                        <td>
                                            <span
                                                className={
                                                    user.enabled
                                                        ? 'status-chip status-chip--enabled'
                                                        : 'status-chip status-chip--disabled'
                                                }
                                            >
                                                <span className="status-chip__dot" aria-hidden="true" />
                                                {user.enabled ? 'Включён' : 'Отключён'}
                                            </span>
                                        </td>
                                        <td>{formatDateTime(user.createdAt)}</td>
                                        <td className="actions-cell">
                                            <UserActionsMenu
                                                disabled={isBusy}
                                                canManage={manageable}
                                                canDelete={canPermanentlyDelete(user)}
                                                enabled={user.enabled}
                                                onDetails={() => void openDetailsModal(user)}
                                                onEdit={() => openEditModal(user)}
                                                onRoles={() => openRolesModal(user)}
                                                onResetPassword={() =>
                                                    openResetPasswordModal(user)
                                                }
                                                onToggleEnabled={() =>
                                                    setConfirmState({
                                                        user,
                                                        nextEnabled: !user.enabled,
                                                    })
                                                }
                                                onDelete={() => openDeleteModal(user)}
                                            />

                                            {!manageable && (
                                                <span className="muted">
                                                    {user.roles.includes('SUPER_ADMIN')
                                                        ? 'Платформенный администратор'
                                                        : user.id === currentUser.id
                                                            ? 'Текущий пользователь'
                                                            : 'Недоступно для ADMIN'}
                                                </span>
                                            )}
                                        </td>
                                    </tr>
                                )
                            })}
                            </tbody>
                        </table>
                    </div>

                    <div className="pagination">
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={page === 0 || loading}
                            onClick={() =>
                                setPage((value) => Math.max(0, value - 1))
                            }
                        >
                            Назад
                        </button>
                        <span>
                            Страница {page + 1} из {Math.max(totalPages, 1)}
                        </span>
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={page + 1 >= totalPages || loading}
                            onClick={() => setPage((value) => value + 1)}
                        >
                            Вперёд
                        </button>
                    </div>
                </div>
            )}

            {createModalOpen && (
                <Modal
                    title="Создать пользователя"
                    onClose={closeCreateModal}
                    closeDisabled={creating}
                    size="lg"
                >
                    <form className="form users-create-form" onSubmit={handleCreateUser}>
                        <div className="users-create-form__grid">
                            <label>
                                Email
                                <input
                                    value={email}
                                    onChange={(event) => setEmail(event.target.value)}
                                    type="email"
                                    autoComplete="username"
                                    maxLength={255}
                                    required
                                    disabled={creating}
                                    placeholder="user@company.ru"
                                />
                            </label>

                            <label>
                                Полное имя
                                <input
                                    value={fullName}
                                    onChange={(event) => setFullName(event.target.value)}
                                    maxLength={255}
                                    disabled={creating}
                                    placeholder="Иван Иванов"
                                />
                            </label>

                            <PasswordFields
                                password={password}
                                passwordConfirm={passwordConfirm}
                                onPasswordChange={setPassword}
                                onPasswordConfirmChange={setPasswordConfirm}
                                disabled={creating}
                            />

                            {currentUserIsSuperAdmin && (
                                <label className="users-create-form__wide">
                                    Организация
                                    <select
                                        value={selectedOrganizationId}
                                        onChange={(event) =>
                                            setSelectedOrganizationId(event.target.value)
                                        }
                                        disabled={creating || organizationsLoading}
                                        required
                                    >
                                        <option value="">Выберите организацию</option>
                                        {organizations.map((organization) => (
                                            <option
                                                key={organization.id}
                                                value={organization.id}
                                            >
                                                {organization.name}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                            )}
                        </div>

                        <fieldset className="users-role-selector" disabled={creating}>
                            <legend>Роли</legend>

                            <label
                                className={
                                    createRoles.includes('USER')
                                        ? 'users-role-option users-role-option--user is-selected'
                                        : 'users-role-option users-role-option--user'
                                }
                            >
                                <input
                                    type="checkbox"
                                    checked={createRoles.includes('USER')}
                                    onChange={() => toggleCreateRole('USER')}
                                />
                                <span className="users-role-option__marker" aria-hidden="true" />
                                <span>
                                    <strong>Пользователь</strong>
                                    <small>Доступ к чатам и рабочим функциям</small>
                                </span>
                            </label>

                            {currentUserIsSuperAdmin && (
                                <label
                                    className={
                                        createRoles.includes('ADMIN')
                                            ? 'users-role-option users-role-option--admin is-selected'
                                            : 'users-role-option users-role-option--admin'
                                    }
                                >
                                    <input
                                        type="checkbox"
                                        checked={createRoles.includes('ADMIN')}
                                        onChange={() => toggleCreateRole('ADMIN')}
                                    />
                                    <span className="users-role-option__marker" aria-hidden="true" />
                                    <span>
                                        <strong>Администратор</strong>
                                        <small>Управление пользователями организации</small>
                                    </span>
                                </label>
                            )}
                        </fieldset>

                        {createError && <div className="error">{createError}</div>}

                        <div className="modal-actions">
                            <button
                                type="button"
                                className="button button--secondary"
                                disabled={creating}
                                onClick={closeCreateModal}
                            >
                                Отмена
                            </button>
                            <button
                                type="submit"
                                className="button button--primary"
                                disabled={creating || organizationsLoading}
                            >
                                {creating ? 'Создание...' : 'Создать пользователя'}
                            </button>
                        </div>
                    </form>
                </Modal>
            )}

            {detailsUser && (
                <Modal
                    title="Подробнее о пользователе"
                    onClose={closeDetailsModal}
                    size="md"
                >
                    <p className="modal-subtitle">{detailsUser.email}</p>
                    {detailsError && <div className="error">{detailsError}</div>}
                    <dl className="user-details">
                        <div className="user-details__row"><dt>Email</dt><dd>{detailsUser.email}</dd></div>
                        <div className="user-details__row"><dt>Полное имя</dt><dd>{detailsUser.fullName ?? '—'}</dd></div>
                        <div className="user-details__row">
                            <dt>Организация</dt>
                            <dd>
                                {getOrganizationName(
                                    detailsUser.organizationId,
                                    organizations
                                )}
                            </dd>
                        </div>
                        <div className="user-details__row">
                            <dt>Роли</dt>
                            <dd>{detailsUser.roles.map(getRoleLabel).join(', ')}</dd>
                        </div>
                        <div className="user-details__row">
                            <dt>Статус</dt>
                            <dd>{detailsUser.enabled ? 'Включён' : 'Отключён'}</dd>
                        </div>
                        <div className="user-details__row">
                            <dt>Дата создания</dt>
                            <dd>{formatDateTime(detailsUser.createdAt)}</dd>
                        </div>
                        <div className="user-details__row">
                            <dt>Дата последнего изменения</dt>
                            <dd>{formatDateTime(detailsUser.updatedAt)}</dd>
                        </div>
                        <div className="user-details__row">
                            <dt>Последний вход</dt>
                            <dd>
                                {detailsUser.lastLoginAt
                                    ? formatDateTime(detailsUser.lastLoginAt)
                                    : 'Ещё не входил'}
                            </dd>
                        </div>
                        <div className="user-details__row">
                            <dt>ID пользователя</dt>
                            <dd className="user-details-monospace">
                                {detailsUser.id}
                            </dd>
                        </div>
                    </dl>
                    <div className="modal-actions">
                        <button
                            type="button"
                            className="secondary-button"
                            onClick={closeDetailsModal}
                        >
                            Закрыть
                        </button>
                    </div>
                </Modal>
            )}

            {editUser && (
                <Modal size="sm" title="Редактирование пользователя" onClose={closeMutationModals} closeDisabled={actionUserId === editUser.id}>
                    <p className="modal-subtitle">{editUser.email}</p>
                    <form className="form" onSubmit={submitEditUser}>
                        <label>Email<input value={editEmail} onChange={(event) => setEditEmail(event.target.value)} type="email" maxLength={255} required disabled={actionUserId === editUser.id} /></label>
                        <label>Полное имя<input value={editFullName} onChange={(event) => setEditFullName(event.target.value)} maxLength={255} disabled={actionUserId === editUser.id} /></label>
                        {modalError && <div className="error">{modalError}</div>}
                        <div className="modal-actions">
                            <button type="button" className="secondary-button" disabled={actionUserId === editUser.id} onClick={closeMutationModals}>Отмена</button>
                            <button disabled={actionUserId === editUser.id || !editEmail.trim()}>{actionUserId === editUser.id ? 'Сохранение...' : 'Сохранить изменения'}</button>
                        </div>
                    </form>
                </Modal>
            )}

            {rolesUser && (
                <Modal size="sm" title="Роли и доступ" onClose={closeMutationModals} closeDisabled={actionUserId === rolesUser.id}>
                    <p className="modal-subtitle">{rolesUser.email}</p>
                    <form className="form" onSubmit={submitRoles}>
                        <fieldset disabled={actionUserId === rolesUser.id}>
                            <legend>Назначенные роли</legend>
                            <label><input type="checkbox" checked={selectedRoles.includes('USER')} onChange={() => toggleSelectedRole('USER')} /> USER</label>
                            <label><input type="checkbox" checked={selectedRoles.includes('ADMIN')} onChange={() => toggleSelectedRole('ADMIN')} /> ADMIN</label>
                        </fieldset>
                        {modalError && <div className="error">{modalError}</div>}
                        <div className="modal-actions">
                            <button type="button" className="secondary-button" disabled={actionUserId === rolesUser.id} onClick={closeMutationModals}>Отмена</button>
                            <button disabled={actionUserId === rolesUser.id || selectedRoles.length === 0}>{actionUserId === rolesUser.id ? 'Сохранение...' : 'Сохранить изменения'}</button>
                        </div>
                    </form>
                </Modal>
            )}

            {resetPasswordUser && (
                <Modal size="sm" title="Установить новый пароль" onClose={closeMutationModals} closeDisabled={actionUserId === resetPasswordUser.id}>
                    <p className="modal-subtitle">Пользователь: {resetPasswordUser.email}</p>
                    <p className="modal-help">После сохранения все активные сессии пользователя будут завершены.</p>
                    <form className="form" onSubmit={submitResetPassword}>
                        <PasswordFields
                            password={resetPasswordValue}
                            passwordConfirm={resetPasswordConfirm}
                            onPasswordChange={setResetPasswordValue}
                            onPasswordConfirmChange={setResetPasswordConfirm}
                            disabled={actionUserId === resetPasswordUser.id}
                            passwordLabel="Новый пароль"
                        />
                        {modalError && <div className="error">{modalError}</div>}
                        <div className="modal-actions">
                            <button type="button" className="secondary-button" disabled={actionUserId === resetPasswordUser.id} onClick={closeMutationModals}>Отмена</button>
                            <button disabled={actionUserId === resetPasswordUser.id || !resetPasswordValue || !resetPasswordConfirm}>{actionUserId === resetPasswordUser.id ? 'Сохранение...' : 'Установить новый пароль'}</button>
                        </div>
                    </form>
                </Modal>
            )}

            {deleteUser && (
                <Modal
                    title="Удалить пользователя навсегда?"
                    size="sm"
                    onClose={closeMutationModals}
                    closeDisabled={actionUserId === deleteUser.id}
                >
                    <p>
                        Пользователь: <strong>{deleteUser.email}</strong>
                    </p>
                    <div className="danger-notice">
                        Это действие нельзя отменить. История аудита будет
                        сохранена, но учётную запись нельзя будет восстановить.
                        Пользователя с историей чатов удалить нельзя — его можно
                        только отключить.
                    </div>
                    <form className="form" onSubmit={submitPermanentDelete}>
                        <label>
                            Для подтверждения введите email пользователя
                            <input
                                type="email"
                                value={deleteConfirmationEmail}
                                onChange={(event) =>
                                    setDeleteConfirmationEmail(event.target.value)
                                }
                                autoComplete="off"
                                required
                                disabled={actionUserId === deleteUser.id}
                            />
                        </label>
                        {modalError && <div className="error">{modalError}</div>}
                        <div className="modal-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={actionUserId === deleteUser.id}
                                onClick={closeMutationModals}
                            >
                                Отмена
                            </button>
                            <button
                                className="danger-button"
                                disabled={
                                    actionUserId === deleteUser.id
                                    || deleteConfirmationEmail.trim().toLowerCase()
                                    !== deleteUser.email.toLowerCase()
                                }
                            >
                                {actionUserId === deleteUser.id
                                    ? 'Удаление...'
                                    : 'Удалить навсегда'}
                            </button>
                        </div>
                    </form>
                </Modal>
            )}

            {confirmState && (
                <ConfirmDialog
                    title={confirmState.nextEnabled ? 'Включить пользователя' : 'Отключить пользователя'}
                    message={confirmState.nextEnabled
                        ? `Включить пользователя ${confirmState.user.email}?`
                        : `Отключить пользователя ${confirmState.user.email}? Все активные сессии будут завершены.`}
                    confirmText={confirmState.nextEnabled
                        ? 'Включить пользователя'
                        : 'Отключить пользователя'}
                    danger={!confirmState.nextEnabled}
                    loading={actionUserId === confirmState.user.id}
                    onCancel={() => setConfirmState(null)}
                    onConfirm={() => void confirmEnabledChange()}
                />
            )}
        </div>
    )
}

type PasswordFieldsProps = {
    password: string
    passwordConfirm: string
    onPasswordChange: (value: string) => void
    onPasswordConfirmChange: (value: string) => void
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
                    onChange={(event) => onPasswordChange(event.target.value)}
                    type="password"
                    minLength={12}
                    maxLength={72}
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
                        onPasswordConfirmChange(event.target.value)
                    }
                    type="password"
                    minLength={12}
                    maxLength={72}
                    autoComplete="new-password"
                    required
                    disabled={disabled}
                />
            </label>
        </>
    )
}

function toggleRole(current: AssignableRole[], role: AssignableRole): AssignableRole[] {
    return current.includes(role)
        ? current.filter((value) => value !== role)
        : [...current, role]
}

function getOrganizationName(id: string, organizations: Organization[]): string {
    if (id === PLATFORM_ORGANIZATION_ID) return 'SafeAI Platform'
    return organizations.find((organization) => organization.id === id)?.name ?? id
}

function getRoleLabel(role: UserRole): string {
    if (role === 'SUPER_ADMIN') return 'Суперадминистратор'
    if (role === 'ADMIN') return 'Администратор'
    return 'Пользователь'
}

function validatePassword(password: string): string | null {
    const missing: string[] = []
    if (!password) return 'Введите пароль.'
    if (password.length < 12) missing.push('минимум 12 символов')
    if (password.length > 72) missing.push('не более 72 символов')
    if (!/[a-z]/.test(password)) missing.push('строчную букву')
    if (!/[A-Z]/.test(password)) missing.push('заглавную букву')
    if (!/\d/.test(password)) missing.push('цифру')
    if (!/[^A-Za-z0-9]/.test(password)) missing.push('спецсимвол')
    return missing.length ? `Пароль должен содержать: ${missing.join(', ')}.` : null
}

export default AdminUsersPage

