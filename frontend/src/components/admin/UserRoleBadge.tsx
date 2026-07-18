// ============================================================
// frontend/src/components/admin/UserRoleBadge.tsx
// ============================================================
import type { UserRole } from '../../api/types'

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