// frontend/src/features/admin-users/ui/adminUsersView/UsersDirectoryUpper.tsx
import { useEffect, useRef, useState } from 'react'
import type { User } from '../../../../entities/user/api/userApi'
import { ErrorState, LoadingState } from '../../../../shared/ui/StateBlock'
import { FilterButton } from '../AdminUsersUi'
import type { AdminUsersViewProps } from '../adminUsersViewTypes'
import { OrganizationDirectoryFilter } from '../../../../entities/organization/ui/OrganizationDirectoryFilter'
import { AdminPageHeader } from '../../../../shared/ui/AdminPageHeader'

export function UsersDirectoryUpper({
    users,
    statistics,
    statisticsAvailable = true,
    linkedDetailsError,
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
    organizationId,
    onOrganizationChange,
}: AdminUsersViewProps) {
    const [suggestionsOpen, setSuggestionsOpen] = useState(false)
    const searchForm = useRef<HTMLFormElement>(null)
    const outsidePointer = useRef(false)
    const showSuggestions = suggestionsOpen
        && !loading && !loadError
        && searchDraft.trim().length >= 1
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
    useEffect(() => {
        if (!showSuggestions) return
        const isOutside = (event: Event) =>
            event.target instanceof Node && !searchForm.current?.contains(event.target)
        const onPointerDown = (event: PointerEvent) => {
            outsidePointer.current = isOutside(event)
        }
        const onClick = (event: MouseEvent) => {
            outsidePointer.current = false
            if (isOutside(event)) setSuggestionsOpen(false)
        }
        const resetPointer = () => { outsidePointer.current = false }
        // Keep the inline list in place until the outside click has reached
        // its target. Blur during pointerdown must not move a table row.
        document.addEventListener('pointerdown', onPointerDown, true)
        document.addEventListener('click', onClick)
        document.addEventListener('pointercancel', resetPointer, true)
        document.addEventListener('keydown', resetPointer, true)
        return () => {
            document.removeEventListener('pointerdown', onPointerDown, true)
            document.removeEventListener('click', onClick)
            document.removeEventListener('pointercancel', resetPointer, true)
            document.removeEventListener('keydown', resetPointer, true)
            resetPointer()
        }
    }, [showSuggestions])
    return (
                    <div
                        className={
                            'users-page__upper'
                        }
                    >
                        <AdminPageHeader
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
                        </AdminPageHeader>

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
                                    loading || loadError || !statisticsAvailable ? '—' : statistics.total
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

                            {currentUserIsSuperAdmin && <FilterButton active={filter === 'SUPER_ADMIN'}
                                label="Суперадминистраторы"
                                count={loading || loadError || !statisticsAvailable
                                    ? '—' : statistics.superAdministrators ?? '—'}
                                disabled={hasPendingMutation} onClick={() => onFilterChange('SUPER_ADMIN')} />}
                            <FilterButton
                                active={
                                    filter === 'ADMIN'
                                }
                                label="Администраторы"
                                count={
                                    loading || loadError || !statisticsAvailable ? '—' : statistics.administrators
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
                                    loading || loadError || !statisticsAvailable ? '—' : statistics.users
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

                        {currentUserIsSuperAdmin && onOrganizationChange && <OrganizationDirectoryFilter
                            value={organizationId ?? ''} onChange={onOrganizationChange} disabled={hasPendingMutation} />}

                        <form
                            ref={searchForm}
                            className="users-directory-search"
                            role="search"
                            onBlur={(event) => {
                                if (!outsidePointer.current && !event.currentTarget.contains(event.relatedTarget)) {
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
                                Найдено по фильтрам: {loading || loadError ? '—' : totalMatched}
                                {' · Всего пользователей: '}
                                {loading || loadError || !statisticsAvailable ? '—' : statistics.total}
                                {searchDraft.trim() !== appliedSearch && (
                                    <> · Обновляем результаты поиска…</>
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

                        {linkedDetailsError && <p role="alert" className="error">{linkedDetailsError}</p>}
                        {!loading && !loadError && !statisticsAvailable && (
                            <p role="status">Не удалось получить общие итоги. Список доступен.{' '}
                                <button type="button" className="secondary-button" onClick={onReload}>
                                    Обновить итоги
                                </button>
                            </p>
                        )}
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
