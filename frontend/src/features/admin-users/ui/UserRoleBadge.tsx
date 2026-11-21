// frontend/src/features/admin-users/ui/UserRoleBadge.tsx
// ============================================================
// frontend/src/features/admin-users/ui/UserRoleBadge.tsx
// ============================================================
import type { UserRole } from '../../../shared/api/types'
import { roleLabel } from '../../../shared/lib/roleLabel'

type UserRoleBadgeProps = {
    role: UserRole
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

            {roleLabel(role)}
        </span>
    )
}

export default UserRoleBadge
