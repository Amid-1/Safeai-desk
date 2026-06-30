// frontend/src/pages/LoginPage.tsx
import { useEffect, useState } from 'react'
import type { SyntheticEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { getApiErrorMessage } from '../api/http'
import { useAuth } from '../auth/AuthContext'

function LoginPage() {
    const navigate = useNavigate()
    const { currentUser, authLoading, loginUser } = useAuth()

    const [email, setEmail] = useState(import.meta.env.DEV ? 'admin@test.com' : '')
    const [password, setPassword] = useState('')
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)

    useEffect(() => {
        if (!authLoading && currentUser) {
            navigate('/chat', { replace: true })
        }
    }, [authLoading, currentUser, navigate])

    async function handleSubmit(event: SyntheticEvent<HTMLFormElement>) {
        event.preventDefault()

        setError('')
        setLoading(true)

        try {
            await loginUser({
                email: email.trim(),
                password,
            })

            navigate('/chat', { replace: true })
        } catch (err) {
            setError(getApiErrorMessage(err, 'Login failed'))
        } finally {
            setLoading(false)
        }
    }

    if (authLoading) {
        return (
            <div className="page narrow-page">
                <p>Checking access...</p>
            </div>
        )
    }

    return (
        <div className="page narrow-page">
            <h1>SafeAI Desk Login</h1>

            <form className="card form" onSubmit={handleSubmit}>
                <label>
                    Email
                    <input
                        value={email}
                        onChange={(event) => setEmail(event.target.value)}
                        autoComplete="username"
                    />
                </label>

                <label>
                    Password
                    <input
                        type="password"
                        value={password}
                        onChange={(event) => setPassword(event.target.value)}
                        autoComplete="current-password"
                    />
                </label>

                {error && <div className="error">{error}</div>}

                <button disabled={loading || !email.trim() || !password}>
                    {loading ? 'Logging in...' : 'Login'}
                </button>
            </form>
        </div>
    )
}

export default LoginPage