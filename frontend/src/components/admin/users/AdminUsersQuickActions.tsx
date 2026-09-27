// frontend/src/components/admin/users/AdminUsersQuickActions.tsx
import type {
    UserRole,
} from '../../../api/types'
import UserRoleBadge
    from '../UserRoleBadge'
import {
    UserStatusBadge,
} from './AdminUsersUi'

type UserRoleQuickActionProps = {
    role: UserRole
    email: string
    editable: boolean
    disabled: boolean
    onEdit: () => void
}

export function UserRoleQuickAction({
    role,
    email,
    editable,
    disabled,
    onEdit,
}: UserRoleQuickActionProps) {
    if (!editable) {
        return (
            <UserRoleBadge
                role={role}
            />
        )
    }

    return (
        <button
            type="button"
            className={
                'users-inline-action '
                + 'users-inline-action--role'
            }
            disabled={disabled}
            title="Изменить роль"
            aria-label={
                `Изменить роль пользователя ${email}`
            }
            onClick={onEdit}
        >
            <UserRoleBadge
                role={role}
            />

            <span
                className={
                    'users-inline-action__edit'
                }
                aria-hidden="true"
            >
                ✎
            </span>
        </button>
    )
}

type UserStatusQuickActionProps = {
    enabled: boolean
    email: string
    editable: boolean
    disabled: boolean
    onToggle: () => void
}

export function UserStatusQuickAction({
    enabled,
    email,
    editable,
    disabled,
    onToggle,
}: UserStatusQuickActionProps) {
    if (!editable) {
        return (
            <UserStatusBadge
                enabled={enabled}
            />
        )
    }

    const action =
        enabled
            ? 'Отключить'
            : 'Включить'

    return (
        <button
            type="button"
            className={
                'users-inline-action '
                + 'users-inline-action--status'
            }
            disabled={disabled}
            title={
                `${action} пользователя`
            }
            aria-label={
                `${action} пользователя ${email}`
            }
            onClick={onToggle}
        >
            <UserStatusBadge
                enabled={enabled}
            />

            <span
                className={
                    'users-inline-action__edit'
                }
                aria-hidden="true"
            >
                ✎
            </span>
        </button>
    )
}
