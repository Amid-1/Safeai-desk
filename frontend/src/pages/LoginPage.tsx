// frontend/src/pages/LoginPage.tsx
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, getApiErrorMessage } from '../api/http'
import { useAuth } from '../auth/AuthContext'
import { LoadingState } from '../components/StateBlock'

function LoginPage() {
    const navigate = useNavigate()
    const { currentUser, authLoading, loginUser } = useAuth()

    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)

    useEffect(() => {
        if (!authLoading && currentUser) {
            navigate('/chat', { replace: true })
        }
    }, [authLoading, currentUser, navigate])

    async function handleSubmit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault()

        setError('')

        const normalizedEmail = email.trim()

        if (!normalizedEmail || !password) {
            setError('Введите email и пароль.')
            return
        }

        setLoading(true)

        try {
            await loginUser({
                email: normalizedEmail,
                password,
            })

            navigate('/chat', { replace: true })
        } catch (err) {
            if (err instanceof ApiError && err.status === 401) {
                setError('Неверный email или пароль.')
                return
            }

            setError(getApiErrorMessage(err, 'Не удалось выполнить вход.'))
        } finally {
            setLoading(false)
        }
    }

    if (authLoading) {
        return (
            <div className="page narrow-page">
                <LoadingState message="Checking access..." />
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
                        type="email"
                        autoComplete="username"
                        disabled={loading}
                    />
                </label>

                <label>
                    Password
                    <input
                        type="password"
                        value={password}
                        onChange={(event) => setPassword(event.target.value)}
                        autoComplete="current-password"
                        disabled={loading}
                    />
                </label>

                {error && <div className="error">{error}</div>}

                <button disabled={loading}>
                    {loading ? 'Logging in...' : 'Login'}
                </button>
            </form>
        </div>
    )
}

export default LoginPage