// ============================================================
// frontend/src/components/StateBlock.tsx
// ============================================================
import type {
    ReactNode,
} from 'react'

type StateBlockVariant =
    | 'card'
    | 'inline'

type StateBlockProps = {
    title?: string
    message: string
    action?: ReactNode
    variant?: StateBlockVariant
    kind: 'loading' | 'error' | 'empty'
}

type LoadingStateProps = {
    title?: string
    message?: string
    variant?: StateBlockVariant
}

type ErrorStateProps = {
    title?: string
    message: string
    action?: ReactNode
    variant?: StateBlockVariant
}

type EmptyStateProps = {
    title?: string
    message: string
    action?: ReactNode
    variant?: StateBlockVariant
}

export function LoadingState({
    title,
    message = 'Загрузка...',
    variant = 'card',
}: LoadingStateProps) {
    return (
        <StateBlock
            kind="loading"
            title={title}
            message={message}
            variant={variant}
        />
    )
}

export function ErrorState({
    title = 'Не удалось загрузить данные',
    message,
    action,
    variant = 'card',
}: ErrorStateProps) {
    return (
        <StateBlock
            kind="error"
            title={title}
            message={message}
            action={action}
            variant={variant}
        />
    )
}

export function EmptyState({
    title,
    message,
    action,
    variant = 'card',
}: EmptyStateProps) {
    return (
        <StateBlock
            kind="empty"
            title={title}
            message={message}
            action={action}
            variant={variant}
        />
    )
}

function StateBlock({
    title,
    message,
    action,
    variant = 'card',
    kind,
}: StateBlockProps) {
    const role =
        kind === 'error'
            ? 'alert'
            : 'status'

    const ariaLive =
        kind === 'error'
            ? 'assertive'
            : 'polite'

    const className = [
        'state-block',
        `state-block--${kind}`,
        `state-block--${variant}`,
        variant === 'card'
            ? 'card'
            : '',
    ]
        .filter(Boolean)
        .join(' ')

    return (
        <section
            className={className}
            role={role}
            aria-live={ariaLive}
            aria-busy={
                kind === 'loading'
                    ? true
                    : undefined
            }
        >
            {title && (
                <h2 className="state-block__title">
                    {title}
                </h2>
            )}

            <p className="state-block__message">
                {message}
            </p>

            {action && (
                <div className="state-block__action">
                    {action}
                </div>
            )}
        </section>
    )
}