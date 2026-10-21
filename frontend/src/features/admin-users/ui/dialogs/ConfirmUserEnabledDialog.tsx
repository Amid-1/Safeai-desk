// frontend/src/features/admin-users/ui/dialogs/ConfirmUserEnabledDialog.tsx
// ============================================================
// frontend/src/features/admin-users/ui/dialogs/ConfirmUserEnabledDialog.tsx
// ============================================================
import type { AdminUsersDialogsProps } from '../AdminUsersDialogs.types'
import ConfirmDialog from '../../../../shared/ui/ConfirmDialog'

type ConfirmUserEnabledDialogProps = Pick<AdminUsersDialogsProps,
    | 'confirmState'
    | 'hasPendingMutation'
    | 'setConfirmState'
    | 'confirmEnabledChange'
>

export function ConfirmUserEnabledDialog(props: ConfirmUserEnabledDialogProps) {
    const { confirmState, hasPendingMutation, setConfirmState, confirmEnabledChange } = props
    return (
        <>
            {confirmState && (
                <ConfirmDialog
                    title={
                        confirmState.nextEnabled
                            ? 'Включить пользователя'
                            : 'Отключить пользователя'
                    }
                    message={
                        confirmState.nextEnabled
                            ? (
                                `Включить ${confirmState.user.email}? `
                                + 'Пользователь снова сможет входить в систему.'
                            )
                            : (
                                `Отключить ${confirmState.user.email}? `
                                + 'Пользователь потеряет доступ, '
                                + 'а его активные сессии будут отозваны. '
                                + 'Учётная запись и история действий сохранятся.'
                            )
                    }
                    confirmText={
                        confirmState.nextEnabled
                            ? 'Включить пользователя'
                            : 'Отключить пользователя'
                    }
                    danger={
                        !confirmState.nextEnabled
                    }
                    loading={
                        hasPendingMutation
                    }
                    onCancel={() => {
                        if (!hasPendingMutation) {
                            setConfirmState(
                                null,
                            )
                        }
                    }}
                    onConfirm={
                        confirmEnabledChange
                    }
                />
            )}
        </>
    )
}
