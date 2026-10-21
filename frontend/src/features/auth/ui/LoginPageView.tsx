// frontend/src/features/auth/ui/LoginPageView.tsx
// Форма входа и состояния аутентификации; семантика и доступность сохранены.
import { ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import { useLoginPageController } from '../controller/useLoginPageController'

export function LoginPageView() {
    const { authStatus, authError, emailInputRef, passwordInputRef, email, setEmail,
        password, setPassword, error, invalidField, loading, retryAfterSeconds,
        handleSubmit, retryLogout, preventSubmitOnRetry } = useLoginPageController()

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
