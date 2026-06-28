// frontend/src/App.tsx
import { NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import type { ReactNode } from 'react'
import LoginPage from './pages/LoginPage'
import ChatPage from './pages/ChatPage'
import AdminUsersPage from './pages/AdminUsersPage'
import AdminAuditPage from './pages/AdminAuditPage'
import AdminUsagePage from './pages/AdminUsagePage'
import type { AuthUser } from './api/authApi'
import { AuthProvider, useAuth } from './auth/AuthContext'

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

function getNavLinkClass({ isActive }: { isActive: boolean }): string {
    return isActive ? 'nav-link active' : 'nav-link'
}

function RequireAuth({ children }: { children: ReactNode }) {
    const { currentUser, authLoading } = useAuth()

    if (authLoading) {
        return <p>Checking access...</p>
    }

    if (!currentUser) {
        return <Navigate to="/login" replace />
    }

    return children
}

function RequireAdmin({ children }: { children: ReactNode }) {
    const { currentUser, authLoading } = useAuth()

    if (authLoading) {
        return <p>Checking access...</p>
    }

    if (!currentUser) {
        return <Navigate to="/login" replace />
    }

    if (!hasAnyRole(currentUser, ['ADMIN', 'SUPER_ADMIN'])) {
        return <Navigate to="/chat" replace />
    }

    return children
}

function AdminUsersRoute() {
    const { currentUser, authLoading } = useAuth()

    if (authLoading) {
        return <p>Checking access...</p>
    }

    if (!currentUser) {
        return <Navigate to="/login" replace />
    }

    if (!hasAnyRole(currentUser, ['ADMIN', 'SUPER_ADMIN'])) {
        return <Navigate to="/chat" replace />
    }

    return <AdminUsersPage currentUser={currentUser} />
}

function AppLayout() {
    const navigate = useNavigate()
    const { currentUser, logoutUser } = useAuth()

    const isAuthenticated = currentUser !== null
    const canAccessAdmin = hasAnyRole(currentUser, ['ADMIN', 'SUPER_ADMIN'])
    const displayRole = getDisplayRole(currentUser)

    async function handleLogout() {
        await logoutUser()
        navigate('/login', { replace: true })
    }

    return (
        <div className="app">
            {isAuthenticated && (
                <header className="topbar">
                    <div className="logo">SafeAI Desk</div>

                    <nav>
                        <NavLink to="/chat" className={getNavLinkClass}>
                            Chat
                        </NavLink>

                        {canAccessAdmin && (
                            <>
                                <NavLink to="/admin/users" className={getNavLinkClass}>
                                    Users
                                </NavLink>

                                <NavLink to="/admin/audit" className={getNavLinkClass}>
                                    Audit
                                </NavLink>

                                <NavLink to="/admin/usage" className={getNavLinkClass}>
                                    Usage
                                </NavLink>
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

                        <button onClick={() => void handleLogout()}>
                            Logout
                        </button>
                    </div>
                </header>
            )}

            <main className="content">
                <Routes>
                    <Route
                        path="/"
                        element={<Navigate to={isAuthenticated ? '/chat' : '/login'} replace />}
                    />

                    <Route path="/login" element={<LoginPage />} />

                    <Route
                        path="/chat"
                        element={
                            <RequireAuth>
                                <ChatPage />
                            </RequireAuth>
                        }
                    />

                    <Route path="/admin/users" element={<AdminUsersRoute />} />

                    <Route
                        path="/admin/audit"
                        element={
                            <RequireAdmin>
                                <AdminAuditPage />
                            </RequireAdmin>
                        }
                    />

                    <Route
                        path="/admin/usage"
                        element={
                            <RequireAdmin>
                                <AdminUsagePage />
                            </RequireAdmin>
                        }
                    />

                    <Route path="*" element={<Navigate to="/" replace />} />
                </Routes>
            </main>
        </div>
    )
}

function App() {
    return (
        <AuthProvider>
            <AppLayout />
        </AuthProvider>
    )
}

export default App