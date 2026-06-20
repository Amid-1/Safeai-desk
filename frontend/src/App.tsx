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

function RequireAuth({ children }: { children: ReactNode }) {
    const token = getToken()

    if (!token) {
        return <Navigate to="/login" />
    }

    return children
}

function RequireAdmin({
                          children,
                          currentUser,
                      }: {
    children: ReactNode
    currentUser: AuthUser | null
}) {
    const token = getToken()

    if (!token) {
        return <Navigate to="/login" />
    }

    if (currentUser && !currentUser.roles.includes('ADMIN')) {
        return <Navigate to="/chat" />
    }

    return children
}

function App() {
    const navigate = useNavigate()
    const token = getToken()

    const [currentUser, setCurrentUser] = useState<AuthUser | null>(null)

    const isAdmin = currentUser?.roles.includes('ADMIN') ?? false

    useEffect(() => {
        if (!token) {
            setCurrentUser(null)
            return
        }

        getCurrentUser()
            .then(setCurrentUser)
            .catch(() => {
                clearToken()
                setCurrentUser(null)
                navigate('/login')
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

                        {isAdmin && (
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
                                <span className={isAdmin ? 'role-badge role-admin' : 'role-badge role-user'}>
                                    {isAdmin ? 'ADMIN' : 'USER'}
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
                            <RequireAuth>
                                <ChatPage />
                            </RequireAuth>
                        }
                    />

                    <Route
                        path="/admin/users"
                        element={
                            <RequireAdmin currentUser={currentUser}>
                                <AdminUsersPage />
                            </RequireAdmin>
                        }
                    />

                    <Route
                        path="/admin/audit"
                        element={
                            <RequireAdmin currentUser={currentUser}>
                                <AdminAuditPage />
                            </RequireAdmin>
                        }
                    />

                    <Route
                        path="/admin/usage"
                        element={
                            <RequireAdmin currentUser={currentUser}>
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