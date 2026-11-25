// frontend/src/app/App.tsx
// ============================================================
// frontend/src/app/App.tsx
// ============================================================
import {
    Fragment,
    lazy,
    Suspense,
    useEffect,
    useRef,
    useState,
    type MouseEvent,
    type ReactNode,
} from 'react'
import {
    NavLink,
    Navigate,
    Route,
    Routes,
    useLocation,
    useNavigate,
} from 'react-router-dom'
import type { AuthUser } from '../features/auth/api/authApi'
import type { UserRole } from '../shared/api/types'
import { AuthProvider } from '../features/auth/state/AuthContext'
import { useAuth } from '../features/auth/state/useAuth'
import { ErrorState, LoadingState } from '../shared/ui/StateBlock'
import { roleLabel } from '../shared/lib/roleLabel'

const LoginPage = lazy(() => import('../pages/LoginPage'))
const ChatPage = lazy(() => import('../pages/ChatPage'))
const AdminUsersPage = lazy(
    () => import('../pages/AdminUsersPage'),
)
const AdminOrganizationsPage = lazy(
    () => import('../pages/AdminOrganizationsPage'),
)
const KnowledgePage = lazy(
    () => import('../pages/KnowledgePage'),
)
const KnowledgeDetailsPage = lazy(
    () => import('../pages/KnowledgeDetailsPage'),
)
const AdminAuditPage = lazy(
    () => import('../pages/AdminAuditPage'),
)
const AdminUsagePage = lazy(
    () => import('../pages/AdminUsagePage'),
)
const AdminDashboardPage = lazy(() => import('../pages/AdminDashboardPage'))
const AdminRetentionPage = lazy(() => import('../pages/AdminRetentionPage'))
const PrivacySettingsPage = lazy(() => import('../pages/PrivacySettingsPage'))
const ErasureReviewCenterPage = lazy(() => import('../pages/ErasureReviewCenterPage'))
const ErasureReviewQueuePage = lazy(() => import('../pages/ErasureReviewQueuePage'))
const ErasureReviewDetailsPage = lazy(() => import('../pages/ErasureReviewDetailsPage'))
const ErasureRequestsPage = lazy(() => import('../pages/ErasureRequestsPage'))
const AdminModelsPage = lazy(
    () => import('../pages/AdminModelsPage'),
)

type ProtectedRouteProps = {
    roles?: readonly UserRole[]
    children: ReactNode | ((currentUser: AuthUser) => ReactNode)
}

function hasAnyRole(
    user: AuthUser | null,
    roles: readonly UserRole[],
): boolean {
    return user?.roles.some((role) => roles.includes(role)) ?? false
}

function hasRequiredRole(
    user: AuthUser,
    roles?: readonly UserRole[],
): boolean {
    if (!roles || roles.length === 0) {
        return true
    }

    return user.roles.some((role) => roles.includes(role))
}

function isSuperAdmin(user: AuthUser | null): boolean {
    return user?.roles.includes('SUPER_ADMIN') ?? false
}

function getDefaultAuthenticatedRoute(user: AuthUser): string {
    return isSuperAdmin(user)
        ? '/admin/dashboard'
        : '/chat'
}

function getDisplayRole(user: AuthUser | null): UserRole | null {
    if (!user) {
        return null
    }

    if (user.roles.includes('SUPER_ADMIN')) {
        return 'SUPER_ADMIN'
    }

    if (user.roles.includes('ADMIN')) {
        return 'ADMIN'
    }

    return 'USER'
}

function getNavLinkClass({
    isActive,
}: {
    isActive: boolean
}): string {
    return isActive
        ? 'nav-link active'
        : 'nav-link'
}

function AuthUnavailableState() {
    const {
        authError,
        reloadCurrentUser,
    } = useAuth()

    return (
        <div className="page narrow-page route-state">
            <ErrorState
                title="Сервис временно недоступен"
                message={
                    authError
                    ?? 'Не удалось проверить состояние сессии.'
                }
                action={
                    <button
                        type="button"
                        onClick={() => void reloadCurrentUser()}
                    >
                        Повторить
                    </button>
                }
            />
        </div>
    )
}

function AccessDeniedState() {
    return (
        <div className="page narrow-page route-state">
            <ErrorState
                title="Недостаточно прав"
                message="У вас нет доступа к этой странице."
            />
        </div>
    )
}

function RouteLoadingState() {
    return (
        <div className="page narrow-page route-state">
            <LoadingState message="Загрузка страницы..." />
        </div>
    )
}

function ProtectedRoute({
    roles,
    children,
}: ProtectedRouteProps) {
    const {
        currentUser,
        authStatus,
    } = useAuth()

    if (authStatus === 'loading') {
        return <LoadingState message="Проверка доступа..." />
    }

    if (authStatus === 'temporarily-unavailable') {
        return <AuthUnavailableState />
    }

    if (authStatus !== 'authenticated' || !currentUser) {
        return <Navigate to="/login" replace />
    }

    if (!currentUser.enabled || !hasRequiredRole(currentUser, roles)) {
        return <AccessDeniedState />
    }

    const scope = JSON.stringify([currentUser.id, currentUser.organizationId, [...currentUser.roles].sort()])
    return (
        <Fragment key={scope}>
            {typeof children === 'function' ? children(currentUser) : children}
        </Fragment>
    )
}

function RootRedirect() {
    const {
        currentUser,
        authStatus,
    } = useAuth()

    if (authStatus === 'loading') {
        return <LoadingState message="Проверка доступа..." />
    }

    if (authStatus === 'temporarily-unavailable') {
        return <AuthUnavailableState />
    }

    return (
        <Navigate
            to={
                authStatus === 'authenticated' && currentUser
                    ? getDefaultAuthenticatedRoute(currentUser)
                    : '/login'
            }
            replace
        />
    )
}

function LoginRoute() {
    const {
        currentUser,
        authStatus,
    } = useAuth()

    if (authStatus === 'loading') {
        return <LoadingState message="Проверка доступа..." />
    }

    if (authStatus === 'temporarily-unavailable') {
        return <AuthUnavailableState />
    }

    if (authStatus === 'authenticated' && currentUser) {
        return (
            <Navigate
                to={getDefaultAuthenticatedRoute(currentUser)}
                replace
            />
        )
    }

    return <LoginPage />
}

function NotFoundRoute() {
    const navigate = useNavigate()

    return (
        <div className="page narrow-page route-state">
            <ErrorState
                title="Страница не найдена"
                message="Проверьте адрес или вернитесь на главную страницу."
                action={
                    <button
                        type="button"
                        onClick={() => navigate('/', { replace: true })}
                    >
                        На главную
                    </button>
                }
            />
        </div>
    )
}

function AppLayout() {
    const navigate = useNavigate()
    const location = useLocation()
    const {
        currentUser,
        logoutUser,
        authStatus,
    } = useAuth()
    const [logoutPending, setLogoutPending] = useState(false)
    const [menuLocation, setMenuLocation] = useState<string | null>(null)
    const menuButtonRef = useRef<HTMLButtonElement>(null)
    const mainRef = useRef<HTMLElement>(null)
    const menuOpen = menuLocation === location.key

    useEffect(() => {
        if (!menuOpen) return
        function handleEscape(event: KeyboardEvent) {
            if (event.key !== 'Escape' || event.defaultPrevented) return
            if (!menuButtonRef.current?.getClientRects().length) return
            setMenuLocation(null)
            menuButtonRef.current.focus()
        }
        window.addEventListener('keydown', handleEscape)
        return () => window.removeEventListener('keydown', handleEscape)
    }, [menuOpen])

    function closeMenuOnNavigate(event: MouseEvent<HTMLAnchorElement>) {
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
        if (menuOpen) {
            setMenuLocation(null)
            mainRef.current?.focus()
        }
    }

    const isAuthenticated = authStatus === 'authenticated' && currentUser?.enabled === true
    const canAccessChat = hasAnyRole(
        currentUser,
        ['ADMIN', 'USER'],
    )
    const canAccessKnowledge = canAccessChat
    const canAccessAdmin = hasAnyRole(
        currentUser,
        [
            'ADMIN',
            'SUPER_ADMIN',
        ],
    )
    const canAccessOrganizations = isSuperAdmin(currentUser)
    const displayRole = getDisplayRole(currentUser)
    const isChatRoute = location.pathname === '/chat'
    const isKnowledgeRoute =
        location.pathname === '/knowledge'
        || location.pathname.startsWith('/knowledge/')
    const isViewportWorkspaceRoute =
        isKnowledgeRoute
        || [
            '/chat',
            '/admin/users',
            '/admin/organizations',
            '/admin/audit',
            '/admin/usage',
            '/my/erasure-requests',
            '/admin/erasure/requests',
        ].includes(location.pathname)
    const appClassName = [
        'app',
        isViewportWorkspaceRoute ? 'app--workspace' : '',
        isChatRoute ? 'app--chat' : '',
    ].filter(Boolean).join(' ')
    const contentClassName = [
        'content',
        isViewportWorkspaceRoute ? 'content--workspace' : '',
        isChatRoute ? 'content--chat' : '',
    ].filter(Boolean).join(' ')

    async function handleLogout() {
        if (logoutPending) {
            return
        }

        setLogoutPending(true)

        try {
            await logoutUser()
        } catch {
            // AuthContext очищает локальную сессию в finally.
        } finally {
            setLogoutPending(false)
            void navigate('/login', { replace: true })
        }
    }

    return (
        <div className={appClassName}>
            <a
                className="skip-link"
                href="#main-content"
                onClick={event => {
                    event.preventDefault()
                    setMenuLocation(null)
                    mainRef.current?.focus()
                }}
            >
                Перейти к содержимому
            </a>
            {isAuthenticated && (
                <header className="topbar" data-menu-open={menuOpen}>
                    <NavLink
                        to={currentUser
                            ? getDefaultAuthenticatedRoute(currentUser)
                            : '/'}
                        className="logo"
                        aria-label="SafeAI Desk — на главную"
                        title="На главную"
                        onClick={closeMenuOnNavigate}
                    >
                        <span className="logo__mark" aria-hidden="true">S</span>
                        <span>SafeAI Desk</span>
                    </NavLink>

                    <button
                        ref={menuButtonRef}
                        className="topbar-menu-toggle"
                        type="button"
                        aria-expanded={menuOpen}
                        aria-controls="main-navigation account-controls"
                        onClick={() => setMenuLocation(menuOpen ? null : location.key)}
                    >
                        <span aria-hidden="true" className="topbar-menu-toggle__icon" />
                        Меню
                    </button>

                    <nav id="main-navigation" aria-label="Основная навигация">
                        {canAccessChat && (
                            <NavLink
                                to="/chat"
                                className={getNavLinkClass}
                                onClick={closeMenuOnNavigate}
                            >
                                Чат
                            </NavLink>
                        )}

                        {canAccessChat && (
                            <NavLink to="/my/erasure-requests" className={getNavLinkClass}
                                onClick={closeMenuOnNavigate}>
                                Мои запросы
                            </NavLink>
                        )}

                        {isAuthenticated && (
                            <NavLink to="/settings/privacy" className={getNavLinkClass}
                                onClick={closeMenuOnNavigate}>
                                Приватность
                            </NavLink>
                        )}

                        {canAccessKnowledge && (
                            <NavLink
                                to="/knowledge"
                                className={getNavLinkClass}
                                onClick={closeMenuOnNavigate}
                            >
                                Базы знаний
                            </NavLink>
                        )}

                        {canAccessAdmin && (
                            <>
                                {canAccessOrganizations && (
                                    <NavLink
                                        to="/admin/dashboard"
                                        className={getNavLinkClass}
                                        onClick={closeMenuOnNavigate}
                                    >
                                        Обзор
                                    </NavLink>
                                )}
                                <NavLink
                                    to="/admin/users"
                                    className={getNavLinkClass}
                                    onClick={closeMenuOnNavigate}
                                >
                                    Пользователи
                                </NavLink>

                                {canAccessOrganizations && (
                                    <NavLink
                                        to="/admin/organizations"
                                        className={getNavLinkClass}
                                        onClick={closeMenuOnNavigate}
                                    >
                                        Организации
                                    </NavLink>
                                )}

                                <NavLink
                                    to="/admin/audit"
                                    className={getNavLinkClass}
                                    onClick={closeMenuOnNavigate}
                                >
                                    Аудит
                                </NavLink>

                                <NavLink
                                    to="/admin/usage"
                                    className={getNavLinkClass}
                                    onClick={closeMenuOnNavigate}
                                >
                                    Использование
                                </NavLink>

                                {hasAnyRole(currentUser, ['ADMIN']) && (
                                    <NavLink to="/admin/erasure/requests"
                                        className={getNavLinkClass}
                                        onClick={closeMenuOnNavigate}>
                                        Запросы на удаление
                                    </NavLink>
                                )}

                                {canAccessOrganizations && (
                                    <NavLink to="/admin/erasure" className={getNavLinkClass}
                                        onClick={closeMenuOnNavigate}>
                                        Центр удаления
                                    </NavLink>
                                )}

                                <NavLink
                                    to="/admin/models"
                                    className={getNavLinkClass}
                                    onClick={closeMenuOnNavigate}
                                >
                                    Модели
                                </NavLink>
                            </>
                        )}
                    </nav>

                    <div className="topbar-user" id="account-controls">
                        {currentUser && displayRole && (
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
                                    {roleLabel(displayRole)}
                                </span>
                            </div>
                        )}

                        <button
                            type="button"
                            disabled={logoutPending}
                            onClick={() => void handleLogout()}
                        >
                            {logoutPending
                                ? 'Выход...'
                                : 'Выйти'}
                        </button>
                    </div>
                </header>
            )}

            <main id="main-content" ref={mainRef} tabIndex={-1} className={contentClassName}>
                <Suspense fallback={<RouteLoadingState />}>
                    <Routes>
                        <Route
                            path="/"
                            element={<RootRedirect />}
                        />

                        <Route
                            path="/login"
                            element={<LoginRoute />}
                        />

                        <Route
                            path="/chat"
                            element={
                                <ProtectedRoute
                                    roles={['ADMIN', 'USER']}
                                >
                                    <ChatPage />
                                </ProtectedRoute>
                            }
                        />

                        <Route path="/my/erasure-requests"
                            element={<ProtectedRoute roles={['USER','ADMIN']}>
                                {user => <ErasureRequestsPage user={user} />}
                            </ProtectedRoute>}
                        />
                        <Route path="/admin/erasure/requests"
                            element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}>
                                <ErasureReviewQueuePage />
                            </ProtectedRoute>}
                        />

                        <Route
                            path="/knowledge"
                            element={
                                <ProtectedRoute
                                    roles={['ADMIN', 'USER']}
                                >
                                    <KnowledgePage />
                                </ProtectedRoute>
                            }
                        />
                        <Route
                            path="/knowledge/:knowledgeBaseId"
                            element={<ProtectedRoute roles={['ADMIN','USER']}><KnowledgeDetailsPage /></ProtectedRoute>}
                        />

                        <Route path="/settings/privacy" element={
                            <ProtectedRoute><PrivacySettingsPage /></ProtectedRoute>
                        } />
                        <Route path="/my/erasure-requests/:requestId" element={
                            <ProtectedRoute roles={['USER', 'ADMIN']}>
                                <ErasureReviewDetailsPage />
                            </ProtectedRoute>
                        } />
                        <Route path="/admin/erasure" element={
                            <ProtectedRoute roles={['SUPER_ADMIN']}>
                                <ErasureReviewCenterPage />
                            </ProtectedRoute>
                        } />
                        <Route path="/admin/erasure/requests/:requestId" element={
                            <ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}>
                                <ErasureReviewDetailsPage reviewer />
                            </ProtectedRoute>
                        } />
                        <Route
                            path="/admin/dashboard"
                            element={<ProtectedRoute roles={['SUPER_ADMIN']}>
                                {user => <AdminDashboardPage key={user.id} />}
                            </ProtectedRoute>}
                        />
                        <Route path="/admin/retention" element={<ProtectedRoute roles={['SUPER_ADMIN']}>
                            <AdminRetentionPage />
                        </ProtectedRoute>} />
                        <Route
                            path="/admin/users"
                            element={
                                <ProtectedRoute
                                    roles={[
                                        'ADMIN',
                                        'SUPER_ADMIN',
                                    ]}
                                >
                                    {(user) => (
                                        <AdminUsersPage
                                            currentUser={user}
                                        />
                                    )}
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="/admin/organizations"
                            element={
                                <ProtectedRoute
                                    roles={['SUPER_ADMIN']}
                                >
                                    <AdminOrganizationsPage />
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="/admin/audit"
                            element={
                                <ProtectedRoute
                                    roles={[
                                        'ADMIN',
                                        'SUPER_ADMIN',
                                    ]}
                                >
                                    <AdminAuditPage />
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="/admin/usage"
                            element={
                                <ProtectedRoute
                                    roles={[
                                        'ADMIN',
                                        'SUPER_ADMIN',
                                    ]}
                                >
                                    <AdminUsagePage />
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="/admin/models"
                            element={
                                <ProtectedRoute
                                    roles={[
                                        'ADMIN',
                                        'SUPER_ADMIN',
                                    ]}
                                >
                                    <AdminModelsPage />
                                </ProtectedRoute>
                            }
                        />

                        <Route
                            path="*"
                            element={<NotFoundRoute />}
                        />
                    </Routes>
                </Suspense>
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
