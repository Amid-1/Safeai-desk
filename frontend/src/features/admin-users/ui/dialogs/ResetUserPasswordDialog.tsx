// frontend/src/features/admin-users/ui/dialogs/ResetUserPasswordDialog.tsx
// ============================================================
// frontend/src/features/admin-users/ui/dialogs/ResetUserPasswordDialog.tsx
// ============================================================
import type { AdminUsersDialogsProps } from '../AdminUsersDialogs.types'
import Modal from '../../../../shared/ui/Modal'
import { ModalActions, ModalError, PasswordFields } from '../AdminUsersUi'

type ResetUserPasswordDialogProps = Pick<AdminUsersDialogsProps,
    | 'resetPasswordUser'
    | 'closeMutationModals'
    | 'hasPendingMutation'
    | 'submitResetPassword'
    | 'resetPasswordValue'
    | 'resetPasswordConfirm'
    | 'setResetPasswordValue'
    | 'setResetPasswordConfirm'
    | 'modalError'
>

export function ResetUserPasswordDialog(props: ResetUserPasswordDialogProps) {
    const { resetPasswordUser, closeMutationModals, hasPendingMutation, submitResetPassword, resetPasswordValue, resetPasswordConfirm, setResetPasswordValue, setResetPasswordConfirm, modalError } = props
    return (
        <>
            {resetPasswordUser && (
                <Modal
                    title={
                        'Установить новый пароль'
                    }
                    size="sm"
                    onClose={
                        closeMutationModals
                    }
                    closeDisabled={
                        hasPendingMutation
                    }
                >
                    <p
                        className={
                            'modal-subtitle'
                        }
                    >
                        {resetPasswordUser.email}
                    </p>

                    <form
                        className="form"
                        onSubmit={(event) => {
                            event.preventDefault()
                            void submitResetPassword()
                        }}
                    >
                        <PasswordFields
                            password={
                                resetPasswordValue
                            }
                            passwordConfirm={
                                resetPasswordConfirm
                            }
                            onPasswordChange={
                                setResetPasswordValue
                            }
                            onPasswordConfirmChange={
                                setResetPasswordConfirm
                            }
                            disabled={
                                hasPendingMutation
                            }
                            passwordLabel={
                                'Новый пароль'
                            }
                        />

                        <p
                            className={
                                'users-security-note'
                            }
                        >
                            После смены пароля активные
                            сессии пользователя будут
                            отозваны.
                        </p>

                        <ModalError
                            message={
                                modalError
                            }
                        />

                        <ModalActions
                            busy={
                                hasPendingMutation
                            }
                            onCancel={
                                closeMutationModals
                            }
                            submitLabel={
                                'Установить новый пароль'
                            }
                            submitDisabled={
                                resetPasswordValue !== resetPasswordConfirm
                                || new TextEncoder().encode(resetPasswordValue).length > 72
                            }
                        />
                    </form>
                </Modal>
            )}
        </>
    )
}
