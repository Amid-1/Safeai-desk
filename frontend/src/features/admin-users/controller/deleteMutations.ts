// frontend/src/features/admin-users/controller/deleteMutations.ts
// ============================================================
// frontend/src/features/admin-users/controller/deleteMutations.ts
// ============================================================
import { permanentlyDeleteUser } from '../../../entities/user/api/userApi'
import { normalizeEmail } from '../lib/adminUsersSupport'
import type { User } from '../../../entities/user/api/userApi'
import type { MutationContext } from './mutationTypes'

export function createDeleteMutations(context: MutationContext) {
    const {
        deleteUser,
        deleteConfirmationEmail,
        setDeleteUser,
        setDeleteConfirmationEmail,
        setModalError,
        setMutationError,
        setSuccess,
        ensureVersionAvailable,
        requireVersion,
        closeMutationModals,
        clearMutationSensitiveFields,
        runUserMutation,
        requestReloadFromFirstPage,
        canPermanentlyDelete,
        canManageUser,
        pendingMutationRef,
    } = context

    function openDeleteModal(user: User) {
        if (pendingMutationRef.current) return
        if (!canManageUser(user)) {
            setMutationError('Недостаточно прав для изменения выбранного пользователя.')
            return
        }

        if (!ensureVersionAvailable(user)) {
            return
        }

        closeMutationModals()
        setDeleteUser(user)
        setDeleteConfirmationEmail('')
        setModalError('')
    }

    async function submitPermanentDelete() {
        if (!deleteUser) {
            return
        }

        if (!canPermanentlyDelete(deleteUser)) {
            setModalError(
                'Удаление недоступно. '
                + 'Сначала отключите пользователя '
                + 'и убедитесь, что его организация не защищена.',
            )
            return
        }

        const expectedVersion =
            requireVersion(deleteUser)

        if (expectedVersion === null) {
            return
        }

        const canonicalConfirmation =
            normalizeEmail(
                deleteConfirmationEmail,
            )

        if (
            canonicalConfirmation
            !== normalizeEmail(deleteUser.email)
        ) {
            setModalError(
                'Введите email пользователя полностью и без ошибок.',
            )
            return
        }

        await runUserMutation(
            {
                userId: deleteUser.id,
                type: 'DELETE',
            },
            'Не удалось удалить пользователя.',
            async () => {
                await permanentlyDeleteUser(
                    deleteUser.id,
                    {
                        confirmationEmail:
                            canonicalConfirmation,
                        expectedVersion,
                    },
                )

                const deletedEmail =
                    deleteUser.email

                clearMutationSensitiveFields()
                setDeleteUser(null)
                setSuccess(
                    `Пользователь ${deletedEmail} удалён навсегда.`,
                )
                requestReloadFromFirstPage()
            },
        )
    }

    return { openDeleteModal, submitPermanentDelete }
}
