// frontend/src/features/admin-users/ui/adminUsersView/UsersDirectoryUpper.tsx
import { useState } from 'react'
import type { User } from '../../../../entities/user/api/userApi'
import { ErrorState, LoadingState } from '../../../../shared/ui/StateBlock'
import { FilterButton } from '../AdminUsersUi'
import type { AdminUsersViewProps } from '../adminUsersViewTypes'

export function UsersDirectoryUpper({
    users,
    statistics,
    loadError,
    mutationError,
    success,
    loading,
    creating,
    hasPendingMutation,
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
    currentUserIsSuperAdmin,
    onCreate,
    onFilterChange,
    onReload,
}: AdminUsersViewProps) {
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

    )
}
