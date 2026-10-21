// ============================================================
// frontend/src/components/admin/users/dialogs/UserDetailsDialog.tsx
// ============================================================
import type { AdminUsersDialogsProps } from '../AdminUsersDialogs.types'
import Modal from '../../../Modal'
import { Detail, UserStatusBadge } from '../AdminUsersUi'
import { UserExactDate, UserUuidCopy } from '../UserDetailExtras'
import { findOrganizationName, getRoleLabel } from '../../../../pages/adminUsersSupport'

type UserDetailsDialogProps = Pick<AdminUsersDialogsProps,
    | 'detailsUser'
    | 'closeDetailsModal'
    | 'detailsError'
    | 'currentUserIsSuperAdmin'
    | 'organizations'
>

export function UserDetailsDialog(props: UserDetailsDialogProps) {
    const { detailsUser, closeDetailsModal, detailsError, currentUserIsSuperAdmin, organizations } = props
    return (
        <>
            {detailsUser && (
                <Modal
                    title="Данные пользователя"
                    onClose={
                        closeDetailsModal
                    }
                    size="md"
                >
                    {detailsError && (
                        <div
                            className="error"
                            role="alert"
                        >
                            {detailsError}
                        </div>
                    )}

                    <dl
                        className={
                            'user-details'
                        }
                    >
                        {currentUserIsSuperAdmin && (
                            <Detail
                                term="UUID пользователя"
                                value={<UserUuidCopy value={detailsUser.id} label="UUID пользователя" />}
                            />
                        )}

                        <Detail
                            term="Email"
                            value={
                                detailsUser.email
                            }
                        />

                        <Detail
                            term="Полное имя"
                            value={
                                detailsUser.fullName
                                ?? '—'
                            }
                        />

                        <Detail
                            term="Организация"
                            value={
                                <div
                                    className={
                                        'user-details__organization'
                                    }
                                >
                                    <strong
                                        className={
                                            'user-details__organization-name'
                                        }
                                    >
                                        {
                                            detailsUser.organizationName
                                            ?? findOrganizationName(
                                                detailsUser.organizationId,
                                                organizations,
                                            )
                                            ?? 'Название недоступно'
                                        }
                                    </strong>

                                    {currentUserIsSuperAdmin
                                        && (
                                            <UserUuidCopy
                                                value={detailsUser.organizationId}
                                                label="UUID организации"
                                            />
                                        )}
                                </div>
                            }
                        />

                        <Detail
                            term="Роль"
                            value={
                                detailsUser.roles
                                    .map(
                                        getRoleLabel,
                                    )
                                    .join(', ')
                            }
                        />

                        <Detail
                            term="Статус"
                            value={
                                <UserStatusBadge
                                    enabled={
                                        detailsUser.enabled
                                    }
                                />
                            }
                        />

                        <Detail
                            term="Дата создания"
                            value={
                                <UserExactDate value={detailsUser.createdAt} />
                            }
                        />

                        <Detail
                            term={
                                'Последнее обновление записи'
                            }
                            value={
                                <UserExactDate value={detailsUser.updatedAt} />
                            }
                        />

                        <Detail
                            term="Последний вход"
                            value={
                                <UserExactDate value={detailsUser.lastLoginAt} />
                            }
                        />
                    </dl>
                </Modal>
            )}
        </>
    )
}
