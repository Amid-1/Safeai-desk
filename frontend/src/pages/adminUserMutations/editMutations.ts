// ============================================================
// frontend/src/pages/adminUserMutations/editMutations.ts
// ============================================================
import { getUserDetails, updateUser } from '../../api/userApi'
import { normalizeEmail, normalizeOptionalText, getMutationErrorMessage } from '../adminUsersSupport'
import type { User } from '../../api/userApi'
import type { MutationContext, WorkingMutation } from './mutationTypes'

export function createEditMutations(context: MutationContext) {
    const {
        editUser,
        editVersionConflict,
        editEmail,
        editFullName,
        setEditUser,
        setEditEmail,
        setEditFullName,
        setEditVersionConflict,
        setModalError,
        setMutationError,
        setSuccess,
        pendingMutationRef,
        setPendingMutation,
        ensureVersionAvailable,
        requireVersion,
        closeMutationModals,
        runUserMutation,
        requestReloadFromFirstPage,
        canManageUser,
    } = context

    function openEditModal(user: User) {
        if (pendingMutationRef.current) return
        if (!canManageUser(user)) {
            setMutationError('Недостаточно прав для изменения выбранного пользователя.')
            return
        }

        if (!ensureVersionAvailable(user)) {
            return
        }

        closeMutationModals()
        setEditUser(user)
        setEditVersionConflict(false)
        setEditEmail(user.email)
        setEditFullName(user.fullName ?? '')
        setModalError('')
    }

    async function refreshEditedUser() {
        if (!editUser || pendingMutationRef.current) return
        const previousId = editUser.id
        const loadingMutation: WorkingMutation = {
            userId: previousId,
            type: 'EDIT',
        }
        pendingMutationRef.current = loadingMutation
        setPendingMutation(loadingMutation)
        try {
            const fresh = await getUserDetails(previousId)
            if (fresh.id !== previousId || fresh.organizationId !== editUser.organizationId || fresh.version === null || !Number.isSafeInteger(fresh.version) || fresh.version < 0) {
                // Нельзя подменять редактируемого пользователя другим ID, tenant или версией.
                setEditVersionConflict(true)
                setModalError(
                    'Backend вернул данные другого пользователя. '
                    + 'Редактирование заблокировано до повторной загрузки.',
                )
                return
            }
            setEditUser(fresh)
            setEditEmail(fresh.email)
            setEditFullName(fresh.fullName ?? '')
            setEditVersionConflict(false)
            setModalError('')
        } catch (error) {
            setModalError(getMutationErrorMessage(
                error,
                'Не удалось получить актуальные данные пользователя.',
            ))
        } finally {
            pendingMutationRef.current = null
            setPendingMutation(null)
        }
    }

    async function submitEditUser() {
        if (!editUser || editVersionConflict) {
            return
        }

        if (!canManageUser(editUser)) {
            setModalError('Недостаточно прав для изменения выбранного пользователя.')
            return
        }

        const expectedVersion =
            requireVersion(editUser)

        if (expectedVersion === null) {
            return
        }

        const normalizedEmail =
            normalizeEmail(editEmail)

        if (!normalizedEmail) {
            setModalError(
                'Введите email пользователя.',
            )
            return
        }

        await runUserMutation(
            {
                userId: editUser.id,
                type: 'EDIT',
            },
            'Не удалось обновить пользователя.',
            async () => {
                await updateUser(
                    editUser.id,
                    {
                        email: normalizedEmail,
                        fullName:
                            normalizeOptionalText(
                                editFullName,
                            ),
                        expectedVersion,
                    },
                )

                setEditUser(null)
                setSuccess(
                    `Пользователь ${normalizedEmail} обновлён.`,
                )
                requestReloadFromFirstPage()
            },
        )
    }

    return { openEditModal, refreshEditedUser, submitEditUser }
}
