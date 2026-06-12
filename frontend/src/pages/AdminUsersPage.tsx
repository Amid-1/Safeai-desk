import { useEffect, useMemo, useState } from 'react'
import type { SyntheticEvent } from 'react'
import { createUser, getUsers } from '../api/userApi'
import type { User } from '../api/userApi'

const DEMO_ORGANIZATION_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

type Role = 'USER' | 'ADMIN'
type UserFilter = 'ALL' | 'ADMIN' | 'USER'

function AdminUsersPage() {
    const [users, setUsers] = useState<User[]>([])
    const [error, setError] = useState('')
    const [success, setSuccess] = useState('')
    const [loading, setLoading] = useState(true)
    const [creating, setCreating] = useState(false)

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
            setError(err instanceof Error ? err.message : 'Failed to load users')
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
            setError(err instanceof Error ? err.message : 'Failed to create user')
        } finally {
            setCreating(false)
        }
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
                    </tr>
                    </thead>

                    <tbody>
                    {filteredUsers.map((user) => (
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
                            <td>{user.enabled ? 'yes' : 'no'}</td>
                            <td>{user.createdAt}</td>
                        </tr>
                    ))}
                    </tbody>
                </table>
            </div>
        </div>
    )
}

export default AdminUsersPage