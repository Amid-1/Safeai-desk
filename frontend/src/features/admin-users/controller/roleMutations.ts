// frontend/src/features/admin-users/controller/roleMutations.ts
// ============================================================
// frontend/src/features/admin-users/controller/roleMutations.ts
// ============================================================
import { updateUserRoles } from '../../../entities/user/api/userApi'
import { getAssignableRole } from '../lib/adminUsersSupport'
import type { User } from '../../../entities/user/api/userApi'
import type { MutationContext } from './mutationTypes'

export function createRoleMutations(context: MutationContext) {
    const {
        rolesUser,
        selectedRole,
        adminElevationConfirmed,
        setRolesUser,
        setSelectedRole,
        setAdminElevationConfirmed,
        setModalError,
        setMutationError,
        setSuccess,
        currentUserIsSuperAdmin,
        ensureVersionAvailable,
        requireVersion,
        closeMutationModals,
        runUserMutation,
        requestReloadFromFirstPage,
        canManageUser,
        pendingMutationRef,
    } = context

    function openRolesModal(user: User) {
        if (pendingMutationRef.current) return
        if (!canManageUser(user)) {
            setMutationError('Недостаточно прав для изменения выбранного пользователя.')
            return
        }

        if (!currentUserIsSuperAdmin) {
            setMutationError(
                'Только SUPER_ADMIN может изменять системную роль пользователя.',
            )
            return
        }

        if (!ensureVersionAvailable(user)) {
            return
        }

        const role = getAssignableRole(user)

        if (!role) {
            setMutationError(
                'Роль пользователя недоступна для изменения через user-management.',
            )
            return
        }

        closeMutationModals()
        setRolesUser(user)
        setSelectedRole(role)
        setAdminElevationConfirmed(false)
        setModalError('')
    }

    async function submitRoles() {
        if (!rolesUser) {
            return
        }

        if (!canManageUser(rolesUser) || !getAssignableRole(rolesUser)) {
            setModalError('Роль пользователя недоступна для изменения.')
            return
        }

        if (selectedRole !== 'USER' && selectedRole !== 'ADMIN') {
            setModalError('Недопустимая системная роль пользователя.')
            return
        }

        const expectedVersion =
            requireVersion(rolesUser)

        if (expectedVersion === null) {
            return
        }

        if (!currentUserIsSuperAdmin) {
            setModalError(
                'Только SUPER_ADMIN может изменять системную роль пользователя.',
            )
            return
        }

        const addingAdmin =
            selectedRole === 'ADMIN'
            && !rolesUser.roles.includes(
                'ADMIN',
            )

        if (
            addingAdmin
            && !adminElevationConfirmed
        ) {
            setModalError(
                'Подтвердите повышение привилегий до ADMIN.',
            )
            return
        }

        await runUserMutation(
            {
                userId: rolesUser.id,
                type: 'ROLES',
            },
            'Не удалось изменить роли пользователя.',
            async () => {
                await updateUserRoles(
                    rolesUser.id,
                    {
                        roles: [selectedRole],
                        expectedVersion,
                    },
                )

                const userEmail = rolesUser.email
                setRolesUser(null)
                setAdminElevationConfirmed(false)
                setSuccess(
                    `Роль пользователя ${userEmail} изменена. `
                    + 'Активные сессии должны быть завершены backend.',
                )
                requestReloadFromFirstPage()
            },
        )
    }

    return { openRolesModal, submitRoles }
}
