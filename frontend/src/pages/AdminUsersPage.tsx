// frontend/src/pages/AdminUsersPage.tsx
import { useEffect, useMemo, useState } from 'react'
import type { SyntheticEvent } from 'react'
import {
    createUser,
    getUsers,
    resetUserPassword,
    updateUserEnabled,
    updateUserRoles,
} from '../api/userApi'
import type { User } from '../api/userApi'
import type { AuthUser } from '../api/authApi'
import { getApiErrorMessage } from '../api/http'
import { formatDateTime } from '../utils/format'
import { getPageContent, getPageTotalPages } from '../utils/page'

type Role = 'USER' | 'ADMIN'
type UserFilter = 'ALL' | 'ADMIN' | 'USER'

type AdminUsersPageProps = {
    currentUser: AuthUser
}

const PAGE_SIZE = 50

function AdminUsersPage({ currentUser }: AdminUsersPageProps) {
    const [users, setUsers] = useState<User[]>([])
    const [error, setError] = useState('')
    const [success, setSuccess] = useState('')
    const [loading, setLoading] = useState(true)
    const [creating, setCreating] = useState(false)
    const [actionUserId, setActionUserId] = useState<string | null>(null)

    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] = useState(1)

    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [passwordConfirm, setPasswordConfirm] = useState('')
    const [fullName, setFullName] = useState('')
    const [role, setRole] = useState<Role>('USER')
    const [filter, setFilter] = useState<UserFilter>('ALL')

    useEffect(() => {
        void loadUsers(page)
    }, [page])

    const filteredUsers = useMemo(() => {
        if (filter === 'ALL') {
            return users
        }

        return users.filter((user) => user.roles.includes(filter))
    }, [users, filter])

    const adminCount = users.filter((user) => user.roles.includes('ADMIN')).length
    const userCount = users.filter((user) => user.roles.includes('USER')).length

    async function loadUsers(nextPage = page) {
        setError('')
        setLoading(true)

        try {
            const data = await getUsers(nextPage, PAGE_SIZE)

            setUsers(getPageContent(data))
            setTotalPages(getPageTotalPages(data))
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to load users'))
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

        setCreating(true)

        try {
            const createdUser = await createUser({
                organizationId: currentUser.organizationId,
                email: normalizedEmail,
                password,
                fullName: fullName.trim() || null,
                roles: [role],
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

    async function handleToggleEnabled(user: User) {
        const nextEnabled = !user.enabled

        const confirmed = window.confirm(
            nextEnabled
                ? `Enable user ${user.email}?`
                : `Disable user ${user.email}?`
        )

        if (!confirmed) {
            return
        }

        setError('')
        setSuccess('')
        setActionUserId(user.id)

        try {
            const updatedUser = await updateUserEnabled(user.id, {
                enabled: nextEnabled,
            })

            replaceUser(updatedUser)

            setSuccess(
                nextEnabled
                    ? `User ${updatedUser.email} enabled`
                    : `User ${updatedUser.email} disabled`
            )
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to update user status'))
        } finally {
            setActionUserId(null)
        }
    }

    async function handleChangeRole(user: User, nextRole: Role) {
        const confirmed = window.confirm(
            `Change role for ${user.email} to ${nextRole}?`
        )

        if (!confirmed) {
            return
        }

        setError('')
        setSuccess('')
        setActionUserId(user.id)

        try {
            const updatedUser = await updateUserRoles(user.id, {
                roles: [nextRole],
            })

            replaceUser(updatedUser)

            setSuccess(`Role for ${updatedUser.email} changed to ${nextRole}`)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to change user role'))
        } finally {
            setActionUserId(null)
        }
    }

    async function handleResetPassword(user: User) {
        const newPassword = window.prompt(
            `Новый пароль для ${user.email}. Минимум 12 символов: строчная буква, заглавная буква, цифра и спецсимвол.`
        )

        if (newPassword === null) {
            return
        }

        setError('')
        setSuccess('')

        const passwordValidationError = validatePassword(newPassword)

        if (passwordValidationError) {
            setError(passwordValidationError)
            return
        }

        const confirmPassword = window.prompt('Повторите новый пароль:')

        if (confirmPassword === null) {
            return
        }

        if (newPassword !== confirmPassword) {
            setError('Пароли не совпадают.')
            return
        }

        setActionUserId(user.id)

        try {
            await resetUserPassword(user.id, {
                password: newPassword,
            })

            setSuccess(`Пароль для ${user.email} изменен.`)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Не удалось изменить пароль.'))
        } finally {
            setActionUserId(null)
        }
    }

    function replaceUser(updatedUser: User) {
        setUsers((prev) =>
            prev.map((user) => (user.id === updatedUser.id ? updatedUser : user))
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

    return (
        <div className="page">
            <h1>Admin Users</h1>

            {loading && <p>Loading...</p>}
            {error && <div className="error">{error}</div>}
            {success && <div className="success">{success}</div>}

            <div className="card form-card">
                <h2>Create user</h2>

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
                        Password
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
                        Confirm password
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
                        Full name
                        <input
                            value={fullName}
                            onChange={(event) => setFullName(event.target.value)}
                            placeholder="User full name"
                        />
                    </label>

                    <label>
                        Role
                        <select
                            value={role}
                            onChange={(event) => setRole(event.target.value as Role)}
                        >
                            <option value="USER">USER</option>
                            <option value="ADMIN">ADMIN</option>
                        </select>
                    </label>

                    <button disabled={creating}>
                        {creating ? 'Creating...' : 'Create user'}
                    </button>
                </form>
            </div>

            <div className="user-toolbar">
                <button
                    className={filter === 'ALL' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setFilter('ALL')}
                >
                    All ({users.length})
                </button>

                <button
                    className={filter === 'ADMIN' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setFilter('ADMIN')}
                >
                    Admins ({adminCount})
                </button>

                <button
                    className={filter === 'USER' ? 'filter-button active' : 'filter-button'}
                    onClick={() => setFilter('USER')}
                >
                    Users ({userCount})
                </button>
            </div>

            <div className="card">
                {filteredUsers.length === 0 && !loading && (
                    <p>No users found.</p>
                )}

                {filteredUsers.length > 0 && (
                    <table>
                        <thead>
                        <tr>
                            <th>Email</th>
                            <th>Full name</th>
                            <th>Roles</th>
                            <th>Enabled</th>
                            <th>Created at</th>
                            <th>Actions</th>
                        </tr>
                        </thead>

                        <tbody>
                        {filteredUsers.map((user) => {
                            const isAdmin = user.roles.includes('ADMIN')
                            const isSuperAdminUser = user.roles.includes('SUPER_ADMIN')
                            const isBusy = actionUserId === user.id

                            return (
                                <tr key={user.id}>
                                    <td>{user.email}</td>
                                    <td>{user.fullName ?? '-'}</td>

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
                                            {user.enabled ? 'enabled' : 'disabled'}
                                        </span>
                                    </td>

                                    <td>{formatDateTime(user.createdAt)}</td>

                                    <td>
                                        {isSuperAdminUser ? (
                                            <span className="muted">Platform admin</span>
                                        ) : (
                                            <div className="user-actions">
                                                <button
                                                    className={
                                                        user.enabled
                                                            ? 'danger-button'
                                                            : 'secondary-button'
                                                    }
                                                    disabled={isBusy}
                                                    onClick={() => void handleToggleEnabled(user)}
                                                >
                                                    {user.enabled ? 'Disable' : 'Enable'}
                                                </button>

                                                <button
                                                    className="secondary-button"
                                                    disabled={isBusy}
                                                    onClick={() => void handleResetPassword(user)}
                                                >
                                                    Reset password
                                                </button>

                                                {isAdmin ? (
                                                    <button
                                                        className="secondary-button"
                                                        disabled={isBusy}
                                                        onClick={() => void handleChangeRole(user, 'USER')}
                                                    >
                                                        Make USER
                                                    </button>
                                                ) : (
                                                    <button
                                                        className="secondary-button"
                                                        disabled={isBusy}
                                                        onClick={() => void handleChangeRole(user, 'ADMIN')}
                                                    >
                                                        Make ADMIN
                                                    </button>
                                                )}
                                            </div>
                                        )}
                                    </td>
                                </tr>
                            )
                        })}
                        </tbody>
                    </table>
                )}

                <div className="pagination">
                    <button
                        type="button"
                        className="secondary-button"
                        disabled={page === 0 || loading}
                        onClick={() => setPage((prev) => Math.max(0, prev - 1))}
                    >
                        Previous
                    </button>

                    <span>
                        Page {page + 1} of {Math.max(totalPages, 1)}
                    </span>

                    <button
                        type="button"
                        className="secondary-button"
                        disabled={page + 1 >= totalPages || loading}
                        onClick={() => setPage((prev) => prev + 1)}
                    >
                        Next
                    </button>
                </div>
            </div>
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