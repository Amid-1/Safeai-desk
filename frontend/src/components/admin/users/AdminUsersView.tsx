// frontend/src/components/admin/users/AdminUsersView.tsx
import { useState } from 'react'
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
import Modal from '../../Modal'
import UserRoleBadge from '../UserRoleBadge'
import ResizableScrollRegion
    from '../../ResizableScrollRegion'
import {
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
    status: 'ALL' | 'ACTIVE' | 'DISABLED'
    searchDraft: string
    appliedSearch: string
    totalMatched: number
    onSearchDraftChange: (value: string) => void
    onApplySearch: () => void
    onClearSearch: () => void
    onSelectSearchSuggestion: (user: User) => void
    onStatusChange: (value: 'ALL' | 'ACTIVE' | 'DISABLED') => void
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
    status,
    searchDraft,
    appliedSearch,
    totalMatched,
    onSearchDraftChange,
    onApplySearch,
    onClearSearch,
    onSelectSearchSuggestion,
    onStatusChange,
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
    // Информация о роли — только для чтения. Права на изменение
    // остаются у серверного SUPER_ADMIN, включая защиту своей роли.
    const [roleInfoUser, setRoleInfoUser] = useState<User | null>(null)
    const roleInfoIsSelf = roleInfoUser?.id === currentUser.id
    const roleInfoIsProtected =
        roleInfoUser?.roles.includes('SUPER_ADMIN') ?? false

    const [suggestionsOpen, setSuggestionsOpen] = useState(false)
    const showSuggestions = suggestionsOpen
        && !loading && !loadError
        && searchDraft.trim().length >= 2
        && searchDraft.trim() === appliedSearch
        && users.length > 0
    const suggestionRank = (user: User): number => {
        const fragment = searchDraft.trim().toLocaleLowerCase()
        if (user.email.toLocaleLowerCase().startsWith(fragment)) return 0
        if ((user.fullName || '').toLocaleLowerCase().split(/\s+/)
            .some(part => part.startsWith(fragment))) return 1
        return 2
    }
    const suggestions = [...users].sort((a, b) =>
        suggestionRank(a) - suggestionRank(b)).slice(0, 8)
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
                                    {currentUserIsSuperAdmin
                                        ? 'Управление пользователями всех организаций'
                                        : 'Управление сотрудниками вашей организации'}
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
                            title="Общие количества в выбранной организации или области доступа; поиск и статус на эти числа не влияют."
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

                        <form
                            className="users-directory-search"
                            role="search"
                            onBlur={(event) => {
                                if (!event.currentTarget.contains(event.relatedTarget)) {
                                    setSuggestionsOpen(false)
                                }
                            }}
                            onKeyDown={(event) => {
                                if (event.key === 'Escape') setSuggestionsOpen(false)
                            }}
                            onSubmit={(event) => {
                                setSuggestionsOpen(false)
                                event.preventDefault()
                                onApplySearch()
                            }}
                        >
                            <label htmlFor="users-directory-query">
                                Поиск по имени, фамилии или email
                            </label>
                            <div className="users-directory-search__row">
                                <input
                                    id="users-directory-query"
                                    type="search"
                                    maxLength={120}
                                    value={searchDraft}
                                    onChange={(event) => {
                                        const value = event.target.value
                                        onSearchDraftChange(value)
                                        setSuggestionsOpen(Boolean(value.trim()))
                                    }}
                                    onFocus={() => setSuggestionsOpen(true)}
                                    placeholder="Имя, фамилия или email"
                                    disabled={hasPendingMutation}
                                />
                                <label htmlFor="users-directory-status" className="users-directory-search__status-label">
                                    Статус
                                </label>
                                <select
                                    id="users-directory-status"
                                    value={status}
                                    onChange={(event) => onStatusChange(
                                        event.target.value as 'ALL' | 'ACTIVE' | 'DISABLED',
                                    )}
                                    disabled={hasPendingMutation}
                                >
                                    <option value="ALL">Все статусы</option>
                                    <option value="ACTIVE">Включённые</option>
                                    <option value="DISABLED">Отключённые</option>
                                </select>
                                <button type="submit" disabled={hasPendingMutation || loading}>
                                    Найти
                                </button>
                                {(searchDraft || appliedSearch) && (
                                    <button
                                        type="button"
                                        className="secondary-button"
                                        onClick={onClearSearch}
                                        disabled={hasPendingMutation}
                                    >
                                        Очистить
                                    </button>
                                )}
                            </div>
                            {showSuggestions && (
                                <div
                                    id="users-directory-suggestions"
                                    className="users-directory-search__suggestions"
                                    aria-label="Подходящие зарегистрированные пользователи"
                                >
                                    <span className="users-directory-search__suggestions-heading">
                                        Совпадения · выберите пользователя
                                    </span>
                                    {suggestions.map(user => (
                                        <button
                                            key={user.id}
                                            type="button"
                                            className="users-directory-search__suggestion"
                                            onClick={() => {
                                                onSelectSearchSuggestion(user)
                                                setSuggestionsOpen(false)
                                            }}
                                        >
                                            <strong>{user.fullName || user.email}</strong>
                                            {user.fullName && <small>{user.email}</small>}
                                        </button>
                                    ))}
                                </div>
                            )}
                            <span className="users-directory-search__summary" role="status">
                                Найдено по фильтрам: {totalMatched}
                                {' · Всего пользователей: '}{statistics.total}
                                {searchDraft.trim() !== appliedSearch && (
                                    <> · {searchDraft.trim().length === 1
                                        ? 'Введите ещё символ или нажмите «Найти»'
                                        : 'Обновляем результаты поиска…'}</>
                                )}
                                {' · Сначала недавно созданные'}
                            </span>
                        </form>

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
                    users.length === 0 ? 'users-table-card users-table-card--empty' : 'users-table-card'
                }
                viewportClassName={
                    'users-table-scroll'
                }
                defaultHeight={430}
                autoFitHeight={
                    !loading && !loadError
                        ? users.length > 0
                            ? 15 + 59 + users.length * 73 + 56
                            : 175
                        : undefined
                }
                minHeight={72}
                maxHeight={760}
                minUpperHeight={112}
            >
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
            </ResizableScrollRegion>

            {roleInfoUser && (
                <Modal
                    title="Системная роль пользователя"
                    size="sm"
                    onClose={() => setRoleInfoUser(null)}
                >
                    <div className="users-role-info-dialog">
                        <p className="modal-subtitle">
                            {roleInfoUser.email}
                        </p>
                        <div className="users-role-info-dialog__current">
                            <strong>Текущая роль</strong>
                            <UserRoleBadge role={roleInfoUser.roles[0]} />
                        </div>
                        <p role="note" className="users-security-note">
                            {!currentUserIsSuperAdmin
                                ? 'Изменять системные роли пользователей может только SUPER_ADMIN. Обратитесь к администратору платформы для назначения роли ADMIN.'
                                : roleInfoIsSelf
                                    ? 'Собственную системную роль нельзя изменять через управление пользователями.'
                                    : roleInfoIsProtected
                                        ? 'Роль SUPER_ADMIN защищена и не изменяется через обычное управление пользователями.'
                                        : 'Изменение системной роли этой учетной записи сейчас недоступно.'}
                        </p>
                        <p className="muted">
                            Проверка прав выполняется на сервере. Просмотр роли не изменяет учетную запись и не отзывает сессии.
                        </p>
                        <div className="modal-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                onClick={() => setRoleInfoUser(null)}
                            >
                                Закрыть
                            </button>
                        </div>
                    </div>
                </Modal>
            )}
        </>
    )
}
