// frontend/src/features/admin-users/ui/dialogs/DeleteUserDialog.tsx
// ============================================================
// frontend/src/features/admin-users/ui/dialogs/DeleteUserDialog.tsx
// ============================================================
import type { AdminUsersDialogsProps } from '../AdminUsersDialogs.types'
import Modal from '../../../../shared/ui/Modal'
import { ModalActions, ModalError } from '../AdminUsersUi'
import { normalizeEmail } from '../../lib/adminUsersSupport'

type DeleteUserDialogProps = Pick<AdminUsersDialogsProps,
    | 'deleteUser'
    | 'closeMutationModals'
    | 'hasPendingMutation'
    | 'submitPermanentDelete'
    | 'deleteConfirmationEmail'
    | 'setDeleteConfirmationEmail'
    | 'modalError'
>

export function DeleteUserDialog(props: DeleteUserDialogProps) {
    const { deleteUser, closeMutationModals, hasPendingMutation, submitPermanentDelete, deleteConfirmationEmail, setDeleteConfirmationEmail, modalError } = props
    return (
        <>
            {deleteUser && (
                <Modal
                    title={
                        'Удалить пользователя навсегда?'
                    }
                    size="sm"
                    onClose={
                        closeMutationModals
                    }
                    closeDisabled={
                        hasPendingMutation
                    }
                >
                    <div
                        className={
                            'danger-notice'
                        }
                    >
                        Пользователь должен быть
                        предварительно отключён.
                        Backend дополнительно проверяет
                        зависимости, retention policy,
                        last-admin invariant и защиту
                        платформенной организации.
                    </div>

                    <form
                        className="form"
                        onSubmit={(event) => {
                            event.preventDefault()
                            void submitPermanentDelete()
                        }}
                    >
                        <label>
                            Введите email пользователя

                            <input
                                type="email"
                                value={
                                    deleteConfirmationEmail
                                }
                                onChange={(event) =>
                                    setDeleteConfirmationEmail(
                                        event.target.value,
                                    )
                                }
                                autoComplete="off"
                                required
                                disabled={
                                    hasPendingMutation
                                }
                            />
                        </label>

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
                                'Удалить навсегда'
                            }
                            danger
                            submitDisabled={
                                normalizeEmail(
                                    deleteConfirmationEmail,
                                )
                                !== normalizeEmail(
                                    deleteUser.email,
                                )
                            }
                        />
                    </form>
                </Modal>
            )}
        </>
    )
}
