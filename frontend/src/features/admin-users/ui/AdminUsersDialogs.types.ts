// frontend/src/features/admin-users/ui/AdminUsersDialogs.types.ts
// ============================================================
// frontend/src/features/admin-users/ui/AdminUsersDialogs.types.ts
// ============================================================
import type { User, UserDetails } from '../../../entities/user/api/userApi'
import type { OrganizationDirectoryItem } from '../../../entities/organization/api/organizationApi'
import type { AssignableRole } from '../lib/adminUsersSupport'

export type ConfirmState = {
    user: User
    nextEnabled: boolean
} | null

export type AdminUsersDialogsProps = {
    createModalOpen: boolean
    createError: string
    email: string
    password: string
    passwordConfirm: string
    fullName: string
    createRole: AssignableRole
    currentUserIsSuperAdmin: boolean
    organizationsLoading: boolean
    organizations: OrganizationDirectoryItem[]
    selectedOrganizationId: string
    creating: boolean
    detailsUser: UserDetails | null
    detailsError: string
    editUser: User | null
    editVersionConflict: boolean
    refreshEditedUser: () => void | Promise<void>
    editEmail: string
    editFullName: string
    rolesUser: User | null
    selectedRole: AssignableRole
    adminElevationConfirmed: boolean
    resetPasswordUser: User | null
    resetPasswordValue: string
    resetPasswordConfirm: string
    deleteUser: User | null
    deleteConfirmationEmail: string
    modalError: string
    hasPendingMutation: boolean
    confirmState: ConfirmState
    setEmail:
        (value: string) => void
    setPassword:
        (value: string) => void
    setPasswordConfirm:
        (value: string) => void
    setFullName:
        (value: string) => void
    setCreateRole:
        (value: AssignableRole) => void
    setSelectedOrganizationId:
        (value: string) => void
    setEditEmail:
        (value: string) => void
    setEditFullName:
        (value: string) => void
    setSelectedRole:
        (value: AssignableRole) => void
    setAdminElevationConfirmed:
        (value: boolean) => void
    setResetPasswordValue:
        (value: string) => void
    setResetPasswordConfirm:
        (value: string) => void
    setDeleteConfirmationEmail:
        (value: string) => void
    setConfirmState:
        (value: ConfirmState) => void
    handleCreateUser:
        () => void | Promise<void>
    closeCreateModal: () => void
    closeDetailsModal: () => void
    closeMutationModals: () => void
    submitEditUser:
        () => void | Promise<void>
    submitRoles:
        () => void | Promise<void>
    submitResetPassword:
        () => void | Promise<void>
    submitPermanentDelete:
        () => void | Promise<void>
    confirmEnabledChange:
        () => void | Promise<void>
}
