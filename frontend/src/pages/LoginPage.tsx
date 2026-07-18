// frontend/src/pages/LoginPage.tsx
import { useEffect, useState } from 'react'
import type { SyntheticEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, getApiErrorMessage } from '../api/http'
import { useAuth } from '../auth/AuthContext'
import { LoadingState } from '../components/StateBlock'

function LoginPage() {
    const navigate = useNavigate()
    const { currentUser, authStatus, loginUser } = useAuth()

    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)

    useEffect(() => {
        if (authStatus === 'authenticated' && currentUser) {
            navigate('/chat', { replace: true })
        }
    }, [authStatus, currentUser, navigate])

    async function handleSubmit(
        event: SyntheticEvent<HTMLFormElement, SubmitEvent>
    ) {
        event.preventDefault()

        if (loading) {
            return
        }

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
        } catch (error) {
            if (error instanceof ApiError && error.status === 401) {
                setError('Неверный email или пароль.')
                return
            }

            setError(getApiErrorMessage(error, 'Не удалось выполнить вход.'))
        } finally {
            setLoading(false)
        }
    }

    if (authStatus === 'loading') {
        return (
            <div className="page narrow-page">
                <LoadingState message="Проверка доступа..." />
            </div>
        )
    }

    return (
        <div className="page narrow-page">
            <h1>Вход в SafeAI Desk</h1>

            <form className="card form" onSubmit={handleSubmit}>
                <label>
                    Email
                    <input
                        value={email}
                        onChange={(event) => setEmail(event.target.value)}
                        type="email"
                        autoComplete="username"
                        maxLength={255}
                        required
                        disabled={loading}
                    />
                </label>

                <label>
                    Пароль
                    <input
                        type="password"
                        value={password}
                        onChange={(event) => setPassword(event.target.value)}
                        autoComplete="current-password"
                        maxLength={100}
                        required
                        disabled={loading}
                    />
                </label>

                {error && <div className="error">{error}</div>}

                <button disabled={loading || !email.trim() || !password}>
                    {loading ? 'Вход...' : 'Войти'}
                </button>
            </form>
        </div>
    )
}

export default LoginPage
