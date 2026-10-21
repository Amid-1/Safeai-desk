// frontend/src/features/admin-users/controller/mutationTypes.ts
// ============================================================
// frontend/src/features/admin-users/controller/mutationTypes.ts
// ============================================================
import type { Dispatch, RefObject, SetStateAction } from 'react'
import type { AuthUser } from '../../auth/api/authApi'
import type { User } from '../../../entities/user/api/userApi'
import type { OrganizationDirectoryItem } from '../../../entities/organization/api/organizationApi'
import type { AssignableRole } from '../lib/adminUsersSupport'

export type WorkingMutation = {
    userId: string
    type: 'EDIT' | 'ROLES' | 'RESET_PASSWORD' | 'ENABLED' | 'DELETE'
}
export type PendingMutation = WorkingMutation | null
export type AdminUsersConfirmState = {
    user: User
    nextEnabled: boolean
} | null

/** Единый тип зависимостей операций; используется только внутри страницы. */
export type MutationContext = {
    currentUser: AuthUser
    currentUserIsSuperAdmin: boolean
    organizations: OrganizationDirectoryItem[]
    requestReloadFromFirstPage: () => void
    pendingMutationRef: RefObject<PendingMutation>
    setPendingMutation: Dispatch<SetStateAction<PendingMutation>>
    setMutationError: Dispatch<SetStateAction<string>>
    setModalError: Dispatch<SetStateAction<string>>
    setSuccess: Dispatch<SetStateAction<string>>
    editUser: User | null
    setEditUser: Dispatch<SetStateAction<User | null>>
    editVersionConflict: boolean
    setEditVersionConflict: Dispatch<SetStateAction<boolean>>
    editEmail: string
    setEditEmail: Dispatch<SetStateAction<string>>
    editFullName: string
    setEditFullName: Dispatch<SetStateAction<string>>
    rolesUser: User | null
    setRolesUser: Dispatch<SetStateAction<User | null>>
    selectedRole: AssignableRole
    setSelectedRole: Dispatch<SetStateAction<AssignableRole>>
    adminElevationConfirmed: boolean
    setAdminElevationConfirmed: Dispatch<SetStateAction<boolean>>
    resetPasswordUser: User | null
    setResetPasswordUser: Dispatch<SetStateAction<User | null>>
    resetPasswordValue: string
    setResetPasswordValue: Dispatch<SetStateAction<string>>
    resetPasswordConfirm: string
    setResetPasswordConfirm: Dispatch<SetStateAction<string>>
    confirmState: AdminUsersConfirmState
    setConfirmState: Dispatch<SetStateAction<AdminUsersConfirmState>>
    deleteUser: User | null
    setDeleteUser: Dispatch<SetStateAction<User | null>>
    deleteConfirmationEmail: string
    setDeleteConfirmationEmail: Dispatch<SetStateAction<string>>
    clearMutationSensitiveFields: () => void
    closeMutationModals: () => void
    ensureVersionAvailable: (user: User) => boolean
    requireVersion: (user: User) => number | null
    canManageUser: (user: User) => boolean
    canPermanentlyDelete: (user: User) => boolean
    runUserMutation: (
        mutation: WorkingMutation,
        fallbackError: string,
        action: () => Promise<void>,
        modal?: boolean,
    ) => Promise<void>
}
