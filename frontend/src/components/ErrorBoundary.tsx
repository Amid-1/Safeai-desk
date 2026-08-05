// frontend/src/components/ErrorBoundary.tsx
import {
    Component,
} from 'react'

import type {
    ErrorInfo,
    ReactNode,
} from 'react'

import {
    reportReactRenderError,
} from '../utils/frontendErrorReporting'

type ErrorBoundaryProps = {
    children: ReactNode
    variant?: 'root' | 'page'
    resetKey?: string | number
    onReset?: () => void
}

type ErrorBoundaryState = {
    hasError: boolean
    incidentId: string | null
}

class ErrorBoundary extends Component<
    ErrorBoundaryProps,
    ErrorBoundaryState
> {
    state: ErrorBoundaryState = {
        hasError: false,
        incidentId: null,
    }

    static getDerivedStateFromError(): ErrorBoundaryState {
        return {
            hasError: true,
            incidentId: null,
        }
    }

    componentDidCatch(
        error: Error,
        errorInfo: ErrorInfo,
    ): void {
        const incidentId =
            reportReactRenderError(
                error,
                errorInfo,
            )

        this.setState({
            incidentId,
        })
    }

    componentDidUpdate(
        previousProps: ErrorBoundaryProps,
    ): void {
        if (
            this.state.hasError
            && previousProps.resetKey
                !== this.props.resetKey
        ) {
            this.resetBoundary()
        }
    }

    private resetBoundary = (): void => {
        this.setState({
            hasError: false,
            incidentId: null,
        })

        this.props.onReset?.()
    }

    private reloadApplication = (): void => {
        window.location.reload()
    }

    render(): ReactNode {
        if (!this.state.hasError) {
            return this.props.children
        }

        const variant =
            this.props.variant ?? 'root'

        const fallback = (
            <div className="card">
                <h1>
                    Произошла ошибка интерфейса
                </h1>

                <div
                    className="error"
                    role="alert"
                    aria-live="assertive"
                >
                    Не удалось отобразить этот раздел.
                    Технические сведения об ошибке скрыты.
                </div>

                {this.state.incidentId && (
                    <p className="muted">
                        Код инцидента:
                        {' '}
                        <code>
                            {this.state.incidentId}
                        </code>
                    </p>
                )}

                <div className="modal-actions">
                    {variant === 'page' && (
                        <button
                            type="button"
                            className="secondary-button"
                            onClick={
                                this.resetBoundary
                            }
                        >
                            Повторить отображение
                        </button>
                    )}

                    <button
                        type="button"
                        onClick={
                            this.reloadApplication
                        }
                    >
                        Перезагрузить страницу
                    </button>
                </div>
            </div>
        )

        if (variant === 'page') {
            return fallback
        }

        return (
            <div className="page">
                {fallback}
            </div>
        )
    }
}

export default ErrorBoundary