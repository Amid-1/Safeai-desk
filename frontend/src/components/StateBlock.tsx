// frontend/src/components/StateBlock.tsx
import type { ReactNode } from 'react'

type LoadingStateProps = {
    message?: string
}

type StateBlockProps = {
    title?: string
    message: string
    action?: ReactNode
}

export function LoadingState({ message = 'Loading...' }: LoadingStateProps) {
    return (
        <div className="card">
            <p className="muted">{message}</p>
        </div>
    )
}

export function ErrorState({ title = 'Error', message, action }: StateBlockProps) {
    return (
        <div className="card">
            <h2>{title}</h2>
            <div className="error">{message}</div>
            {action}
        </div>
    )
}

export function EmptyState({ title = 'No data', message, action }: StateBlockProps) {
    return (
        <div className="card">
            <h2>{title}</h2>
            <p className="muted">{message}</p>
            {action}
        </div>
    )
}