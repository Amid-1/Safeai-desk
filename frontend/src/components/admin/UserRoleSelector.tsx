// ============================================================
// frontend/src/components/admin/UserRoleSelector.tsx
// ============================================================
import type {
    ChangeEvent,
} from 'react'
import {
    parseAssignableUserRole,
} from './userRole'
import type {
    AssignableUserRole,
} from './userRole'

export type { AssignableUserRole } from './userRole'

type UserRoleSelectorProps = {
    name: string
    value: AssignableUserRole
    onChange:
        (role: AssignableUserRole) => void
    disabled?: boolean
    legend?: string
}

type FixedUserRoleProps = {
    userRole: AssignableUserRole
    title?: string
    description?: string
}

type RoleOption = {
    role: AssignableUserRole
    title: string
    description: string
}

const ROLE_OPTIONS: readonly RoleOption[] = [
    {
        role: 'USER',
        title: 'Пользователь',
        description:
            'Доступ к чатам и рабочим функциям.',
    },
    {
        role: 'ADMIN',
        title: 'Администратор',
        description:
            'Управление пользователями своей организации.',
    },
]

export function UserRoleSelector({
    name,
    value,
    onChange,
    disabled = false,
    legend = 'Роль',
}: UserRoleSelectorProps) {
    function handleChange(
        event: ChangeEvent<HTMLInputElement>,
    ) {
        const role =
            parseAssignableUserRole(
                event.target.value,
            )

        if (role) {
            onChange(role)
        }
    }

    return (
        <fieldset
            className="users-role-selector"
            disabled={disabled}
        >
            <legend>{legend}</legend>

            <div
                className="users-role-selector__options"
            >
                {ROLE_OPTIONS.map((option) => {
                    const selected =
                        option.role === value

                    return (
                        <label
                            key={option.role}
                            className={
                                getRoleOptionClassName(
                                    option.role,
                                    selected,
                                )
                            }
                        >
                            <input
                                type="radio"
                                name={name}
                                value={option.role}
                                checked={selected}
                                onChange={
                                    handleChange
                                }
                            />

                            <span
                                className={
                                    'users-role-option__marker'
                                }
                                aria-hidden="true"
                            />

                            <span
                                className={
                                    'users-role-option__content'
                                }
                            >
                                <strong>
                                    {option.title}
                                </strong>
                                <small>
                                    {
                                        option.description
                                    }
                                </small>
                            </span>
                        </label>
                    )
                })}
            </div>

            <p className="users-role-selector__hint">
                Можно назначить только одну
                системную роль.
            </p>
        </fieldset>
    )
}

export function FixedUserRole({
    userRole,
    title = 'Роль',
    description,
}: FixedUserRoleProps) {
    const option =
        ROLE_OPTIONS.find(
            (item) => item.role === userRole,
        )

    if (!option) {
        return null
    }

    return (
        <section
            className={
                `users-fixed-role users-fixed-role--${userRole.toLowerCase()}`
            }
            aria-label={title}
        >
            <span
                className="users-fixed-role__eyebrow"
            >
                {title}
            </span>

            <div
                className="users-fixed-role__row"
            >
                <span
                    className="users-fixed-role__icon"
                    aria-hidden="true"
                >
                    ✓
                </span>

                <span>
                    <strong>{option.title}</strong>
                    <small>
                        {description
                            ?? option.description}
                    </small>
                </span>
            </div>
        </section>
    )
}

function getRoleOptionClassName(
    role: AssignableUserRole,
    selected: boolean,
): string {
    return [
        'users-role-option',
        `users-role-option--${role.toLowerCase()}`,
        selected ? 'is-selected' : '',
    ]
        .filter(Boolean)
        .join(' ')
}
