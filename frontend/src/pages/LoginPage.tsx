// frontend/src/pages/LoginPage.tsx
import { useState } from 'react'
import type { SyntheticEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { getApiErrorMessage } from '../api/http'
import { useAuth } from '../auth/AuthContext'

function LoginPage() {
    const navigate = useNavigate()
    const { loginUser } = useAuth()

    const [email, setEmail] = useState('admin@test.com')
    const [password, setPassword] = useState('admin123')
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)

    async function handleSubmit(event: SyntheticEvent<HTMLFormElement>) {
        event.preventDefault()

        setError('')
        setLoading(true)

        try {
            await loginUser({
                email: email.trim(),
                password,
            })

            navigate('/chat')
        } catch (err) {
            setError(getApiErrorMessage(err, 'Login failed'))
        } finally {
            setLoading(false)
        }
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
                    />
                </label>

                <label>
                    Password
                    <input
                        type="password"
                        value={password}
                        onChange={(event) => setPassword(event.target.value)}
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