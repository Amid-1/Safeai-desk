// frontend/src/features/admin-users/ui/AdminUsersQuickActions.tsx
import type {
    UserRole,
} from '../../../shared/api/types'
import UserRoleBadge
    from './UserRoleBadge'
import {
    UserStatusBadge,
} from './AdminUsersUi'

type UserRoleQuickActionProps = {
    role: UserRole
    email: string
    editable: boolean
    disabled: boolean
    onEdit: () => void
    onInfo: () => void
}

export function UserRoleQuickAction({
    role,
    email,
    editable,
    disabled,
    onEdit,
    onInfo,
}: UserRoleQuickActionProps) {
    if (!editable) {
        // An ADMIN may inspect the role policy but cannot elevate privileges.
        // Mutation is still guarded separately by SUPER_ADMIN on the server.
        return (
            <button
                type="button"
                className={
                    'users-inline-action '
                    + 'users-inline-action--role-info'
                }
                disabled={disabled}
                title="Информация о системной роли и правах на изменение"
                aria-label={
                    `Информация о роли пользователя ${email}`
                }
                aria-haspopup="dialog"
                onClick={onInfo}
            >
                <UserRoleBadge role={role} />
                <span
                    className="users-inline-action__info"
                    aria-hidden="true"
                >
                    i
                </span>
            </button>
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
            title="Изменить системную роль"
            aria-haspopup="dialog"
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
