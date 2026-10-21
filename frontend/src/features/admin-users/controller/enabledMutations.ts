// frontend/src/features/admin-users/controller/enabledMutations.ts
// ============================================================
// frontend/src/features/admin-users/controller/enabledMutations.ts
// ============================================================
import { updateUserEnabled } from '../../../entities/user/api/userApi'
import type { MutationContext } from './mutationTypes'

export function createEnabledMutations(context: MutationContext) {
    const {
        confirmState,
        setConfirmState,
        setMutationError,
        setSuccess,
        requireVersion,
        runUserMutation,
        requestReloadFromFirstPage,
        canManageUser,
    } = context

    async function confirmEnabledChange() {
        if (!confirmState) {
            return
        }

        if (!canManageUser(confirmState.user)) {
            setMutationError('Недостаточно прав для изменения статуса пользователя.')
            return
        }

        const expectedVersion =
            requireVersion(confirmState.user)

        if (expectedVersion === null) {
            return
        }

        const target = confirmState.user
        const nextEnabled =
            confirmState.nextEnabled

        await runUserMutation(
            {
                userId: target.id,
                type: 'ENABLED',
            },
            'Не удалось изменить статус пользователя.',
            async () => {
                await updateUserEnabled(
                    target.id,
                    {
                        enabled: nextEnabled,
                        expectedVersion,
                    },
                )

                setConfirmState(null)
                setSuccess(
                    nextEnabled
                        ? `Пользователь ${target.email} включён.`
                        : (
                            `Пользователь ${target.email} отключён. `
                            + 'Запущен отзыв активных сессий.'
                        ),
                )
                requestReloadFromFirstPage()
            },
            false,
        )
    }

    return { confirmEnabledChange }
}
