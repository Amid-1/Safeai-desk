import { Link, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import LoginPage from './pages/LoginPage'
import ChatPage from './pages/ChatPage'
import AdminUsersPage from './pages/AdminUsersPage'
import AdminAuditPage from './pages/AdminAuditPage'
import AdminUsagePage from './pages/AdminUsagePage'
import { clearToken, getToken } from './api/http'

function App() {
    const navigate = useNavigate()
    const token = getToken()

    function logout() {
        clearToken()
        navigate('/login')
    }

    return (
        <div className="app">
            {token && (
                <header className="topbar">
                    <div className="logo">SafeAI Desk</div>

                    <nav>
                        <Link to="/chat">Chat</Link>
                        <Link to="/admin/users">Users</Link>
                        <Link to="/admin/audit">Audit</Link>
                        <Link to="/admin/usage">Usage</Link>
                    </nav>

                    <button onClick={logout}>Logout</button>
                </header>
            )}

            <main className="content">
                <Routes>
                    <Route path="/" element={<Navigate to={token ? '/chat' : '/login'} />} />
                    <Route path="/login" element={<LoginPage />} />
                    <Route path="/chat" element={<ChatPage />} />
                    <Route path="/admin/users" element={<AdminUsersPage />} />
                    <Route path="/admin/audit" element={<AdminAuditPage />} />
                    <Route path="/admin/usage" element={<AdminUsagePage />} />
                    <Route path="*" element={<Navigate to="/" />} />
                </Routes>
            </main>
        </div>
    )
}

export default App