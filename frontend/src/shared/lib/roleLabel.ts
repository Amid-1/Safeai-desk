// frontend/src/shared/lib/roleLabel.ts
import type { UserRole } from '../api/types'

export function roleLabel(role: UserRole): string {
    switch (role) {
        case 'SUPER_ADMIN': return 'Суперадминистратор'
        case 'ADMIN': return 'Администратор'
        case 'USER': return 'Пользователь'
    }
}
