//frontend/src/App.tsx
import { Link, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import LoginPage from './pages/LoginPage'
import ChatPage from './pages/ChatPage'
import AdminUsersPage from './pages/AdminUsersPage'
import AdminAuditPage from './pages/AdminAuditPage'
import AdminUsagePage from './pages/AdminUsagePage'
import { clearToken, getToken } from './api/http'
import { getCurrentUser } from './api/authApi'
import type { AuthUser } from './api/authApi'

function hasAnyRole(user: AuthUser | null, roles: string[]): boolean {
    return user?.roles.some((role) => roles.includes(role)) ?? false
}

function getDisplayRole(user: AuthUser | null): string {
    if (!user) {
        return ''
    }

    if (user.roles.includes('SUPER_ADMIN')) {
        return 'SUPER_ADMIN'
    }

    if (user.roles.includes('ADMIN')) {
        return 'ADMIN'
    }

    return 'USER'
}

function RequireAuth({
                         children,
                         authLoading,
                     }: {
    children: ReactNode
    authLoading: boolean
}) {
    const token = getToken()

    if (!token) {
        return <Navigate to="/login" />
    }

    if (authLoading) {
        return <p>Checking access...</p>
    }

    return children
}

function RequireAdmin({
                          children,
                          currentUser,
                          authLoading,
                      }: {
    children: ReactNode
    currentUser: AuthUser | null
    authLoading: boolean
}) {
    const token = getToken()

    if (!token) {
        return <Navigate to="/login" />
    }

    if (authLoading) {
        return <p>Checking access...</p>
    }

    if (!currentUser || !hasAnyRole(currentUser, ['ADMIN', 'SUPER_ADMIN'])) {
        return <Navigate to="/chat" />
    }

    return children
}

function App() {
    const navigate = useNavigate()
    const token = getToken()

    const [currentUser, setCurrentUser] = useState<AuthUser | null>(null)
    const [authLoading, setAuthLoading] = useState(Boolean(token))

    const canAccessAdmin = hasAnyRole(currentUser, ['ADMIN', 'SUPER_ADMIN'])
    const displayRole = getDisplayRole(currentUser)

    useEffect(() => {
        if (!token) {
            setCurrentUser(null)
            setAuthLoading(false)
            return
        }

        setAuthLoading(true)

        getCurrentUser()
            .then(setCurrentUser)
            .catch(() => {
                clearToken()
                setCurrentUser(null)
                navigate('/login')
            })
            .finally(() => {
                setAuthLoading(false)
            })
    }, [token, navigate])

    function logout() {
        clearToken()
        setCurrentUser(null)
        navigate('/login')
    }

    return (
        <div className="app">
            {token && (
                <header className="topbar">
                    <div className="logo">SafeAI Desk</div>

                    <nav>
                        <Link to="/chat">Chat</Link>

                        {canAccessAdmin && (
                            <>
                                <Link to="/admin/users">Users</Link>
                                <Link to="/admin/audit">Audit</Link>
                                <Link to="/admin/usage">Usage</Link>
                            </>
                        )}
                    </nav>

                    <div className="topbar-user">
                        {currentUser && (
                            <div className="current-user">
                                <span>{currentUser.email}</span>
                                <span
                                    className={
                                        displayRole === 'SUPER_ADMIN'
                                            ? 'role-badge role-super-admin'
                                            : displayRole === 'ADMIN'
                                                ? 'role-badge role-admin'
                                                : 'role-badge role-user'
                                    }
                                >
                                    {displayRole}
                                </span>
                            </div>
                        )}

                        <button onClick={logout}>Logout</button>
                    </div>
                </header>
            )}

            <main className="content">
                <Routes>
                    <Route path="/" element={<Navigate to={token ? '/chat' : '/login'} />} />
                    <Route path="/login" element={<LoginPage />} />

                    <Route
                        path="/chat"
                        element={
                            <RequireAuth authLoading={authLoading}>
                                <ChatPage />
                            </RequireAuth>
                        }
                    />

                    <Route
                        path="/admin/users"
                        element={
                            <RequireAdmin currentUser={currentUser} authLoading={authLoading}>
                                <AdminUsersPage currentUser={currentUser!} />
                            </RequireAdmin>
                        }
                    />

                    <Route
                        path="/admin/audit"
                        element={
                            <RequireAdmin currentUser={currentUser} authLoading={authLoading}>
                                <AdminAuditPage />
                            </RequireAdmin>
                        }
                    />

                    <Route
                        path="/admin/usage"
                        element={
                            <RequireAdmin currentUser={currentUser} authLoading={authLoading}>
                                <AdminUsagePage />
                            </RequireAdmin>
                        }
                    />

                    <Route path="*" element={<Navigate to="/" />} />
                </Routes>
            </main>
        </div>
    )
}

export default App