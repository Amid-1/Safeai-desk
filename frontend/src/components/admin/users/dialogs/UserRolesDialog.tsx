// ============================================================
// frontend/src/components/admin/users/dialogs/UserRolesDialog.tsx
// ============================================================
import type { AdminUsersDialogsProps } from '../AdminUsersDialogs.types'
import Modal from '../../../Modal'
import { UserRoleSelector } from '../../UserRoleSelector'
import { ModalActions, ModalError } from '../AdminUsersUi'

type UserRolesDialogProps = Pick<AdminUsersDialogsProps,
    | 'rolesUser'
    | 'currentUserIsSuperAdmin'
    | 'closeMutationModals'
    | 'hasPendingMutation'
    | 'submitRoles'
    | 'selectedRole'
    | 'setSelectedRole'
    | 'setAdminElevationConfirmed'
    | 'adminElevationConfirmed'
    | 'modalError'
>

export function UserRolesDialog(props: UserRolesDialogProps) {
    const { rolesUser, currentUserIsSuperAdmin, closeMutationModals, hasPendingMutation, submitRoles, selectedRole, setSelectedRole, setAdminElevationConfirmed, adminElevationConfirmed, modalError } = props
    return (
        <>
            {rolesUser
                && currentUserIsSuperAdmin
                && (
                    <Modal
                        title={
                            'Изменить роль пользователя'
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
                            {rolesUser.email}
                        </p>

                        <form
                            className="form"
                            onSubmit={(event) => {
                                event.preventDefault()
                                void submitRoles()
                            }}
                        >
                            <UserRoleSelector
                                name={
                                    'edit-user-role'
                                }
                                value={
                                    selectedRole
                                }
                                disabled={
                                    hasPendingMutation
                                }
                                onChange={(role) => {
                                    setSelectedRole(
                                        role,
                                    )

                                    setAdminElevationConfirmed(
                                        false,
                                    )
                                }}
                                legend={
                                    'Системная роль'
                                }
                            />

                            {selectedRole === 'ADMIN'
                                && !rolesUser.roles.includes(
                                    'ADMIN',
                                )
                                && (
                                    <label
                                        className={
                                            'danger-notice'
                                        }
                                    >
                                        <input
                                            type={
                                                'checkbox'
                                            }
                                            checked={
                                                adminElevationConfirmed
                                            }
                                            onChange={(
                                                event,
                                            ) =>
                                                setAdminElevationConfirmed(
                                                    event.target.checked,
                                                )
                                            }
                                            disabled={
                                                hasPendingMutation
                                            }
                                        />

                                        Подтверждаю повышение
                                        привилегий до ADMIN.
                                        Пользователь получит
                                        административный доступ.
                                    </label>
                                )}

                            <p className="users-security-note" role="note">
                                После изменения роли сервер отзовет активные
                                сессии пользователя. Новые права будут
                                доступны только после повторного входа.
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
                                    'Сохранить роль'
                                }
                                submitDisabled={
                                    selectedRole === rolesUser.roles[0]
                                    || (selectedRole === 'ADMIN'
                                        && !rolesUser.roles.includes('ADMIN')
                                        && !adminElevationConfirmed)
                                }
                            />
                        </form>
                    </Modal>
                )}
        </>
    )
}
