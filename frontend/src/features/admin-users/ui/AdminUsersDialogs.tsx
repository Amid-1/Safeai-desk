// frontend/src/features/admin-users/ui/AdminUsersDialogs.tsx
// ============================================================
// frontend/src/features/admin-users/ui/AdminUsersDialogs.tsx
// ============================================================
import type { AdminUsersDialogsProps } from './AdminUsersDialogs.types'
export type { AdminUsersDialogsProps } from './AdminUsersDialogs.types'
import { CreateUserDialog } from './dialogs/CreateUserDialog'
import { UserDetailsDialog } from './dialogs/UserDetailsDialog'
import { EditUserDialog } from './dialogs/EditUserDialog'
import { UserRolesDialog } from './dialogs/UserRolesDialog'
import { ResetUserPasswordDialog } from './dialogs/ResetUserPasswordDialog'
import { DeleteUserDialog } from './dialogs/DeleteUserDialog'
import { ConfirmUserEnabledDialog } from './dialogs/ConfirmUserEnabledDialog'

export function AdminUsersDialogs(props: AdminUsersDialogsProps) {
    return (
        <>
            <CreateUserDialog {...props} />
            <UserDetailsDialog {...props} />
            <EditUserDialog {...props} />
            <UserRolesDialog {...props} />
            <ResetUserPasswordDialog {...props} />
            <DeleteUserDialog {...props} />
            <ConfirmUserEnabledDialog {...props} />
        </>
    )
}
