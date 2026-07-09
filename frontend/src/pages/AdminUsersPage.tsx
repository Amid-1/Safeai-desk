// frontend/src/pages/AdminUsersPage.tsx
import { useEffect, useMemo, useState } from 'react'
import type { SyntheticEvent } from 'react'
import {
    createUser,
    getUsers,
    resetUserPassword,
    updateUser,
    updateUserEnabled,
    updateUserRoles,
} from '../api/userApi'
import type { User } from '../api/userApi'
import type { AuthUser } from '../api/authApi'
import { getApiErrorMessage } from '../api/http'
import { formatDateTime } from '../utils/format'
import { getPageContent, getPageTotalPages } from '../utils/page'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import { EmptyState, LoadingState } from '../components/StateBlock'
import { getOrganizations } from '../api/organizationApi'
import type { Organization } from '../api/organizationApi'

type Role = 'USER' | 'ADMIN'
type UserFilter = 'ALL' | 'ADMIN' | 'USER'

type AdminUsersPageProps = {
    currentUser: AuthUser
}

type ConfirmState =
    | {
    type: 'enabled'
    user: User
}
    | {
    type: 'role'
    user: User
    nextRole: Role
}
    | null

const PAGE_SIZE = 50
const SUCCESS_MESSAGE_TIMEOUT_MS = 4000
const PLATFORM_ORGANIZATION_ID = '00000000-0000-0000-0000-000000000001'

function AdminUsersPage({ currentUser }: AdminUsersPageProps) {
    const [users, setUsers] = useState<User[]>([])
    const [error, setError] = useState('')
    const [success, setSuccess] = useState('')
    const [loading, setLoading] = useState(true)
    const [creating, setCreating] = useState(false)
    const [organizationsLoading, setOrganizationsLoading] = useState(false)
    const [actionUserId, setActionUserId] = useState<string | null>(null)

    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] = useState(1)

    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [passwordConfirm, setPasswordConfirm] = useState('')
    const [fullName, setFullName] = useState('')
    const [role, setRole] = useState<Role>('USER')
    const [filter, setFilter] = useState<UserFilter>('ALL')

    const [resetPasswordUser, setResetPasswordUser] = useState<User | null>(null)
    const [resetPasswordValue, setResetPasswordValue] = useState('')
    const [resetPasswordConfirm, setResetPasswordConfirm] = useState('')
    const [confirmState, setConfirmState] = useState<ConfirmState>(null)
    const [detailsUser, setDetailsUser] = useState<User | null>(null)

    const [editUser, setEditUser] = useState<User | null>(null)
    const [editEmail, setEditEmail] = useState('')
    const [editFullName, setEditFullName] = useState('')

    const [organizations, setOrganizations] = useState<Organization[]>([])
    const [selectedOrganizationId, setSelectedOrganizationId] = useState('')

    const currentUserIsSuperAdmin = currentUser.roles.includes('SUPER_ADMIN')
    const canAssignAdmin = currentUserIsSuperAdmin

    useEffect(() => {
        void loadUsers(page)
    }, [page])

    useEffect(() => {
        if (!success) {
            return
        }

        const timeoutId = window.setTimeout(() => {
            setSuccess('')
        }, SUCCESS_MESSAGE_TIMEOUT_MS)

        return () => {
            window.clearTimeout(timeoutId)
        }
    }, [success])

    useEffect(() => {
        if (!canAssignAdmin && role === 'ADMIN') {
            setRole('USER')
        }
    }, [canAssignAdmin, role])

    useEffect(() => {
        if (!currentUserIsSuperAdmin) {
            setOrganizations([])
            setSelectedOrganizationId('')
            return
        }

        async function loadOrganizations() {
            setOrganizationsLoading(true)

            try {
                const data = await getOrganizations(0, 100)
                const content = getPageContent(data)

                const customerOrganizations = content.filter(
                    (organization) => organization.id !== PLATFORM_ORGANIZATION_ID
                )

                setOrganizations(customerOrganizations)

                setSelectedOrganizationId((currentSelectedOrganizationId) => {
                    if (
                        currentSelectedOrganizationId
                        && customerOrganizations.some(
                            (organization) => organization.id === currentSelectedOrganizationId
                        )
                    ) {
                        return currentSelectedOrganizationId
                    }

                    return ''
                })
            } catch (err) {
                setError(getApiErrorMessage(err, 'Не удалось загрузить организации.'))
            } finally {
                setOrganizationsLoading(false)
            }
        }

        void loadOrganizations()
    }, [currentUserIsSuperAdmin])

    const filteredUsers = useMemo(() => {
        if (filter === 'ALL') {
            return users
        }

        return users.filter((user) => user.roles.includes(filter))
    }, [users, filter])

    const adminCount = users.filter((user) => user.roles.includes('ADMIN')).length
    const userCount = users.filter((user) => user.roles.includes('USER')).length

    const createDisabled =
        creating
        || organizationsLoading
        || (currentUserIsSuperAdmin && !selectedOrganizationId)

    async function loadUsers(nextPage = page) {
        setError('')
        setLoading(true)

        try {
            const data = await getUsers(nextPage, PAGE_SIZE)

            setUsers(getPageContent(data))
            setTotalPages(getPageTotalPages(data))
        } catch (err) {
            setError(getApiErrorMessage(err, 'Не удалось загрузить пользователей.'))
        } finally {
            setLoading(false)
        }
    }

    async function handleCreateUser(event: SyntheticEvent<HTMLFormElement>) {
        event.preventDefault()

        setError('')
        setSuccess('')

        const normalizedEmail = email.trim()
        const passwordValidationError = validatePassword(password)

        if (!normalizedEmail) {
            setError('Введите email пользователя.')
            return
        }

        if (passwordValidationError) {
            setError(passwordValidationError)
            return
        }

        if (password !== passwordConfirm) {
            setError('Пароли не совпадают.')
            return
        }

        const requestedRole: Role = canAssignAdmin ? role : 'USER'

        const targetOrganizationId = currentUserIsSuperAdmin
            ? selectedOrganizationId
            : currentUser.organizationId

        if (!targetOrganizationId) {
            setError('Выберите организацию для пользователя.')
            return
        }

        setCreating(true)

        try {
            const createdUser = await createUser({
                organizationId: targetOrganizationId,
                email: normalizedEmail,
                password,
                fullName: fullName.trim() || null,
                roles: [requestedRole],
            })

            setEmail('')
            setPassword('')
            setPasswordConfirm('')
            setFullName('')
            setRole('USER')
            setSuccess(`Пользователь ${createdUser.email} создан.`)

            setPage(0)
            await loadUsers(0)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Не удалось создать пользователя.'))
        } finally {
            setCreating(false)
        }
    }

    function openEditUserModal(user: User) {
        setEditUser(user)
        setEditEmail(user.email)
        setEditFullName(user.fullName ?? '')
        setError('')
        setSuccess('')
    }

    function closeEditUserModal() {
        setEditUser(null)
        setEditEmail('')
        setEditFullName('')
    }

    async function handleSubmitEditUser(event: SyntheticEvent<HTMLFormElement>) {
        event.preventDefault()

        if (!editUser) {
            return
        }

        const normalizedEmail = editEmail.trim()

        if (!normalizedEmail) {
            setError('Введите email пользователя.')
            return
        }

        setActionUserId(editUser.id)
        setError('')
        setSuccess('')

        try {
            const updatedUser = await updateUser(editUser.id, {
                email: normalizedEmail,
                fullName: editFullName.trim() || null,
            })

            replaceUser(updatedUser)
            setSuccess(`Пользователь ${updatedUser.email} обновлен.`)
            closeEditUserModal()
        } catch (err) {
            setError(getApiErrorMessage(err, 'Не удалось обновить пользователя.'))
        } finally {
            setActionUserId(null)
        }
    }

    async function confirmAction() {
        if (!confirmState) {
            return
        }

        setError('')
        setSuccess('')

        if (confirmState.type === 'enabled') {
            await submitToggleEnabled(confirmState.user)
            return
        }

        await submitChangeRole(confirmState.user, confirmState.nextRole)
    }

    async function submitToggleEnabled(user: User) {
        const nextEnabled = !user.enabled

        setActionUserId(user.id)

        try {
            const updatedUser = await updateUserEnabled(user.id, {
                enabled: nextEnabled,
            })

            replaceUser(updatedUser)

            setSuccess(
                nextEnabled
                    ? `Пользователь ${updatedUser.email} включен.`
                    : `Пользователь ${updatedUser.email} отключен.`
            )

            setConfirmState(null)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Не удалось изменить статус пользователя.'))
        } finally {
            setActionUserId(null)
        }
    }

    async function submitChangeRole(user: User, nextRole: Role) {
        if (nextRole === 'ADMIN' && !canAssignAdmin) {
            setError('ADMIN может назначать только роль USER.')
            setConfirmState(null)
            return
        }

        setActionUserId(user.id)

        try {
            const updatedUser = await updateUserRoles(user.id, {
                roles: [nextRole],
            })

            replaceUser(updatedUser)

            setSuccess(`Роль пользователя ${updatedUser.email} изменена на ${nextRole}.`)
            setConfirmState(null)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Не удалось изменить роль пользователя.'))
        } finally {
            setActionUserId(null)
        }
    }

    async function handleSubmitResetPassword(event: SyntheticEvent<HTMLFormElement>) {
        event.preventDefault()

        if (!resetPasswordUser) {
            return
        }

        setError('')
        setSuccess('')

        const passwordValidationError = validatePassword(resetPasswordValue)

        if (passwordValidationError) {
            setError(passwordValidationError)
            return
        }

        if (resetPasswordValue !== resetPasswordConfirm) {
            setError('Пароли не совпадают.')
            return
        }

        setActionUserId(resetPasswordUser.id)

        try {
            await resetUserPassword(resetPasswordUser.id, {
                password: resetPasswordValue,
            })

            setSuccess(`Пароль для ${resetPasswordUser.email} изменен.`)
            closeResetPasswordModal()
        } catch (err) {
            setError(getApiErrorMessage(err, 'Не удалось изменить пароль.'))
        } finally {
            setActionUserId(null)
        }
    }

    function openResetPasswordModal(user: User) {
        setResetPasswordUser(user)
        setResetPasswordValue('')
        setResetPasswordConfirm('')
        setError('')
        setSuccess('')
    }

    function closeResetPasswordModal() {
        setResetPasswordUser(null)
        setResetPasswordValue('')
        setResetPasswordConfirm('')
    }

    function replaceUser(updatedUser: User) {
        setUsers((prev) =>
            prev.map((user) => (user.id === updatedUser.id ? updatedUser : user))
        )

        setDetailsUser((current) =>
            current?.id === updatedUser.id ? updatedUser : current
        )

        setEditUser((current) =>
            current?.id === updatedUser.id ? updatedUser : current
        )

        setResetPasswordUser((current) =>
            current?.id === updatedUser.id ? updatedUser : current
        )
    }

    function getRoleBadgeClass(userRole: string): string {
        if (userRole === 'SUPER_ADMIN') {
            return 'role-badge role-super-admin'
        }

        if (userRole === 'ADMIN') {
            return 'role-badge role-admin'
        }

        return 'role-badge role-user'
    }

    function getOrganizationName(organizationId: string): string {
        if (organizationId === PLATFORM_ORGANIZATION_ID) {
            return 'SafeAI Platform'
        }

        return organizations.find((organization) => organization.id === organizationId)?.name
            ?? organizationId
    }

    function canManageUser(user: User): boolean {
        const targetIsAdmin = user.roles.includes('ADMIN')
        const targetIsSuperAdmin = user.roles.includes('SUPER_ADMIN')
        const isCurrentUser = user.id === currentUser.id

        return !targetIsSuperAdmin
            && !isCurrentUser
            && (!targetIsAdmin || currentUserIsSuperAdmin)
    }

    function renderUserActions(user: User) {
        const targetIsAdmin = user.roles.includes('ADMIN')
        const targetIsSuperAdmin = user.roles.includes('SUPER_ADMIN')
        const isCurrentUser = user.id === currentUser.id
        const isBusy = actionUserId === user.id
        const manageable = canManageUser(user)

        return (
            <div className="table-actions table-actions-compact">
                <button
                    type="button"
                    className="secondary-button"
                    title="Открыть детали пользователя"
                    onClick={() => setDetailsUser(user)}
                >
                    Детали
                </button>

                {manageable && (
                    <button
                        type="button"
                        className="secondary-button"
                        title="Изменить email и полное имя"
                        disabled={isBusy}
                        onClick={() => openEditUserModal(user)}
                    >
                        Изменить
                    </button>
                )}

                {targetIsSuperAdmin && (
                    <span className="muted">Платформенный администратор</span>
                )}

                {!targetIsSuperAdmin && isCurrentUser && (
                    <span className="muted">Текущий пользователь</span>
                )}

                {!targetIsSuperAdmin
                    && !isCurrentUser
                    && targetIsAdmin
                    && !currentUserIsSuperAdmin
                    && (
                        <span className="muted">Администратор</span>
                    )}

                {manageable && (
                    <>
                        <button
                            type="button"
                            className={user.enabled ? 'danger-button' : 'secondary-button'}
                            title={user.enabled ? 'Отключить пользователя' : 'Включить пользователя'}
                            disabled={isBusy}
                            onClick={() =>
                                setConfirmState({
                                    type: 'enabled',
                                    user,
                                })
                            }
                        >
                            {user.enabled ? 'Откл.' : 'Вкл.'}
                        </button>

                        <button
                            type="button"
                            className="secondary-button"
                            title="Сбросить пароль пользователя"
                            disabled={isBusy}
                            onClick={() => openResetPasswordModal(user)}
                        >
                            Пароль
                        </button>

                        {targetIsAdmin && currentUserIsSuperAdmin && (
                            <button
                                type="button"
                                className="secondary-button"
                                title="Оставить только роль USER"
                                disabled={isBusy}
                                onClick={() =>
                                    setConfirmState({
                                        type: 'role',
                                        user,
                                        nextRole: 'USER',
                                    })
                                }
                            >
                                В USER
                            </button>
                        )}

                        {!targetIsAdmin && currentUserIsSuperAdmin && (
                            <button
                                type="button"
                                className="secondary-button"
                                title="Назначить роль ADMIN"
                                disabled={isBusy}
                                onClick={() =>
                                    setConfirmState({
                                        type: 'role',
                                        user,
                                        nextRole: 'ADMIN',
                                    })
                                }
                            >
                                В ADMIN
                            </button>
                        )}
                    </>
                )}
            </div>
        )
    }

    return (
        <div className="page">
            <h1>Пользователи</h1>

            {error && <div className="error">{error}</div>}
            {success && <div className="success">{success}</div>}

            <div className="card form-card">
                <h2>Создать пользователя</h2>

                <form className="form" onSubmit={handleCreateUser}>
                    <label>
                        Email
                        <input
                            value={email}
                            onChange={(event) => setEmail(event.target.value)}
                            placeholder="user@example.com"
                            type="email"
                            autoComplete="username"
                        />
                    </label>

                    <label>
                        Пароль
                        <input
                            value={password}
                            onChange={(event) => setPassword(event.target.value)}
                            placeholder="Минимум 12 символов: A-z, цифра, спецсимвол"
                            type="password"
                            minLength={12}
                            maxLength={72}
                            autoComplete="new-password"
                        />
                    </label>

                    <label>
                        Повторите пароль
                        <input
                            value={passwordConfirm}
                            onChange={(event) => setPasswordConfirm(event.target.value)}
                            placeholder="Повторите пароль"
                            type="password"
                            minLength={12}
                            maxLength={72}
                            autoComplete="new-password"
                        />
                    </label>

                    <label>
                        Полное имя
                        <input
                            value={fullName}
                            onChange={(event) => setFullName(event.target.value)}
                            placeholder="Иван Иванов"
                        />
                    </label>

                    {currentUserIsSuperAdmin && (
                        <label>
                            Организация
                            <select
                                value={selectedOrganizationId}
                                onChange={(event) => setSelectedOrganizationId(event.target.value)}
                                disabled={organizationsLoading || organizations.length === 0}
                            >
                                <option value="">
                                    {organizationsLoading
                                        ? 'Загрузка организаций...'
                                        : 'Выберите организацию'}
                                </option>

                                {organizations.map((organization) => (
                                    <option key={organization.id} value={organization.id}>
                                        {organization.name}
                                    </option>
                                ))}
                            </select>
                        </label>
                    )}

                    {currentUserIsSuperAdmin
                        && !organizationsLoading
                        && organizations.length === 0
                        && (
                            <p className="muted">
                                Нет доступных клиентских организаций. Сначала создайте организацию.
                            </p>
                        )}

                    <label>
                        Роль
                        <select
                            value={role}
                            onChange={(event) => setRole(event.target.value as Role)}
                        >
                            <option value="USER">USER</option>
                            {canAssignAdmin && <option value="ADMIN">ADMIN</option>}
                        </select>
                    </label>

                    <button disabled={createDisabled}>
                        {creating ? 'Создание...' : 'Создать пользователя'}
                    </button>
                </form>
            </div>

            <div className="user-toolbar">
                <button
                    type="button"
                    className={filter === 'ALL' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setFilter('ALL')}
                >
                    Все ({users.length})
                </button>

                <button
                    type="button"
                    className={filter === 'ADMIN' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setFilter('ADMIN')}
                >
                    Администраторы ({adminCount})
                </button>

                <button
                    type="button"
                    className={filter === 'USER' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setFilter('USER')}
                >
                    Пользователи ({userCount})
                </button>
            </div>

            {loading && <LoadingState message="Загрузка пользователей..." />}

            {!loading && !error && filteredUsers.length === 0 && (
                <EmptyState message="Пользователи не найдены на этой странице." />
            )}

            {!loading && filteredUsers.length > 0 && (
                <div className="card table-card">
                    <table>
                        <thead>
                        <tr>
                            <th>EMAIL</th>
                            <th>ПОЛНОЕ ИМЯ</th>
                            {currentUserIsSuperAdmin && <th>ОРГАНИЗАЦИЯ</th>}
                            <th>РОЛИ</th>
                            <th>СТАТУС</th>
                            <th>СОЗДАН</th>
                            <th>ДЕЙСТВИЯ</th>
                        </tr>
                        </thead>

                        <tbody>
                        {filteredUsers.map((user) => (
                            <tr key={user.id}>
                                <td>{user.email}</td>
                                <td>{user.fullName ?? '-'}</td>

                                {currentUserIsSuperAdmin && (
                                    <td>{getOrganizationName(user.organizationId)}</td>
                                )}

                                <td>
                                    <div className="role-list">
                                        {user.roles.map((userRole) => (
                                            <span
                                                key={userRole}
                                                className={getRoleBadgeClass(userRole)}
                                            >
                                                {userRole}
                                            </span>
                                        ))}
                                    </div>
                                </td>

                                <td>
                                    <span
                                        className={
                                            user.enabled
                                                ? 'status-badge status-enabled'
                                                : 'status-badge status-disabled'
                                        }
                                    >
                                        {user.enabled ? 'включен' : 'отключен'}
                                    </span>
                                </td>

                                <td>{formatDateTime(user.createdAt)}</td>

                                <td className="actions-cell">{renderUserActions(user)}</td>
                            </tr>
                        ))}
                        </tbody>
                    </table>

                    <div className="pagination">
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={page === 0 || loading}
                            onClick={() => setPage((prev) => Math.max(0, prev - 1))}
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
                            onClick={() => setPage((prev) => prev + 1)}
                        >
                            Вперед
                        </button>
                    </div>
                </div>
            )}

            {detailsUser && (
                <Modal
                    title={`Детали пользователя: ${detailsUser.email}`}
                    onClose={() => setDetailsUser(null)}
                >
                    <div className="form">
                        <p><strong>ID:</strong> {detailsUser.id}</p>
                        <p><strong>Email:</strong> {detailsUser.email}</p>
                        <p><strong>Полное имя:</strong> {detailsUser.fullName ?? '-'}</p>
                        <p><strong>Организация:</strong> {getOrganizationName(detailsUser.organizationId)}</p>
                        <p><strong>Статус:</strong> {detailsUser.enabled ? 'включен' : 'отключен'}</p>
                        <p><strong>Роли:</strong> {detailsUser.roles.join(', ')}</p>
                        <p><strong>Создан:</strong> {formatDateTime(detailsUser.createdAt)}</p>

                        <div className="modal-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                onClick={() => setDetailsUser(null)}
                            >
                                Закрыть
                            </button>
                        </div>
                    </div>
                </Modal>
            )}

            {editUser && (
                <Modal
                    title={`Изменить пользователя: ${editUser.email}`}
                    onClose={closeEditUserModal}
                >
                    <form className="form" onSubmit={handleSubmitEditUser}>
                        <label>
                            Email
                            <input
                                value={editEmail}
                                onChange={(event) => setEditEmail(event.target.value)}
                                type="email"
                                maxLength={255}
                                autoComplete="username"
                                autoFocus
                            />
                        </label>

                        <label>
                            Полное имя
                            <input
                                value={editFullName}
                                onChange={(event) => setEditFullName(event.target.value)}
                                maxLength={255}
                                placeholder="Иван Иванов"
                            />
                        </label>

                        <div className="modal-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={actionUserId === editUser.id}
                                onClick={closeEditUserModal}
                            >
                                Отмена
                            </button>

                            <button
                                disabled={
                                    actionUserId === editUser.id
                                    || !editEmail.trim()
                                }
                            >
                                {actionUserId === editUser.id ? 'Сохранение...' : 'Сохранить'}
                            </button>
                        </div>
                    </form>
                </Modal>
            )}

            {resetPasswordUser && (
                <Modal
                    title={`Сброс пароля: ${resetPasswordUser.email}`}
                    onClose={closeResetPasswordModal}
                >
                    <form className="form" onSubmit={handleSubmitResetPassword}>
                        <label>
                            Новый пароль
                            <input
                                type="password"
                                value={resetPasswordValue}
                                onChange={(event) => setResetPasswordValue(event.target.value)}
                                minLength={12}
                                maxLength={72}
                                autoComplete="new-password"
                                autoFocus
                            />
                        </label>

                        <label>
                            Повторите пароль
                            <input
                                type="password"
                                value={resetPasswordConfirm}
                                onChange={(event) => setResetPasswordConfirm(event.target.value)}
                                minLength={12}
                                maxLength={72}
                                autoComplete="new-password"
                            />
                        </label>

                        <div className="modal-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={actionUserId === resetPasswordUser.id}
                                onClick={closeResetPasswordModal}
                            >
                                Отмена
                            </button>

                            <button
                                disabled={
                                    actionUserId === resetPasswordUser.id
                                    || !resetPasswordValue
                                    || !resetPasswordConfirm
                                }
                            >
                                {actionUserId === resetPasswordUser.id
                                    ? 'Сброс...'
                                    : 'Сбросить пароль'}
                            </button>
                        </div>
                    </form>
                </Modal>
            )}

            {confirmState && (
                <ConfirmDialog
                    title={
                        confirmState.type === 'enabled'
                            ? confirmState.user.enabled
                                ? 'Отключить пользователя'
                                : 'Включить пользователя'
                            : 'Изменить роль пользователя'
                    }
                    message={
                        confirmState.type === 'enabled'
                            ? confirmState.user.enabled
                                ? `Отключить пользователя ${confirmState.user.email}?`
                                : `Включить пользователя ${confirmState.user.email}?`
                            : `Изменить роль пользователя ${confirmState.user.email} на ${confirmState.nextRole}?`
                    }
                    confirmText={
                        confirmState.type === 'enabled'
                            ? confirmState.user.enabled
                                ? 'Отключить'
                                : 'Включить'
                            : 'Изменить роль'
                    }
                    danger={confirmState.type === 'enabled' && confirmState.user.enabled}
                    loading={actionUserId === confirmState.user.id}
                    onCancel={() => setConfirmState(null)}
                    onConfirm={() => void confirmAction()}
                />
            )}
        </div>
    )
}

function validatePassword(password: string): string | null {
    const missingRequirements: string[] = []

    if (!password) {
        return 'Введите пароль.'
    }

    if (password.length < 12) {
        missingRequirements.push('минимум 12 символов')
    }

    if (password.length > 72) {
        missingRequirements.push('не более 72 символов')
    }

    if (!/[a-z]/.test(password)) {
        missingRequirements.push('строчную букву')
    }

    if (!/[A-Z]/.test(password)) {
        missingRequirements.push('заглавную букву')
    }

    if (!/\d/.test(password)) {
        missingRequirements.push('цифру')
    }

    if (!/[^A-Za-z0-9]/.test(password)) {
        missingRequirements.push('спецсимвол')
    }

    if (missingRequirements.length === 0) {
        return null
    }

    return `Пароль должен содержать: ${missingRequirements.join(', ')}.`
}

export default AdminUsersPage