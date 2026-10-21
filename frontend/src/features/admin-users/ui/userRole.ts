// frontend/src/features/admin-users/ui/userRole.ts
import type { UserRole } from '../../../shared/api/types'

export type AssignableUserRole = Exclude<
    UserRole,
    'SUPER_ADMIN'
>

export function parseAssignableUserRole(
    value: string,
): AssignableUserRole | null {
    if (value === 'USER' || value === 'ADMIN') {
        return value
    }

    return null
}
