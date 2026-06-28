// frontend/src/components/ErrorBoundary.tsx
import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'

type ErrorBoundaryProps = {
    children: ReactNode
}

type ErrorBoundaryState = {
    hasError: boolean
    message: string
}

class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
    state: ErrorBoundaryState = {
        hasError: false,
        message: '',
    }

    static getDerivedStateFromError(error: Error): ErrorBoundaryState {
        return {
            hasError: true,
            message: error.message,
        }
    }

    componentDidCatch(error: Error, errorInfo: ErrorInfo) {
        console.error('React error boundary caught error', error, errorInfo)
    }

    render() {
        if (this.state.hasError) {
            return (
                <div className="page">
                    <div className="card">
                        <h1>Frontend error</h1>

                        <div className="error">
                            {this.state.message || 'Unexpected frontend error'}
                        </div>

                        <button
                            type="button"
                            onClick={() => window.location.reload()}
                        >
                            Reload page
                        </button>
                    </div>
                </div>
            )
        }

        return this.props.children
    }
}

export default ErrorBoundary