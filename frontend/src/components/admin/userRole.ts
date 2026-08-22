import type { UserRole } from '../../api/types'

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
