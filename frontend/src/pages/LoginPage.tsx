// frontend/src/pages/LoginPage.tsx
import {
    useEffect,
    useMemo,
    useRef,
    useState,
} from 'react'
import type {
    KeyboardEvent,
    SyntheticEvent,
} from 'react'
import {
    ApiError,
    getApiErrorMessage,
} from '../api/http'
import { useAuth } from '../auth/useAuth'
import {
    ErrorState,
    LoadingState,
} from '../components/StateBlock'

type InvalidField =
    | 'email'
    | 'password'
    | 'credentials'
    | null

function LoginPage() {
    const {
        authStatus,
        authError,
        loginUser,
        logoutUser,
    } = useAuth()

    const emailInputRef =
        useRef<HTMLInputElement | null>(null)
    const passwordInputRef =
        useRef<HTMLInputElement | null>(null)
    const submitInFlightRef = useRef(false)

    const [email, setEmail] = useState('')
    const [password, setPassword] =
        useState('')
    const [error, setError] = useState('')
    const [invalidField, setInvalidField] =
        useState<InvalidField>(null)
    const [loading, setLoading] =
        useState(false)
    const [retryUntil, setRetryUntil] =
        useState<number | null>(null)
    const [now, setNow] = useState(
        0,
    )

    useEffect(() => {
        if (retryUntil === null) {
            return
        }

        const intervalId = window.setInterval(
            () => {
                const currentTime = Date.now()
                setNow(currentTime)
                if (currentTime >= retryUntil) {
                    window.clearInterval(intervalId)
                    setRetryUntil(null)
                }
            },
            1_000,
        )

        return () => {
            window.clearInterval(intervalId)
        }
    }, [retryUntil])

    const retryAfterSeconds = useMemo(() => {
        if (retryUntil === null) {
            return 0
        }

        return Math.max(
            0,
            Math.ceil(
                (retryUntil - now) / 1_000,
            ),
        )
    }, [
        retryUntil,
        now,
    ])

    async function handleSubmit(
        event: SyntheticEvent<HTMLFormElement>,
    ): Promise<void> {
        event.preventDefault()

        if (
            submitInFlightRef.current
            || loading
            || retryAfterSeconds > 0
        ) {
            return
        }

        setError('')
        setInvalidField(null)

        const normalizedEmail = email.trim()

        if (!normalizedEmail) {
            setInvalidField('email')
            setError('Введите email.')
            emailInputRef.current?.focus()
            return
        }

        if (!password) {
            setInvalidField('password')
            setError('Введите пароль.')
            passwordInputRef.current?.focus()
            return
        }

        submitInFlightRef.current = true
        setLoading(true)

        try {
            await loginUser({
                email: normalizedEmail,
                password,
            })
        } catch (loginError) {
            if (
                loginError instanceof ApiError
                && loginError.status === 401
            ) {
                setPassword('')
                setInvalidField('credentials')
                setError(
                    'Неверный email или пароль.',
                )

                window.requestAnimationFrame(
                    () => {
                        passwordInputRef.current
                            ?.focus()
                    },
                )
                return
            }

            if (
                loginError instanceof ApiError
                && loginError.status === 429
                && loginError.retryAfterSeconds
            ) {
                const currentTime = Date.now()

                setRetryUntil(
                    currentTime
                    + loginError.retryAfterSeconds
                    * 1_000,
                )
                setNow(currentTime)
                setError(
                    'Слишком много попыток входа.',
                )
                return
            }

            setError(
                getApiErrorMessage(
                    loginError,
                    'Не удалось выполнить вход.',
                ),
            )
        } finally {
            submitInFlightRef.current = false
            setLoading(false)
        }
    }

    async function retryLogout(): Promise<void> {
        try {
            await logoutUser()
        } catch {
            // Ошибка уже сохранена в AuthContext.
        }
    }

    function preventSubmitOnRetry(
        event: KeyboardEvent<HTMLButtonElement>,
    ): void {
        if (
            retryAfterSeconds > 0
            && (
                event.key === 'Enter'
                || event.key === ' '
            )
        ) {
            event.preventDefault()
        }
    }

    if (authStatus === 'loading') {
        return (
            <div className="page narrow-page">
                <LoadingState
                    message="Проверка доступа..."
                />
            </div>
        )
    }

    if (authStatus === 'logout-unconfirmed') {
        return (
            <div className="page narrow-page">
                <ErrorState
                    title="Выход не подтверждён"
                    message={
                        authError
                        ?? (
                            'Локальные данные скрыты, '
                            + 'но сервер не подтвердил выход.'
                        )
                    }
                    action={
                        <button
                            type="button"
                            onClick={() =>
                                void retryLogout()
                            }
                        >
                            Повторить выход
                        </button>
                    }
                />
            </div>
        )
    }

    const hasError = Boolean(error)
    const emailInvalid =
        invalidField === 'email'
        || invalidField === 'credentials'
    const passwordInvalid =
        invalidField === 'password'
        || invalidField === 'credentials'

    return (
        <div className="page narrow-page">
            <h1>Вход в SafeAI Desk</h1>

            <form
                className="card form"
                onSubmit={handleSubmit}
                noValidate
            >
                <label htmlFor="login-email">
                    Email
                </label>

                <input
                    ref={emailInputRef}
                    id="login-email"
                    value={email}
                    onChange={(event) =>
                        setEmail(
                            event.target.value,
                        )
                    }
                    type="email"
                    inputMode="email"
                    autoComplete="username"
                    maxLength={255}
                    required
                    disabled={loading}
                    aria-invalid={emailInvalid}
                    aria-describedby={
                        hasError
                            ? 'login-error'
                            : undefined
                    }
                    autoFocus
                />

                <label htmlFor="login-password">
                    Пароль
                </label>

                <input
                    ref={passwordInputRef}
                    id="login-password"
                    type="password"
                    value={password}
                    onChange={(event) =>
                        setPassword(
                            event.target.value,
                        )
                    }
                    autoComplete="current-password"
                    maxLength={100}
                    required
                    disabled={loading}
                    aria-invalid={
                        passwordInvalid
                    }
                    aria-describedby={
                        hasError
                            ? 'login-error'
                            : undefined
                    }
                />

                {hasError && (
                    <div
                        id="login-error"
                        className="error"
                        role="alert"
                        aria-live="assertive"
                    >
                        {error}

                        {retryAfterSeconds > 0 && (
                            <>
                                {' '}
                                Повторите через{' '}
                                {formatDuration(
                                    retryAfterSeconds,
                                )}.
                            </>
                        )}
                    </div>
                )}

                <button
                    type="submit"
                    disabled={
                        loading
                        || retryAfterSeconds > 0
                    }
                    onKeyDown={
                        preventSubmitOnRetry
                    }
                >
                    {loading
                        ? 'Вход...'
                        : 'Войти'}
                </button>
            </form>
        </div>
    )
}

function formatDuration(
    totalSeconds: number,
): string {
    const minutes = Math.floor(
        totalSeconds / 60,
    )
    const seconds = totalSeconds % 60

    if (minutes === 0) {
        return `${seconds} сек.`
    }

    return `${minutes} мин. ${seconds} сек.`
}

export default LoginPage
