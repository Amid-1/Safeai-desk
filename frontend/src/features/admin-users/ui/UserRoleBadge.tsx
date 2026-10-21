// frontend/src/features/admin-users/ui/UserRoleBadge.tsx
// ============================================================
// frontend/src/features/admin-users/ui/UserRoleBadge.tsx
// ============================================================
import type { UserRole } from '../../../shared/api/types'

type UserRoleBadgeProps = {
    role: UserRole
}

const ROLE_LABELS: Record<UserRole, string> = {
    USER: 'Пользователь',
    ADMIN: 'Администратор',
    SUPER_ADMIN: 'Суперадминистратор',
}

function UserRoleBadge({
                           role,
                       }: UserRoleBadgeProps) {
    return (
        <span
            className={`role-chip role-chip--${role.toLowerCase()}`}
        >
            <span
                className="role-chip__dot"
                aria-hidden="true"
            />

            {ROLE_LABELS[role]}
        </span>
    )
}

export default UserRoleBadge