import type { ReactNode } from 'react'

type FilterButtonProps = {
    active: boolean
    label: string
    count: number
    disabled: boolean
    onClick: () => void
}

export function FilterButton({
    active,
    label,
    count,
    disabled,
    onClick,
}: FilterButtonProps) {
    return (
        <button
            type="button"
            className={
                active
                    ? (
                        'users-filter-button '
                        + 'is-active'
                    )
                    : 'users-filter-button'
            }
            aria-pressed={active}
            disabled={disabled}
            onClick={onClick}
        >
            {label}
            {' '}
            <span className="users-filter-count">
                {count}
            </span>
        </button>
    )
}

type PaginationProps = {
    page: number
    totalPages: number
    disabled: boolean
    onPrevious: () => void
    onNext: () => void
}

export function Pagination({
    page,
    totalPages,
    disabled,
    onPrevious,
    onNext,
}: PaginationProps) {
    return (
        <div className="pagination">
            <button
                type="button"
                className="secondary-button"
                disabled={
                    disabled || page === 0
                }
                onClick={onPrevious}
            >
                Назад
            </button>

            <span>
                Страница
                {' '}
                {page + 1}
                {' '}
                из
                {' '}
                {Math.max(totalPages, 1)}
            </span>

            <button
                type="button"
                className="secondary-button"
                disabled={
                    disabled
                    || page + 1 >= totalPages
                }
                onClick={onNext}
            >
                Вперёд
            </button>
        </div>
    )
}

type PasswordFieldsProps = {
    password: string
    passwordConfirm: string
    onPasswordChange:
        (value: string) => void
    onPasswordConfirmChange:
        (value: string) => void
    disabled: boolean
    passwordLabel?: string
}

export function PasswordFields({
    password,
    passwordConfirm,
    onPasswordChange,
    onPasswordConfirmChange,
    disabled,
    passwordLabel = 'Пароль',
}: PasswordFieldsProps) {
    return (
        <>
            <label>
                {passwordLabel}
                <input
                    value={password}
                    onChange={(event) =>
                        onPasswordChange(
                            event.target.value,
                        )
                    }
                    type="password"
                    minLength={12}
                    autoComplete="new-password"
                    required
                    disabled={disabled}
                />
            </label>

            <label>
                Повторите пароль
                <input
                    value={passwordConfirm}
                    onChange={(event) =>
                        onPasswordConfirmChange(
                            event.target.value,
                        )
                    }
                    type="password"
                    minLength={12}
                    autoComplete="new-password"
                    required
                    disabled={disabled}
                />
            </label>

            <small className="muted">
                Максимум 72 байта UTF-8.
                Требуются ASCII: a-z, A-Z,
                цифра и спецсимвол.
            </small>
        </>
    )
}

type ModalActionsProps = {
    busy: boolean
    onCancel: () => void
    submitLabel: string
    danger?: boolean
    submitDisabled?: boolean
}

export function ModalActions({
    busy,
    onCancel,
    submitLabel,
    danger = false,
    submitDisabled = false,
}: ModalActionsProps) {
    return (
        <div className="modal-actions">
            <button
                type="button"
                className="secondary-button"
                disabled={busy}
                onClick={onCancel}
            >
                Отмена
            </button>

            <button
                type="submit"
                className={
                    danger
                        ? 'danger-button'
                        : undefined
                }
                disabled={
                    busy || submitDisabled
                }
            >
                {busy
                    ? 'Выполнение...'
                    : submitLabel}
            </button>
        </div>
    )
}

export function ModalError({
    message,
}: {
    message: string
}) {
    return message ? (
        <div
            className="error"
            role="alert"
            aria-live="assertive"
        >
            {message}
        </div>
    ) : null
}

export function UserStatusBadge({
    enabled,
}: {
    enabled: boolean
}) {
    return (
        <span
            className={
                enabled
                    ? (
                        'status-chip '
                        + 'status-chip--enabled'
                    )
                    : (
                        'status-chip '
                        + 'status-chip--disabled'
                    )
            }
        >
            <span
                className="status-chip__dot"
                aria-hidden="true"
            />

            {enabled
                ? 'Включён'
                : 'Отключён'}
        </span>
    )
}

export function Detail({
    term,
    value,
}: {
    term: string
    value: ReactNode
}) {
    return (
        <div className="user-details__row">
            <dt>{term}</dt>
            <dd>{value}</dd>
        </div>
    )
}

