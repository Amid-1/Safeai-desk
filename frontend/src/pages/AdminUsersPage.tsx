// frontend/src/pages/AdminUsersPage.tsx
import { useEffect, useMemo, useRef, useState } from 'react'
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
import type { UserRole } from '../api/types'
import type { Organization } from '../api/organizationApi'
import { getApiErrorMessage } from '../api/http'
import { formatDateTime } from '../utils/format'
import { loadAllOrganizations } from '../utils/organizations'
import { normalizePageResponse } from '../utils/page'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'

const PAGE_SIZE = 50
const SUCCESS_MESSAGE_TIMEOUT_MS = 4000
const PLATFORM_ORGANIZATION_ID = '00000000-0000-0000-0000-000000000001'

type AssignableRole = Exclude<UserRole, 'SUPER_ADMIN'>
type UserFilter = 'ALL' | AssignableRole

type AdminUsersPageProps = {
    currentUser: AuthUser
}

type ConfirmState = {
    user: User
    nextEnabled: boolean
} | null

function AdminUsersPage({ currentUser }: AdminUsersPageProps) {
    const [users, setUsers] = useState<User[]>([])
    const [organizations, setOrganizations] = useState<Organization[]>([])
    const [loadError, setLoadError] = useState('')
    const [createError, setCreateError] = useState('')
    const [mutationError, setMutationError] = useState('')
    const [modalError, setModalError] = useState('')
    const [success, setSuccess] = useState('')
    const [loading, setLoading] = useState(true)
    const [creating, setCreating] = useState(false)
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
    const [editUser, setEditUser] = useState<User | null>(null)
    const [editEmail, setEditEmail] = useState('')
    const [editFullName, setEditFullName] = useState('')
    const [rolesUser, setRolesUser] = useState<User | null>(null)
    const [selectedRoles, setSelectedRoles] = useState<AssignableRole[]>([])
    const [resetPasswordUser, setResetPasswordUser] = useState<User | null>(null)
    const [resetPasswordValue, setResetPasswordValue] = useState('')
    const [resetPasswordConfirm, setResetPasswordConfirm] = useState('')
    const [confirmState, setConfirmState] = useState<ConfirmState>(null)

    const usersSequenceRef = useRef(0)
    const organizationsSequenceRef = useRef(0)
    const currentUserIsSuperAdmin = currentUser.roles.includes('SUPER_ADMIN')

    useEffect(() => {
        const sequence = ++usersSequenceRef.current

        async function loadUsers() {
            setLoading(true)
            setLoadError('')

            try {
                const response = await getUsers(page, PAGE_SIZE)

                if (sequence !== usersSequenceRef.current) {
                    return
                }

                const normalized = normalizePageResponse(response)
                setUsers(normalized.content)
                setTotalPages(normalized.totalPages)
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
    }, [page, reloadToken])

    useEffect(() => {
        if (!currentUserIsSuperAdmin) {
            setOrganizations([])
            setSelectedOrganizationId('')
            return
        }

        const sequence = ++organizationsSequenceRef.current

        async function loadOrganizations() {
            setOrganizationsLoading(true)

            try {
                const loaded = (await loadAllOrganizations()).filter(
                    (organization) => organization.id !== PLATFORM_ORGANIZATION_ID
                )

                if (sequence === organizationsSequenceRef.current) {
                    setOrganizations(loaded)
                    setSelectedOrganizationId((current) =>
                        loaded.some((organization) => organization.id === current)
                            ? current
                            : ''
                    )
                }
            } catch (error) {
                if (sequence === organizationsSequenceRef.current) {
                    setOrganizations([])
                    setCreateError(
                        getApiErrorMessage(error, 'Не удалось загрузить организации.')
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

    useEffect(() => {
        if (!success) {
            return
        }
        const timeoutId = window.setTimeout(() => setSuccess(''), SUCCESS_MESSAGE_TIMEOUT_MS)
        return () => window.clearTimeout(timeoutId)
    }, [success])

    const filteredUsers = useMemo(() => {
        return filter === 'ALL'
            ? users
            : users.filter((user) => user.roles.includes(filter))
    }, [users, filter])

    const pageAdminCount = users.filter((user) => user.roles.includes('ADMIN')).length
    const pageUserCount = users.filter((user) => user.roles.includes('USER')).length

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
            setSuccess(`Пользователь ${created.email} создан.`)
            requestReloadFromFirstPage()
        } catch (error) {
            setCreateError(getApiErrorMessage(error, 'Не удалось создать пользователя.'))
        } finally {
            setCreating(false)
        }
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

    function closeMutationModals() {
        if (actionUserId) {
            return
        }
        setEditUser(null)
        setRolesUser(null)
        setResetPasswordUser(null)
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
            'Не удалось изменить пароль.',
            async () => {
                await resetUserPassword(resetPasswordUser.id, {
                    password: resetPasswordValue,
                })

                setSuccess(`Пароль для ${resetPasswordUser.email} изменён.`)
                setResetPasswordUser(null)
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

    return (
        <div className="page">
            <h1>Пользователи</h1>

            {mutationError && <div className="error">{mutationError}</div>}
            {success && <div className="success">{success}</div>}

            <div className="card form-card">
                <h2>Создать пользователя</h2>
                <form className="form" onSubmit={handleCreateUser}>
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
                        />
                    </label>
                    <label>
                        Пароль
                        <input
                            value={password}
                            onChange={(event) => setPassword(event.target.value)}
                            type="password"
                            minLength={12}
                            maxLength={72}
                            autoComplete="new-password"
                            required
                            disabled={creating}
                        />
                    </label>
                    <label>
                        Повторите пароль
                        <input
                            value={passwordConfirm}
                            onChange={(event) => setPasswordConfirm(event.target.value)}
                            type="password"
                            minLength={12}
                            maxLength={72}
                            autoComplete="new-password"
                            required
                            disabled={creating}
                        />
                    </label>
                    <label>
                        Полное имя
                        <input
                            value={fullName}
                            onChange={(event) => setFullName(event.target.value)}
                            maxLength={255}
                            disabled={creating}
                        />
                    </label>

                    {currentUserIsSuperAdmin && (
                        <label>
                            Организация
                            <select
                                value={selectedOrganizationId}
                                onChange={(event) => setSelectedOrganizationId(event.target.value)}
                                disabled={creating || organizationsLoading}
                                required
                            >
                                <option value="">Выберите организацию</option>
                                {organizations.map((organization) => (
                                    <option key={organization.id} value={organization.id}>
                                        {organization.name}
                                    </option>
                                ))}
                            </select>
                        </label>
                    )}

                    <fieldset disabled={creating}>
                        <legend>Роли</legend>
                        <label>
                            <input
                                type="checkbox"
                                checked={createRoles.includes('USER')}
                                onChange={() => toggleCreateRole('USER')}
                            />
                            USER
                        </label>
                        {currentUserIsSuperAdmin && (
                            <label>
                                <input
                                    type="checkbox"
                                    checked={createRoles.includes('ADMIN')}
                                    onChange={() => toggleCreateRole('ADMIN')}
                                />
                                ADMIN
                            </label>
                        )}
                    </fieldset>

                    {createError && <div className="error">{createError}</div>}
                    <button disabled={creating || organizationsLoading}>
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
                    На странице: все ({users.length})
                </button>
                <button
                    type="button"
                    className={filter === 'ADMIN' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setFilter('ADMIN')}
                >
                    ADMIN ({pageAdminCount})
                </button>
                <button
                    type="button"
                    className={filter === 'USER' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setFilter('USER')}
                >
                    USER ({pageUserCount})
                </button>
            </div>
            <p className="muted">Фильтр и счётчики относятся только к текущей странице.</p>

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
            {!loading && !loadError && filteredUsers.length === 0 && (
                <EmptyState message="Пользователи не найдены на текущей странице." />
            )}

            {!loading && !loadError && filteredUsers.length > 0 && (
                <div className="card table-card">
                    <table className="admin-table users-table">
                        <thead>
                        <tr>
                            <th>Email</th>
                            <th>Полное имя</th>
                            {currentUserIsSuperAdmin && <th>Организация</th>}
                            <th>Роли</th>
                            <th>Статус</th>
                            <th>Создан</th>
                            <th>Действия</th>
                        </tr>
                        </thead>
                        <tbody>
                        {filteredUsers.map((user) => {
                            const manageable = canManageUser(user)
                            const isBusy = actionUserId === user.id
                            return (
                                <tr key={user.id}>
                                    <td>{user.email}</td>
                                    <td>{user.fullName ?? '—'}</td>
                                    {currentUserIsSuperAdmin && (
                                        <td>{getOrganizationName(user.organizationId, organizations)}</td>
                                    )}
                                    <td>
                                        <div className="role-list">
                                            {user.roles.map((role) => (
                                                <span key={role} className={getRoleBadgeClass(role)}>
                                                        {role}
                                                    </span>
                                            ))}
                                        </div>
                                    </td>
                                    <td>
                                            <span className={user.enabled ? 'status-badge status-enabled' : 'status-badge status-disabled'}>
                                                {user.enabled ? 'включён' : 'отключён'}
                                            </span>
                                    </td>
                                    <td>{formatDateTime(user.createdAt)}</td>
                                    <td className="actions-cell">
                                        <div className="table-actions table-actions-compact">
                                            <button type="button" className="secondary-button" onClick={() => setDetailsUser(user)}>
                                                Детали
                                            </button>
                                            {manageable && (
                                                <>
                                                    <button type="button" className="secondary-button" disabled={isBusy} onClick={() => openEditModal(user)}>
                                                        Изменить
                                                    </button>
                                                    <button type="button" className="secondary-button" disabled={isBusy} onClick={() => openRolesModal(user)}>
                                                        Роли
                                                    </button>
                                                    <button type="button" className="secondary-button" disabled={isBusy} onClick={() => openResetPasswordModal(user)}>
                                                        Пароль
                                                    </button>
                                                    <button
                                                        type="button"
                                                        className={user.enabled ? 'danger-button' : 'secondary-button'}
                                                        disabled={isBusy}
                                                        onClick={() => setConfirmState({ user, nextEnabled: !user.enabled })}
                                                    >
                                                        {user.enabled ? 'Отключить' : 'Включить'}
                                                    </button>
                                                </>
                                            )}
                                            {!manageable && (
                                                <span className="muted">
                                                        {user.roles.includes('SUPER_ADMIN')
                                                            ? 'Платформенный администратор'
                                                            : user.id === currentUser.id
                                                                ? 'Текущий пользователь'
                                                                : 'Недоступно для ADMIN'}
                                                    </span>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            )
                        })}
                        </tbody>
                    </table>

                    <div className="pagination">
                        <button type="button" className="secondary-button" disabled={page === 0 || loading} onClick={() => setPage((value) => Math.max(0, value - 1))}>
                            Назад
                        </button>
                        <span>Страница {page + 1} из {Math.max(totalPages, 1)}</span>
                        <button type="button" className="secondary-button" disabled={page + 1 >= totalPages || loading} onClick={() => setPage((value) => value + 1)}>
                            Вперёд
                        </button>
                    </div>
                </div>
            )}

            {detailsUser && (
                <Modal title={`Пользователь: ${detailsUser.email}`} onClose={() => setDetailsUser(null)}>
                    <div className="form">
                        <p><strong>ID:</strong> {detailsUser.id}</p>
                        <p><strong>Email:</strong> {detailsUser.email}</p>
                        <p><strong>Полное имя:</strong> {detailsUser.fullName ?? '—'}</p>
                        <p><strong>Организация:</strong> {getOrganizationName(detailsUser.organizationId, organizations)}</p>
                        <p><strong>Статус:</strong> {detailsUser.enabled ? 'включён' : 'отключён'}</p>
                        <p><strong>Роли:</strong> {detailsUser.roles.join(', ')}</p>
                        <p><strong>Создан:</strong> {formatDateTime(detailsUser.createdAt)}</p>
                        <div className="modal-actions">
                            <button type="button" className="secondary-button" onClick={() => setDetailsUser(null)}>Закрыть</button>
                        </div>
                    </div>
                </Modal>
            )}

            {editUser && (
                <Modal title={`Изменить: ${editUser.email}`} onClose={closeMutationModals} closeDisabled={actionUserId === editUser.id}>
                    <form className="form" onSubmit={submitEditUser}>
                        <label>Email<input value={editEmail} onChange={(event) => setEditEmail(event.target.value)} type="email" maxLength={255} required disabled={actionUserId === editUser.id} /></label>
                        <label>Полное имя<input value={editFullName} onChange={(event) => setEditFullName(event.target.value)} maxLength={255} disabled={actionUserId === editUser.id} /></label>
                        {modalError && <div className="error">{modalError}</div>}
                        <div className="modal-actions">
                            <button type="button" className="secondary-button" disabled={actionUserId === editUser.id} onClick={closeMutationModals}>Отмена</button>
                            <button disabled={actionUserId === editUser.id || !editEmail.trim()}>{actionUserId === editUser.id ? 'Сохранение...' : 'Сохранить'}</button>
                        </div>
                    </form>
                </Modal>
            )}

            {rolesUser && (
                <Modal title={`Роли: ${rolesUser.email}`} onClose={closeMutationModals} closeDisabled={actionUserId === rolesUser.id}>
                    <form className="form" onSubmit={submitRoles}>
                        <fieldset disabled={actionUserId === rolesUser.id}>
                            <legend>Выберите роли</legend>
                            <label><input type="checkbox" checked={selectedRoles.includes('USER')} onChange={() => toggleSelectedRole('USER')} /> USER</label>
                            <label><input type="checkbox" checked={selectedRoles.includes('ADMIN')} onChange={() => toggleSelectedRole('ADMIN')} /> ADMIN</label>
                        </fieldset>
                        {modalError && <div className="error">{modalError}</div>}
                        <div className="modal-actions">
                            <button type="button" className="secondary-button" disabled={actionUserId === rolesUser.id} onClick={closeMutationModals}>Отмена</button>
                            <button disabled={actionUserId === rolesUser.id || selectedRoles.length === 0}>{actionUserId === rolesUser.id ? 'Сохранение...' : 'Сохранить роли'}</button>
                        </div>
                    </form>
                </Modal>
            )}

            {resetPasswordUser && (
                <Modal title={`Сброс пароля: ${resetPasswordUser.email}`} onClose={closeMutationModals} closeDisabled={actionUserId === resetPasswordUser.id}>
                    <form className="form" onSubmit={submitResetPassword}>
                        <label>Новый пароль<input type="password" value={resetPasswordValue} onChange={(event) => setResetPasswordValue(event.target.value)} minLength={12} maxLength={72} required disabled={actionUserId === resetPasswordUser.id} /></label>
                        <label>Повторите пароль<input type="password" value={resetPasswordConfirm} onChange={(event) => setResetPasswordConfirm(event.target.value)} minLength={12} maxLength={72} required disabled={actionUserId === resetPasswordUser.id} /></label>
                        {modalError && <div className="error">{modalError}</div>}
                        <div className="modal-actions">
                            <button type="button" className="secondary-button" disabled={actionUserId === resetPasswordUser.id} onClick={closeMutationModals}>Отмена</button>
                            <button disabled={actionUserId === resetPasswordUser.id || !resetPasswordValue || !resetPasswordConfirm}>{actionUserId === resetPasswordUser.id ? 'Сброс...' : 'Сбросить пароль'}</button>
                        </div>
                    </form>
                </Modal>
            )}

            {confirmState && (
                <ConfirmDialog
                    title={confirmState.nextEnabled ? 'Включить пользователя' : 'Отключить пользователя'}
                    message={`${confirmState.nextEnabled ? 'Включить' : 'Отключить'} пользователя ${confirmState.user.email}?`}
                    confirmText={confirmState.nextEnabled ? 'Включить' : 'Отключить'}
                    danger={!confirmState.nextEnabled}
                    loading={actionUserId === confirmState.user.id}
                    onCancel={() => setConfirmState(null)}
                    onConfirm={() => void confirmEnabledChange()}
                />
            )}
        </div>
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

function getRoleBadgeClass(role: UserRole): string {
    if (role === 'SUPER_ADMIN') return 'role-badge role-super-admin'
    if (role === 'ADMIN') return 'role-badge role-admin'
    return 'role-badge role-user'
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
