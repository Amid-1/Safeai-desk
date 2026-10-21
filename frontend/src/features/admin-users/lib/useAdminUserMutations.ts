// frontend/src/features/admin-users/lib/useAdminUserMutations.ts
// ============================================================
// frontend/src/features/admin-users/lib/useAdminUserMutations.ts
// ============================================================
import { useRef, useState } from 'react'
import type { MutationContext, PendingMutation, WorkingMutation, AdminUsersConfirmState } from '../controller/mutationTypes'
export type { AdminUsersConfirmState } from '../controller/mutationTypes'
import { createEditMutations } from '../controller/editMutations'
import { createRoleMutations } from '../controller/roleMutations'
import { createPasswordMutations } from '../controller/passwordMutations'
import { createDeleteMutations } from '../controller/deleteMutations'
import { createEnabledMutations } from '../controller/enabledMutations'
import type {
    User,
} from '../../../entities/user/api/userApi'
import type {
    AuthUser,
} from '../../auth/api/authApi'
import {
    isProtectedOrganization,
} from '../../../entities/organization/api/organizationApi'
import type {
    OrganizationDirectoryItem,
} from '../../../entities/organization/api/organizationApi'
import {
    useAutoClearMessage,
} from '../../../shared/lib/hooks/useAutoClearMessage'
import {
    getMutationErrorMessage,
    isVersionConflict,
} from './adminUsersSupport'
import type {
    AssignableRole,
} from './adminUsersSupport'

const SUCCESS_MESSAGE_TIMEOUT_MS = 4_000

export type UseAdminUserMutationsOptions = {
    currentUser: AuthUser
    currentUserIsSuperAdmin: boolean
    organizations: OrganizationDirectoryItem[]
    requestReloadFromFirstPage: () => void
}

export function useAdminUserMutations({
    currentUser,
    currentUserIsSuperAdmin,
    organizations,
    requestReloadFromFirstPage,
}: UseAdminUserMutationsOptions) {
    const [mutationError, setMutationError] =
        useState('')
    const [modalError, setModalError] =
        useState('')
    const [success, setSuccess] =
        useState('')
    const [pendingMutation, setPendingMutation] =
        useState<PendingMutation>(null)

    const [editUser, setEditUser] =
        useState<User | null>(null)
    const [editVersionConflict, setEditVersionConflict] = useState(false)
    const [editEmail, setEditEmail] =
        useState('')
    const [editFullName, setEditFullName] =
        useState('')

    const [rolesUser, setRolesUser] =
        useState<User | null>(null)
    const [selectedRole, setSelectedRole] =
        useState<AssignableRole>('USER')
    const [
        adminElevationConfirmed,
        setAdminElevationConfirmed,
    ] = useState(false)

    const [
        resetPasswordUser,
        setResetPasswordUser,
    ] = useState<User | null>(null)
    const [
        resetPasswordValue,
        setResetPasswordValue,
    ] = useState('')
    const [
        resetPasswordConfirm,
        setResetPasswordConfirm,
    ] = useState('')

    const [confirmState, setConfirmState] =
        useState<AdminUsersConfirmState>(null)

    const [deleteUser, setDeleteUser] =
        useState<User | null>(null)
    const [
        deleteConfirmationEmail,
        setDeleteConfirmationEmail,
    ] = useState('')

    const pendingMutationRef =
        useRef<PendingMutation>(null)

    const hasPendingMutation =
        pendingMutation !== null

    // Ref устанавливается синхронно: эффект мог бы перезаписать блокировку старым состоянием.
    useAutoClearMessage(
        success,
        setSuccess,
        SUCCESS_MESSAGE_TIMEOUT_MS,
    )

    function clearMutationSensitiveFields() {
        setResetPasswordValue('')
        setResetPasswordConfirm('')
        setDeleteConfirmationEmail('')
    }

    function closeMutationModals() {
        if (pendingMutationRef.current) {
            return
        }

        clearMutationSensitiveFields()
        setEditUser(null)
        setEditVersionConflict(false)
        setRolesUser(null)
        setResetPasswordUser(null)
        setDeleteUser(null)
        setModalError('')
        setAdminElevationConfirmed(false)
    }

    function ensureVersionAvailable(
        user: User,
    ): boolean {
        if (Number.isSafeInteger(user.version) && user.version !== null && user.version >= 0) {
            return true
        }

        setMutationError(
            'Backend не вернул version пользователя. '
            + 'Mutation заблокирована fail-closed, '
            + 'пока не реализован optimistic concurrency contract.',
        )
        return false
    }

    function requireVersion(
        user: User,
    ): number | null {
        if (Number.isSafeInteger(user.version) && user.version !== null && user.version >= 0) {
            return user.version
        }

        setModalError(
            'Backend не вернул version пользователя. '
            + 'Операция заблокирована.',
        )
        return null
    }

    async function runUserMutation(
        mutation: WorkingMutation,
        fallbackError: string,
        action: () => Promise<void>,
        modal = true,
    ) {
        if (pendingMutationRef.current) {
            return
        }

        pendingMutationRef.current = mutation
        setPendingMutation(mutation)

        if (modal) {
            setModalError('')
        } else {
            setMutationError('')
        }

        try {
            await action()
        } catch (error) {
            const message =
                getMutationErrorMessage(
                    error,
                    fallbackError,
                )

            if (modal) {
                setModalError(message)
            } else {
                setMutationError(message)
            }

            if (isVersionConflict(error)) {
                if (mutation.type === 'EDIT') {
                    setEditVersionConflict(true)
                }
                requestReloadAfterMutation()
            }
        } finally {
            pendingMutationRef.current = null
            setPendingMutation(null)
        }
    }

    function canManageUser(user: User): boolean {
        const targetIsAdmin = user.roles.includes('ADMIN')
        return (currentUserIsSuperAdmin || user.organizationId === currentUser.organizationId)
            && !user.roles.includes('SUPER_ADMIN')
            && user.id !== currentUser.id
            && (!targetIsAdmin || currentUserIsSuperAdmin)
    }

    function canPermanentlyDelete(user: User): boolean {
        if (!currentUserIsSuperAdmin || !canManageUser(user)
            || user.enabled || !Number.isSafeInteger(user.version)
            || user.version === null || user.version < 0) return false
        const organization = organizations.find(item => item.id === user.organizationId)
        return Boolean(organization && !isProtectedOrganization({
            type: organization.type, protected: organization.protected,
        }))
    }

    function requestReloadAfterMutation() {
        try {
            requestReloadFromFirstPage()
        } catch {
            // Серверная мутация могла завершиться успешно; повторять POST недопустимо.
            setMutationError('Изменение выполнено, но не удалось обновить список пользователей.')
        }
    }

    // Единая контекстная граница операций — все фабрики используют один lock и одни версии.
    const context = {
        currentUser, currentUserIsSuperAdmin, organizations,
        requestReloadFromFirstPage: requestReloadAfterMutation,
        pendingMutationRef, setPendingMutation, setMutationError, setModalError, setSuccess,
        editUser, setEditUser, editVersionConflict, setEditVersionConflict,
        editEmail, setEditEmail, editFullName, setEditFullName,
        rolesUser, setRolesUser, selectedRole, setSelectedRole,
        adminElevationConfirmed, setAdminElevationConfirmed,
        resetPasswordUser, setResetPasswordUser, resetPasswordValue, setResetPasswordValue,
        resetPasswordConfirm, setResetPasswordConfirm,
        confirmState, setConfirmState, deleteUser, setDeleteUser,
        deleteConfirmationEmail, setDeleteConfirmationEmail,
        clearMutationSensitiveFields, closeMutationModals, ensureVersionAvailable,
        requireVersion, canManageUser, canPermanentlyDelete, runUserMutation,
    } satisfies MutationContext

    const { openEditModal, refreshEditedUser, submitEditUser } = createEditMutations(context)
    const { openRolesModal, submitRoles } = createRoleMutations(context)
    const { openResetPasswordModal, submitResetPassword } = createPasswordMutations(context)
    const { openDeleteModal, submitPermanentDelete } = createDeleteMutations(context)
    const { confirmEnabledChange } = createEnabledMutations(context)

    return {
        mutationError,
        modalError,
        success,
        hasPendingMutation,
        editUser,
        editVersionConflict,
        refreshEditedUser,
        editEmail,
        editFullName,
        rolesUser,
        selectedRole,
        adminElevationConfirmed,
        resetPasswordUser,
        resetPasswordValue,
        resetPasswordConfirm,
        confirmState,
        deleteUser,
        deleteConfirmationEmail,
        setEditEmail,
        setEditFullName,
        setSelectedRole,
        setAdminElevationConfirmed,
        setResetPasswordValue,
        setResetPasswordConfirm,
        setDeleteConfirmationEmail,
        setConfirmState,
        openEditModal,
        openRolesModal,
        openResetPasswordModal,
        openDeleteModal,
        closeMutationModals,
        submitEditUser,
        submitRoles,
        submitResetPassword,
        submitPermanentDelete,
        confirmEnabledChange,
        canManageUser,
        canPermanentlyDelete,
    }
}
