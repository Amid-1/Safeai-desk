//pages/AdminUsersPage.tsx
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
import { getApiErrorMessage } from '../api/http'

const DEMO_ORGANIZATION_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

type Role = 'USER' | 'ADMIN'
type UserFilter = 'ALL' | 'ADMIN' | 'USER'

function AdminUsersPage() {
    const [users, setUsers] = useState<User[]>([])
    const [error, setError] = useState('')
    const [success, setSuccess] = useState('')
    const [loading, setLoading] = useState(true)
    const [creating, setCreating] = useState(false)
    const [actionUserId, setActionUserId] = useState<string | null>(null)

    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [passwordConfirm, setPasswordConfirm] = useState('')
    const [fullName, setFullName] = useState('')
    const [role, setRole] = useState<Role>('USER')
    const [filter, setFilter] = useState<UserFilter>('ALL')

    useEffect(() => {
        void loadUsers()
    }, [])

    const filteredUsers = useMemo(() => {
        if (filter === 'ALL') {
            return users
        }

        return users.filter((user) => user.roles.includes(filter))
    }, [users, filter])

    const adminCount = users.filter((user) => user.roles.includes('ADMIN')).length
    const userCount = users.filter((user) => user.roles.includes('USER')).length

    async function loadUsers() {
        setError('')
        setLoading(true)

        try {
            const data = await getUsers()
            setUsers(data)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to load users'))
        } finally {
            setLoading(false)
        }
    }

    async function handleCreateUser(event: SyntheticEvent<HTMLFormElement>) {
        event.preventDefault()

        const normalizedEmail = email.trim()

        if (!normalizedEmail) {
            setError('Email is required')
            return
        }

        if (!password.trim()) {
            setError('Password is required')
            return
        }

        if (password.length < 6) {
            setError('Password must be at least 6 characters')
            return
        }

        if (password !== passwordConfirm) {
            setError('Passwords do not match')
            return
        }

        setError('')
        setSuccess('')
        setCreating(true)

        try {
            const createdUser = await createUser({
                organizationId: DEMO_ORGANIZATION_ID,
                email: normalizedEmail,
                password,
                fullName: fullName.trim() || null,
                roles: [role],
            })

            setUsers((prev) => [createdUser, ...prev])

            setEmail('')
            setPassword('')
            setPasswordConfirm('')
            setFullName('')
            setRole('USER')

            setSuccess(`User ${createdUser.email} created`)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to create user'))
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
            `New password for ${user.email}. Minimum 6 characters:`
        )

        if (newPassword === null) {
            return
        }

        if (newPassword.length < 6) {
            setError('Password must be at least 6 characters')
            return
        }

        const confirmPassword = window.prompt('Repeat new password:')

        if (confirmPassword === null) {
            return
        }

        if (newPassword !== confirmPassword) {
            setError('Passwords do not match')
            return
        }

        setError('')
        setSuccess('')
        setActionUserId(user.id)

        try {
            const updatedUser = await resetUserPassword(user.id, {
                password: newPassword,
            })

            replaceUser(updatedUser)

            setSuccess(`Password for ${updatedUser.email} reset`)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to reset password'))
        } finally {
            setActionUserId(null)
        }
    }

    function replaceUser(updatedUser: User) {
        setUsers((prev) =>
            prev.map((user) => (user.id === updatedUser.id ? updatedUser : user))
        )
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
                        />
                    </label>

                    <label>
                        Password
                        <input
                            value={password}
                            onChange={(event) => setPassword(event.target.value)}
                            placeholder="Minimum 6 characters"
                            type="password"
                            minLength={6}
                        />
                    </label>

                    <label>
                        Confirm password
                        <input
                            value={passwordConfirm}
                            onChange={(event) => setPasswordConfirm(event.target.value)}
                            placeholder="Repeat password"
                            type="password"
                            minLength={6}
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
                                                className={
                                                    userRole === 'ADMIN'
                                                        ? 'role-badge role-admin'
                                                        : 'role-badge role-user'
                                                }
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
                                <td>{user.createdAt}</td>
                                <td>
                                    <div className="user-actions">
                                        <button
                                            className={user.enabled ? 'danger-button' : 'secondary-button'}
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
                                </td>
                            </tr>
                        )
                    })}
                    </tbody>
                </table>
            </div>
        </div>
    )
}

export default AdminUsersPage