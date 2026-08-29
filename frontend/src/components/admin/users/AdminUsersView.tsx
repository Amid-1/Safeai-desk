import type { AuthUser } from '../../../api/authApi'
import type { OrganizationDirectoryItem } from '../../../api/organizationApi'
import type { User, UserStatistics } from '../../../api/userApi'
import ResizableScrollRegion from '../../ResizableScrollRegion'
import { EmptyState, ErrorState, LoadingState } from '../../StateBlock'
import UserActionsMenu from '../UserActionsMenu'
import UserIdentityCell from '../UserIdentityCell'
import UserRoleBadge from '../UserRoleBadge'
import { FilterButton, Pagination, UserStatusBadge } from './AdminUsersUi'
import { formatDateTime } from '../../../utils/format'
import { getOrganizationName, getUnmanageableReason } from '../../../pages/adminUsersSupport'

export type AdminUsersFilter = 'ALL' | 'USER' | 'ADMIN'

type AdminUsersViewProps = {
    users: User[]
    statistics: UserStatistics
    organizations: OrganizationDirectoryItem[]
    loadError: string
    mutationError: string
    success: string
    loading: boolean
    creating: boolean
    hasPendingMutation: boolean
    page: number
    totalPages: number
    filter: AdminUsersFilter
    detailsLoadingUserId: string | null
    currentUser: AuthUser
    currentUserIsSuperAdmin: boolean
    onCreate: () => void
    onFilterChange: (filter: AdminUsersFilter) => void
    onPageChange: (page: number) => void
    onReload: () => void
    onDetails: (user: User) => void | Promise<void>
    onEdit: (user: User) => void
    onRoles: (user: User) => void
    onResetPassword: (user: User) => void
    onEnabledChange: (value: { user: User; nextEnabled: boolean } | null) => void
    onDelete: (user: User) => void
    canManageUser: (user: User) => boolean
    canPermanentlyDelete: (user: User) => boolean
}

export function AdminUsersView({
    users, statistics, organizations, loadError, mutationError, success, loading, creating,
    hasPendingMutation, page, totalPages, filter, detailsLoadingUserId, currentUser,
    currentUserIsSuperAdmin, onCreate, onFilterChange, onPageChange, onReload, onDetails,
    onEdit, onRoles, onResetPassword, onEnabledChange, onDelete, canManageUser,
    canPermanentlyDelete,
}: AdminUsersViewProps) {
    const openCreateModal = onCreate
    const setFilter = onFilterChange
    const openDetailsModal = onDetails
    const openEditModal = onEdit
    const openRolesModal = onRoles
    const openResetPasswordModal = onResetPassword
    const setConfirmState = onEnabledChange
    const openDeleteModal = onDelete

    return (
        <>
            <ResizableScrollRegion
                storageKey="safeai:users-table-height"
                label="список пользователей"
                upper={
                    <div className="users-page__upper">
                        <div className="users-page-header">
                            <div>
                                <h1>Пользователи</h1>
                                <p className="users-page-subtitle">
                                    Управление пользователями системы
                                </p>
                            </div>

                            <button
                                type="button"
                                className="users-create-button"
                                onClick={openCreateModal}
                                disabled={
                                    creating
                                    || hasPendingMutation
                                }
                            >
                                <span aria-hidden="true">
                                    ＋
                                </span>
                                Создать пользователя
                            </button>
                        </div>

                        {mutationError && (
                            <div
                                className="error"
                                role="alert"
                                aria-live="assertive"
                            >
                                {mutationError}
                            </div>
                        )}

                        {success && (
                            <div
                                className="success"
                                role="status"
                                aria-live="polite"
                            >
                                {success}
                            </div>
                        )}

                        <div
                            className="users-filter-bar"
                            aria-label={
                                'Фильтр пользователей по роли'
                            }
                        >
                            <FilterButton
                                active={filter === 'ALL'}
                                label="Все"
                                count={statistics.total}
                                disabled={hasPendingMutation}
                                onClick={() => {
                                    setFilter('ALL')
                                }}
                            />

                            <FilterButton
                                active={filter === 'ADMIN'}
                                label="Администраторы"
                                count={
                                    statistics.administrators
                                }
                                disabled={hasPendingMutation}
                                onClick={() => {
                                    setFilter('ADMIN')
                                }}
                            />

                            <FilterButton
                                active={filter === 'USER'}
                                label="Пользователи"
                                count={statistics.users}
                                disabled={hasPendingMutation}
                                onClick={() => {
                                    setFilter('USER')
                                }}
                            />
                        </div>

                        {loading && (
                            <LoadingState
                                message="Загрузка пользователей..."
                            />
                        )}

                        {!loading && loadError && (
                            <ErrorState
                                title="Ошибка загрузки пользователей"
                                message={loadError}
                                action={
                                    <button
                                        type="button"
                                        onClick={onReload}
                                    >
                                        Повторить
                                    </button>
                                }
                            />
                        )}

                        {!loading
                            && !loadError
                            && users.length === 0
                            && (
                                <EmptyState
                                    message={
                                        'Пользователи не найдены.'
                                    }
                                />
                            )}
                    </div>
                }
                footer={
                    !loading
                    && !loadError
                    && users.length > 0
                        ? (
                            <Pagination
                                page={page}
                                totalPages={totalPages}
                                disabled={
                                    loading
                                    || hasPendingMutation
                                }
                                onPrevious={() =>
                                    onPageChange(
                                        Math.max(
                                            0,
                                            page - 1,
                                        ),
                                    )
                                }
                                onNext={() =>
                                    onPageChange(
                                        page + 1,
                                    )
                                }
                            />
                        )
                        : null
                }
                lowerClassName="users-table-card"
                viewportClassName="users-table-scroll"
                defaultHeight={500}
                minHeight={72}
                maxHeight={760}
                minUpperHeight={112}
            >
                {!loading
                    && !loadError
                    && users.length > 0
                    && (
                    <table className="users-table">
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
                                <th>Роли</th>
                                <th>Статус</th>
                                <th>
                                    Дата создания
                                </th>
                                <th>Действия</th>
                            </tr>
                        </thead>
                        <tbody>
                            {users.map(
                                (user) => {
                                    const manageable =
                                        canManageUser(
                                            user,
                                        )

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
                                                    onClick={() =>
                                                        void openDetailsModal(
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
                                                <div className="role-list">
                                                    {user.roles.map(
                                                        (role) => (
                                                            <UserRoleBadge
                                                                key={
                                                                    role
                                                                }
                                                                role={
                                                                    role
                                                                }
                                                            />
                                                        ),
                                                    )}
                                                </div>
                                            </td>

                                            <td>
                                                <UserStatusBadge
                                                    enabled={
                                                        user.enabled
                                                    }
                                                />
                                            </td>

                                            <td>
                                                {formatDateTime(
                                                    user.createdAt,
                                                )}
                                            </td>

                                            <td className="actions-cell">
                                                <UserActionsMenu
                                                    disabled={
                                                        hasPendingMutation
                                                    }
                                                    canManage={
                                                        manageable
                                                    }
                                                    canChangeRole={
                                                        currentUserIsSuperAdmin
                                                        && manageable
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
                                                        void openDetailsModal(
                                                            user,
                                                        )
                                                    }
                                                    onEdit={() =>
                                                        openEditModal(
                                                            user,
                                                        )
                                                    }
                                                    onRoles={() =>
                                                        openRolesModal(
                                                            user,
                                                        )
                                                    }
                                                    onResetPassword={() =>
                                                        openResetPasswordModal(
                                                            user,
                                                        )
                                                    }
                                                    onToggleEnabled={() =>
                                                        setConfirmState({
                                                            user,
                                                            nextEnabled:
                                                                !user.enabled,
                                                        })
                                                    }
                                                    onDelete={() =>
                                                        openDeleteModal(
                                                            user,
                                                        )
                                                    }
                                                />

                                                {!manageable && (
                                                    <span className="muted">
                                                        {
                                                            getUnmanageableReason(
                                                                user,
                                                                currentUser,
                                                            )
                                                        }
                                                    </span>
                                                )}

                                                {user.version
                                                    === null
                                                    && manageable
                                                    && (
                                                        <span className="muted">
                                                            Backend version отсутствует
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
            </ResizableScrollRegion>

        </>
    )
}
