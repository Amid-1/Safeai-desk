// ============================================================
// frontend/src/components/admin/StateBlock.tsx
// ============================================================

import type { ReactNode } from 'react'

type LoadingStateProps = {
    message?: string
}

type StateBlockProps = {
    title?: string
    message: string
    action?: ReactNode
}

export function LoadingState({
                                 message = 'Загрузка...',
                             }: LoadingStateProps) {
    return (
        <div className="card" role="status" aria-live="polite">
            <p className="muted">{message}</p>
        </div>
    )
}

export function ErrorState({
                               title = 'Ошибка',
                               message,
                               action,
                           }: StateBlockProps) {
    return (
        <div className="card" role="alert">
            <h2>{title}</h2>
            <div className="error">{message}</div>
            {action}
        </div>
    )
}

export function EmptyState({
                               title = 'Нет данных',
                               message,
                               action,
                           }: StateBlockProps) {
    return (
        <div className="card">
            <h2>{title}</h2>
            <p className="muted">{message}</p>
            {action}
        </div>
    )
}

