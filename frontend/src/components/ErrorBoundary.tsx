// ============================================================
// frontend/src/components/admin/ErrorBoundary.tsx
// ============================================================

import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'

type ErrorBoundaryProps = {
    children: ReactNode
}

type ErrorBoundaryState = {
    hasError: boolean
}

class ErrorBoundary extends Component<
    ErrorBoundaryProps,
    ErrorBoundaryState
> {
    state: ErrorBoundaryState = {
        hasError: false,
    }

    static getDerivedStateFromError(): ErrorBoundaryState {
        return {
            hasError: true,
        }
    }

    componentDidCatch(error: Error, errorInfo: ErrorInfo) {
        console.error(
            'React error boundary caught error',
            error,
            errorInfo
        )
    }

    render() {
        if (this.state.hasError) {
            return (
                <div className="page">
                    <div className="card">
                        <h1>Произошла ошибка интерфейса</h1>

                        <div className="error">
                            Не удалось отобразить страницу. Перезагрузите приложение.
                        </div>

                        <button
                            type="button"
                            onClick={() => window.location.reload()}
                        >
                            Перезагрузить страницу
                        </button>
                    </div>
                </div>
            )
        }

        return this.props.children
    }
}

export default ErrorBoundary

