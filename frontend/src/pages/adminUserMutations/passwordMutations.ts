// ============================================================
// frontend/src/pages/adminUserMutations/passwordMutations.ts
// ============================================================
import { resetUserPassword } from '../../api/userApi'
import { validatePassword } from '../../utils/password'
import type { User } from '../../api/userApi'
import type { MutationContext } from './mutationTypes'

export function createPasswordMutations(context: MutationContext) {
    const {
        resetPasswordUser,
        resetPasswordValue,
        resetPasswordConfirm,
        setResetPasswordUser,
        setModalError,
        setMutationError,
        setSuccess,
        ensureVersionAvailable,
        requireVersion,
        closeMutationModals,
        clearMutationSensitiveFields,
        runUserMutation,
        requestReloadFromFirstPage,
        canManageUser,
        pendingMutationRef,
    } = context

    function openResetPasswordModal(
        user: User,
    ) {
        if (pendingMutationRef.current) return
        if (!canManageUser(user)) {
            setMutationError('Недостаточно прав для изменения выбранного пользователя.')
            return
        }

        if (!ensureVersionAvailable(user)) {
            return
        }

        closeMutationModals()
        setResetPasswordUser(user)
        clearMutationSensitiveFields()
        setModalError('')
    }

    async function submitResetPassword() {
        if (!resetPasswordUser) {
            return
        }

        if (!canManageUser(resetPasswordUser)) {
            setModalError('Недостаточно прав для сброса пароля пользователя.')
            return
        }

        const expectedVersion =
            requireVersion(resetPasswordUser)

        if (expectedVersion === null) {
            return
        }

        const passwordError =
            validatePassword(resetPasswordValue)

        if (passwordError) {
            setModalError(passwordError)
            return
        }

        if (
            resetPasswordValue
            !== resetPasswordConfirm
        ) {
            setModalError('Пароли не совпадают.')
            return
        }

        await runUserMutation(
            {
                userId: resetPasswordUser.id,
                type: 'RESET_PASSWORD',
            },
            'Не удалось установить новый пароль.',
            async () => {
                await resetUserPassword(
                    resetPasswordUser.id,
                    {
                        password:
                            resetPasswordValue,
                        expectedVersion,
                    },
                )

                const emailValue =
                    resetPasswordUser.email

                clearMutationSensitiveFields()
                setResetPasswordUser(null)
                setSuccess(
                    `Для ${emailValue} установлен новый пароль. `
                    + 'Активные сессии должны быть завершены backend.',
                )
                requestReloadFromFirstPage()
            },
        )
    }

    return { openResetPasswordModal, submitResetPassword }
}
