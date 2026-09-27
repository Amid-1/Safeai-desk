// frontend/src/components/admin/users/AdminUsersView.tsx
import type {
    AuthUser,
} from '../../../api/authApi'
import type {
    OrganizationDirectoryItem,
} from '../../../api/organizationApi'
import type {
    User,
    UserStatistics,
} from '../../../api/userApi'
import ResizableScrollRegion
    from '../../ResizableScrollRegion'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../../StateBlock'
import UserActionsMenu
    from '../UserActionsMenu'
import UserIdentityCell
    from '../UserIdentityCell'
import {
    FilterButton,
    Pagination,
} from './AdminUsersUi'
import {
    UserRoleQuickAction,
    UserStatusQuickAction,
} from './AdminUsersQuickActions'
import {
    formatDateTime,
} from '../../../utils/format'
import {
    getOrganizationName,
    getUnmanageableReason,
} from '../../../pages/adminUsersSupport'

export type AdminUsersFilter =
    | 'ALL'
    | 'USER'
    | 'ADMIN'

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
    onFilterChange:
        (filter: AdminUsersFilter) => void
    onPageChange:
        (page: number) => void
    onReload: () => void
    onDetails:
        (user: User) => void | Promise<void>
    onEdit:
        (user: User) => void
    onRoles:
        (user: User) => void
    onResetPassword:
        (user: User) => void
    onEnabledChange:
        (
            value: {
                user: User
                nextEnabled: boolean
            } | null
        ) => void
    onDelete:
        (user: User) => void
    canManageUser:
        (user: User) => boolean
    canPermanentlyDelete:
        (user: User) => boolean
}

export function AdminUsersView({
    users,
    statistics,
    organizations,
    loadError,
    mutationError,
    success,
    loading,
    creating,
    hasPendingMutation,
    page,
    totalPages,
    filter,
    detailsLoadingUserId,
    currentUser,
    currentUserIsSuperAdmin,
    onCreate,
    onFilterChange,
    onPageChange,
    onReload,
    onDetails,
    onEdit,
    onRoles,
    onResetPassword,
    onEnabledChange,
    onDelete,
    canManageUser,
    canPermanentlyDelete,
}: AdminUsersViewProps) {
    return (
        <>
            <ResizableScrollRegion
                storageKey={
                    'safeai:users-table-height:v2'
                }
                label="список пользователей"
                preserveUpperContent
                upper={
                    <div
                        className={
                            'users-page__upper'
                        }
                    >
                        <div
                            className={
                                'users-page-header'
                            }
                        >
                            <div>
                                <h1>
                                    Пользователи
                                </h1>

                                <p
                                    className={
                                        'users-page-subtitle'
                                    }
                                >
                                    Управление пользователями системы
                                </p>
                            </div>

                            <button
                                type="button"
                                className={
                                    'users-create-button'
                                }
                                onClick={onCreate}
                                disabled={
                                    creating
                                    || hasPendingMutation
                                }
                            >
                                <span
                                    aria-hidden="true"
                                >
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
                            className={
                                'users-filter-bar'
                            }
                            aria-label={
                                'Фильтр пользователей по роли'
                            }
                        >
                            <FilterButton
                                active={
                                    filter === 'ALL'
                                }
                                label="Все"
                                count={
                                    statistics.total
                                }
                                disabled={
                                    hasPendingMutation
                                }
                                onClick={() =>
                                    onFilterChange(
                                        'ALL',
                                    )
                                }
                            />

                            <FilterButton
                                active={
                                    filter === 'ADMIN'
                                }
                                label="Администраторы"
                                count={
                                    statistics.administrators
                                }
                                disabled={
                                    hasPendingMutation
                                }
                                onClick={() =>
                                    onFilterChange(
                                        'ADMIN',
                                    )
                                }
                            />

                            <FilterButton
                                active={
                                    filter === 'USER'
                                }
                                label="Пользователи"
                                count={
                                    statistics.users
                                }
                                disabled={
                                    hasPendingMutation
                                }
                                onClick={() =>
                                    onFilterChange(
                                        'USER',
                                    )
                                }
                            />
                        </div>

                        <span
                            className={
                                'resizable-scroll-region__upper-boundary-marker'
                            }
                            data-resizable-upper-boundary
                            aria-hidden="true"
                        />

                        {loading && (
                            <LoadingState
                                message={
                                    'Загрузка пользователей...'
                                }
                            />
                        )}

                        {!loading
                            && loadError
                            && (
                                <ErrorState
                                    title={
                                        'Ошибка загрузки пользователей'
                                    }
                                    message={
                                        loadError
                                    }
                                    action={
                                        <button
                                            type="button"
                                            onClick={
                                                onReload
                                            }
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
                                totalPages={
                                    totalPages
                                }
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
                lowerClassName={
                    'users-table-card'
                }
                viewportClassName={
                    'users-table-scroll'
                }
                defaultHeight={430}
                minHeight={72}
                maxHeight={760}
                minUpperHeight={112}
            >
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
            </ResizableScrollRegion>
        </>
    )
}
