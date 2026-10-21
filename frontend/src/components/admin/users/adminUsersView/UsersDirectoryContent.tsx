// frontend/src/components/admin/users/adminUsersView/UsersDirectoryContent.tsx
import type { User } from '../../../../api/userApi'
import UserActionsMenu from '../../UserActionsMenu'
import UserIdentityCell from '../../UserIdentityCell'
import { UserRoleQuickAction, UserStatusQuickAction } from '../AdminUsersQuickActions'
import { formatDateTime } from '../../../../utils/format'
import { getOrganizationName, getUnmanageableReason } from '../../../../pages/adminUsersSupport'
import type { AdminUsersViewProps } from '../adminUsersViewTypes'

type Props = AdminUsersViewProps & { onShowRoleInfo: (user: User) => void }
export function UsersDirectoryContent({
    users,
    loading,
    loadError,
    appliedSearch,
    searchDraft,
    onClearSearch,
    currentUserIsSuperAdmin,
    canManageUser,
    currentUser,
    organizations,
    detailsLoadingUserId,
    onDetails,
    hasPendingMutation,
    onRoles,
    onEnabledChange,
    canPermanentlyDelete,
    onEdit,
    onResetPassword,
    onDelete,
    onShowRoleInfo,
}: Props) {
    const setRoleInfoUser = onShowRoleInfo
    return (
        <>
                {!loading && !loadError && users.length === 0 && (
                    <div className="users-directory-empty" role="status">
                        <strong>
                            {appliedSearch
                                ? 'По вашему запросу пользователи не найдены'
                                : 'В выбранной категории пользователей нет'}
                        </strong>
                        <p>
                            {appliedSearch
                                ? `Нет совпадений для «${appliedSearch}». Проверьте имя, фамилию или email.`
                                : 'Попробуйте изменить роль или статус пользователя.'}
                        </p>
                        {(appliedSearch || searchDraft) && (
                            <button type="button" className="secondary-button"
                                onClick={onClearSearch}>Сбросить поиск</button>
                        )}
                    </div>
                )}
                {!loading
                    && !loadError
                    && users.length > 0
                    && (
                        <table
                            className={
                                'users-table'
                            }
                        >
                            <thead>
                                <tr>
                                    <th>
                                        Пользователь
                                    </th>

                                    {currentUserIsSuperAdmin
                                        && (
                                            <th>
                                                Организация
                                            </th>
                                        )}

                                    <th>
                                        Роль
                                    </th>

                                    <th>
                                        Статус
                                    </th>

                                    <th>
                                        Дата создания
                                    </th>

                                    <th>
                                        Действия
                                    </th>
                                </tr>
                            </thead>

                            <tbody>
                                {users.map(
                                    (user) => {
                                        const manageable =
                                            canManageUser(
                                                user,
                                            )

                                        const canChangeRole =
                                            currentUserIsSuperAdmin
                                            && manageable

                                        const role =
                                            user.roles[0]

                                        return (
                                            <tr
                                                key={
                                                    user.id
                                                }
                                                className={
                                                    user.enabled
                                                        ? undefined
                                                        : 'users-table__row--disabled'
                                                }
                                            >
                                                <td>
                                                    <button
                                                        type="button"
                                                        className={
                                                            'user-identity-link'
                                                        }
                                                        disabled={
                                                            detailsLoadingUserId
                                                                === user.id
                                                        }
                                                        aria-label={
                                                            `Подробнее о пользователе ${user.email}`
                                                        }
                                                        onClick={() =>
                                                            void onDetails(
                                                                user,
                                                            )
                                                        }
                                                    >
                                                        <UserIdentityCell
                                                            fullName={
                                                                user.fullName
                                                            }
                                                            email={
                                                                user.email
                                                            }
                                                            roles={
                                                                user.roles
                                                            }
                                                        />
                                                    </button>
                                                </td>

                                                {currentUserIsSuperAdmin
                                                    && (
                                                        <td>
                                                            {
                                                                getOrganizationName(
                                                                    user.organizationId,
                                                                    organizations,
                                                                )
                                                            }
                                                        </td>
                                                    )}

                                                <td>
                                                    <div
                                                        className={
                                                            'role-list'
                                                        }
                                                    >
                                                        <UserRoleQuickAction
                                                            role={
                                                                role
                                                            }
                                                            email={
                                                                user.email
                                                            }
                                                            editable={
                                                                canChangeRole
                                                            }
                                                            disabled={
                                                                hasPendingMutation
                                                            }
                                                            onEdit={() =>
                                                                onRoles(
                                                                    user,
                                                                )
                                                            }
                                                            onInfo={() =>
                                                                setRoleInfoUser(user)
                                                            }
                                                        />
                                                    </div>
                                                </td>

                                                <td>
                                                    <UserStatusQuickAction
                                                        enabled={
                                                            user.enabled
                                                        }
                                                        email={
                                                            user.email
                                                        }
                                                        editable={
                                                            manageable
                                                        }
                                                        disabled={
                                                            hasPendingMutation
                                                        }
                                                        onToggle={() =>
                                                            onEnabledChange({
                                                                user,
                                                                nextEnabled:
                                                                    !user.enabled,
                                                            })
                                                        }
                                                    />
                                                </td>

                                                <td>
                                                    {
                                                        formatDateTime(
                                                            user.createdAt,
                                                        )
                                                    }
                                                </td>

                                                <td
                                                    className={
                                                        'actions-cell'
                                                    }
                                                >
                                                    <UserActionsMenu
                                                        userLabel={
                                                            user.email
                                                        }
                                                        disabled={
                                                            hasPendingMutation
                                                        }
                                                        canManage={
                                                            manageable
                                                        }
                                                        canChangeRole={
                                                            canChangeRole
                                                        }
                                                        canDelete={
                                                            canPermanentlyDelete(
                                                                user,
                                                            )
                                                        }
                                                        enabled={
                                                            user.enabled
                                                        }
                                                        onDetails={() =>
                                                            void onDetails(
                                                                user,
                                                            )
                                                        }
                                                        onEdit={() =>
                                                            onEdit(
                                                                user,
                                                            )
                                                        }
                                                        onRoles={() =>
                                                            onRoles(
                                                                user,
                                                            )
                                                        }
                                                        onResetPassword={() =>
                                                            onResetPassword(
                                                                user,
                                                            )
                                                        }
                                                        onToggleEnabled={() =>
                                                            onEnabledChange({
                                                                user,
                                                                nextEnabled:
                                                                    !user.enabled,
                                                            })
                                                        }
                                                        onDelete={() =>
                                                            onDelete(
                                                                user,
                                                            )
                                                        }
                                                    />

                                                    {!manageable
                                                        && (
                                                            <span
                                                                className={
                                                                    'muted'
                                                                }
                                                            >
                                                                {
                                                                    getUnmanageableReason(
                                                                        user,
                                                                        currentUser,
                                                                    )
                                                                }
                                                            </span>
                                                        )}
                                                </td>
                                            </tr>
                                        )
                                    },
                                )}
                            </tbody>
                        </table>
                    )}
        </>
    )
}
