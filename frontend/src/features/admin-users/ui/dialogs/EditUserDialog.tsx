// frontend/src/features/admin-users/ui/dialogs/EditUserDialog.tsx
// ============================================================
// frontend/src/features/admin-users/ui/dialogs/EditUserDialog.tsx
// ============================================================
import type { AdminUsersDialogsProps } from '../AdminUsersDialogs.types'
import Modal from '../../../../shared/ui/Modal'
import { normalizeEmail } from '../../lib/adminUsersSupport'
import { ModalActions, ModalError } from '../AdminUsersUi'

type EditUserDialogProps = Pick<AdminUsersDialogsProps,
    | 'editUser'
    | 'closeMutationModals'
    | 'hasPendingMutation'
    | 'submitEditUser'
    | 'editEmail'
    | 'setEditEmail'
    | 'editFullName'
    | 'setEditFullName'
    | 'editVersionConflict'
    | 'refreshEditedUser'
    | 'modalError'
>

export function EditUserDialog(props: EditUserDialogProps) {
    const { editUser, closeMutationModals, hasPendingMutation, submitEditUser, editEmail, setEditEmail, editFullName, setEditFullName, editVersionConflict, refreshEditedUser, modalError } = props
    return (
        <>
            {editUser && (
                <Modal
                    title={
                        'Изменить email и имя'
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
                        Учётная запись:
                        {' '}
                        {editUser.email}
                    </p>

                    <form
                        className="form"
                        onSubmit={(event) => {
                            event.preventDefault()
                            void submitEditUser()
                        }}
                    >
                        <label>
                            Email
                            <input
                                value={
                                    editEmail
                                }
                                onChange={(event) =>
                                    setEditEmail(
                                        event.target.value,
                                    )
                                }
                                type="email"
                                autoComplete="username"
                                maxLength={255}
                                required
                                disabled={
                                    hasPendingMutation
                                }
                            />
                        </label>

                        <label>
                            Полное имя
                            <input
                                value={
                                    editFullName
                                }
                                onChange={(event) =>
                                    setEditFullName(
                                        event.target.value,
                                    )
                                }
                                autoComplete="name"
                                maxLength={255}
                                disabled={
                                    hasPendingMutation
                                }
                            />
                        </label>

                        {normalizeEmail(editEmail) !== normalizeEmail(editUser.email) && (
                            <p className="users-edit-email-note" role="note">
                                Изменение email изменит логин пользователя.
                                После сохранения backend отзовет его активные сессии.
                            </p>
                        )}

                        {editVersionConflict && (
                            <div className="users-edit-email-note" role="alert">
                                Запись была изменена другим администратором.
                                Сохранение заблокировано до получения новой версии.
                                После обновления текущие несохраненные изменения формы будут сброшены.
                                <div style={{marginTop: 10}}>
                                    <button
                                        type="button"
                                        className="secondary-button"
                                        disabled={hasPendingMutation}
                                        onClick={() => void refreshEditedUser()}
                                    >
                                        Загрузить актуальные данные
                                    </button>
                                </div>
                            </div>
                        )}

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
                                'Сохранить изменения'
                            }
                            submitDisabled={
                                editVersionConflict
                                || (normalizeEmail(editEmail) === normalizeEmail(editUser.email)
                                && (editFullName.trim() || '') === (editUser.fullName?.trim() || ''))
                            }
                        />
                    </form>
                </Modal>
            )}
        </>
    )
}
