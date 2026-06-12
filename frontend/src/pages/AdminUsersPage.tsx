import { useEffect, useState } from 'react'
import { getUsers, User } from '../api/userApi'

function AdminUsersPage() {
    const [users, setUsers] = useState<User[]>([])
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(true)

    useEffect(() => {
        async function loadUsers() {
            try {
                const data = await getUsers()
                setUsers(data)
            } catch (err) {
                setError(err instanceof Error ? err.message : 'Failed to load users')
            } finally {
                setLoading(false)
            }
        }

        void loadUsers()
    }, [])

    return (
        <div className="page">
            <h1>Admin Users</h1>

            {loading && <p>Loading...</p>}
            {error && <div className="error">{error}</div>}

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
                    {users.map((user) => (
                        <tr key={user.id}>
                            <td>{user.email}</td>
                            <td>{user.fullName ?? '-'}</td>
                            <td>{user.roles.join(', ')}</td>
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