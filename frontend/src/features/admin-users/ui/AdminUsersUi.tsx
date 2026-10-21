// frontend/src/features/admin-users/ui/AdminUsersUi.tsx
import {useId, useState} from 'react'
// frontend/src/features/admin-users/ui/AdminUsersUi.tsx
import type {
    ReactNode,
} from 'react'

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

            <span
                className={
                    'users-filter-count'
                }
            >
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
        <div
            className="pagination"
        >
            <button
                type="button"
                className={
                    'secondary-button'
                }
                disabled={
                    disabled
                    || page === 0
                }
                onClick={
                    onPrevious
                }
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
                {Math.max(
                    totalPages,
                    1,
                )}
            </span>

            <button
                type="button"
                className={
                    'secondary-button'
                }
                disabled={
                    disabled
                    || page + 1
                        >= totalPages
                }
                onClick={
                    onNext
                }
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
    const id = useId()
    const [showPassword, setShowPassword] = useState(false)
    const [showConfirm, setShowConfirm] = useState(false)
    const bytes = new TextEncoder().encode(password).length
    const mismatch = passwordConfirm.length > 0 && password !== passwordConfirm
    return (
        <>
            <div className="users-password-field">
                <label htmlFor={`${id}-password`}>{passwordLabel}</label>
                <span className="users-password-input">
                    <input
                        id={`${id}-password`}
                        value={password}
                        onChange={(event) => onPasswordChange(event.target.value)}
                        type={showPassword ? 'text' : 'password'}
                        minLength={12}
                        autoComplete="new-password"
                        required
                        disabled={disabled}
                        aria-describedby={`${id}-byte-count`}
                    />
                    <button
                        type="button"
                        disabled={disabled}
                        aria-label={showPassword ? 'Скрыть пароль' : 'Показать пароль'}
                        aria-pressed={showPassword}
                        onClick={() => setShowPassword((shown) => !shown)}
                    >
                        {showPassword ? 'Скрыть' : 'Показать'}
                    </button>
                </span>
            </div>
            <span
                id={`${id}-byte-count`}
                className={bytes > 72
                    ? 'users-password-feedback users-password-feedback--error'
                    : 'users-password-feedback'}
                role="status"
            >
                Длина пароля: {bytes} из 72 байт UTF-8
                {bytes > 72 ? ' — превышен допустимый предел' : ''}
            </span>
            <div className="users-password-field">
                <label htmlFor={`${id}-confirmation`}>Повторите пароль</label>
                <span className="users-password-input">
                    <input
                        id={`${id}-confirmation`}
                        value={passwordConfirm}
                        onChange={(event) => onPasswordConfirmChange(event.target.value)}
                        type={showConfirm ? 'text' : 'password'}
                        minLength={12}
                        autoComplete="new-password"
                        required
                        disabled={disabled}
                        aria-invalid={mismatch}
                        aria-describedby={mismatch ? `${id}-mismatch` : undefined}
                    />
                    <button
                        type="button"
                        disabled={disabled}
                        aria-label={showConfirm ? 'Скрыть подтверждение' : 'Показать подтверждение'}
                        aria-pressed={showConfirm}
                        onClick={() => setShowConfirm((shown) => !shown)}
                    >
                        {showConfirm ? 'Скрыть' : 'Показать'}
                    </button>
                </span>
            </div>
            {mismatch && (
                <span
                    id={`${id}-mismatch`}
                    className="users-password-feedback users-password-feedback--error"
                    role="alert"
                >
                    Пароли не совпадают.
                </span>
            )}
            <small className="muted">
                Минимум 12 символов: строчная и заглавная буква,
                цифра и спецсимвол. Не более 72 байт UTF-8;
                управляющие символы запрещены.
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
        <div
            className={
                'modal-actions'
            }
        >
            <button
                type="button"
                className={
                    'secondary-button'
                }
                disabled={
                    busy
                }
                onClick={
                    onCancel
                }
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
                    busy
                    || submitDisabled
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
    return message
        ? (
            <div
                className="error"
                role="alert"
                aria-live="assertive"
            >
                {message}
            </div>
        )
        : null
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
                className={
                    'status-chip__dot'
                }
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
        <div
            className={
                'user-details__row'
            }
        >
            <dt>
                {term}
            </dt>

            <dd>
                {value}
            </dd>
        </div>
    )
}
