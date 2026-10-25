// frontend/src/features/knowledge/ui/KnowledgeDirectoryWorkspace.tsx
// Представление каталога баз знаний, фильтров и диалогов управления.
import KnowledgeBaseCard from './KnowledgeBaseCard'
import KnowledgeBaseFormModal from './KnowledgeBaseFormModal'
import KnowledgeMembersModal from './KnowledgeMembersModal'
import KnowledgePagination from './KnowledgePagination'
import { EmptyState, ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import { useKnowledgeDirectoryController } from '../controller/useKnowledgeDirectoryController'
import type { ActivityFilter, VisibilityFilter } from '../model/knowledgeDirectoryTypes'

export function KnowledgeDirectoryWorkspace() {
    const { canManage, setPage, basePage, searchInput, setSearchInput, activity, setActivity,
        visibility, setVisibility, loading, error, success, setSuccess, createOpen, setCreateOpen, editing,
        setEditing, membersBase, setMembersBase, busy, activeFilters, refreshList, resetFilters,
        handleCreate, reloadEditingBase, handleUpdate } = useKnowledgeDirectoryController()

    return (
        <div className="page knowledge-page">
            <header className="knowledge-page__header">
                <div>
                    <h1>Базы знаний</h1>
                    <p className="muted">
                        Корпоративные базы знаний текущей организации.
                    </p>
                </div>
                {canManage && (
                    <button
                        type="button"
                        onClick={() => setCreateOpen(true)}
                    >
                        Создать базу знаний
                    </button>
                )}
            </header>

            <section className="knowledge-page__filters" aria-label="Поиск и фильтры баз знаний">
                <label className="knowledge-page__filter knowledge-page__filter--search">
                    <span>Поиск по названию</span>
                    <input
                        type="search"
                        placeholder="Название базы знаний"
                        value={searchInput}
                        maxLength={100}
                        onChange={(event) => {
                            setSearchInput(event.target.value)
                            setPage(0)
                        }}
                    />
                </label>
                <label className="knowledge-page__filter">
                    <span>Активность</span>
                    <select
                        value={activity}
                        onChange={(event) => {
                            setActivity(event.target.value as ActivityFilter)
                            setPage(0)
                        }}
                    >
                        <option value="all">Все статусы</option>
                        <option value="enabled">Активные</option>
                        {canManage && <option value="disabled">Отключённые</option>}
                    </select>
                </label>
                <label className="knowledge-page__filter">
                    <span>Доступность</span>
                    <select
                        value={visibility}
                        onChange={(event) => {
                            setVisibility(event.target.value as VisibilityFilter)
                            setPage(0)
                        }}
                    >
                        <option value="all">Любая</option>
                        <option value="ORGANIZATION">Вся организация</option>
                        <option value="MEMBERS">По приглашению</option>
                    </select>
                </label>
                <button
                    type="button"
                    className="secondary-button knowledge-page__reset"
                    onClick={resetFilters}
                    disabled={!activeFilters && searchInput === ''}
                >
                    Сбросить
                </button>
            </section>

            {success && (
                <div className="success" role="status" aria-live="polite">
                    {success}
                </div>
            )}

            <div className="knowledge-page__results">
                {error && (
                    <ErrorState
                        message={error}
                        action={
                            <button type="button" onClick={refreshList}>
                                Повторить
                            </button>
                        }
                    />
                )}

                {!error && loading && (
                    <LoadingState message="Загрузка баз знаний..." />
                )}

                {!error && !loading && basePage.content.length === 0 && (
                    <EmptyState
                        title={activeFilters
                            ? 'Ничего не найдено'
                            : 'Баз знаний пока нет'}
                        message={activeFilters
                            ? 'По выбранным условиям доступные базы не найдены.'
                            : canManage
                                ? 'Создайте первую базу для корпоративных документов и инструкций.'
                                : 'Вам пока не доступна ни одна база знаний.'}
                        action={activeFilters
                            ? (
                                <button type="button" onClick={resetFilters}>
                                    Сбросить фильтры
                                </button>
                            )
                            : undefined}
                    />
                )}

                {!error && !loading && basePage.content.length > 0 && (
                    <>
                        <div className="knowledge-grid">
                            {basePage.content.map((knowledgeBase) => (
                                <KnowledgeBaseCard
                                    key={knowledgeBase.id}
                                    knowledgeBase={knowledgeBase}
                                    canManage={canManage}
                                    onEdit={setEditing}
                                    onMembers={setMembersBase}
                                />
                            ))}
                        </div>
                        <KnowledgePagination
                            page={basePage.page}
                            totalPages={basePage.totalPages}
                            totalElements={basePage.totalElements}
                            disabled={loading}
                            onPageChange={setPage}
                            singlePageMessage={activeFilters
                                ? 'Все найденные базы показаны'
                                : 'Все базы показаны'}
                        />
                    </>
                )}
            </div>

            {createOpen && (
                <KnowledgeBaseFormModal
                    title="Новая база знаний"
                    submitText="Создать"
                    initial={{
                        name: '',
                        description: '',
                        visibility: 'ORGANIZATION',
                        enabled: true,
                    }}
                    busy={busy}
                    onClose={() => {
                        if (!busy) setCreateOpen(false)
                    }}
                    onSubmit={handleCreate}
                />
            )}

            {editing && (
                <KnowledgeBaseFormModal
                    key={`${editing.id}:${editing.version}`}
                    title="Настройки базы знаний"
                    submitText="Сохранить"
                    initial={{
                        name: editing.name,
                        description: editing.description ?? '',
                        visibility: editing.visibility,
                        enabled: editing.enabled,
                    }}
                    busy={busy}
                    allowEnabled
                    onReload={reloadEditingBase}
                    onClose={() => {
                        if (!busy) setEditing(null)
                    }}
                    onSubmit={handleUpdate}
                />
            )}

            {membersBase && (
                <KnowledgeMembersModal
                    knowledgeBase={membersBase}
                    onClose={() => setMembersBase(null)}
                    onChanged={() => {
                        refreshList()
                        setSuccess('Доступ к базе знаний обновлён.')
                    }}
                />
            )}
        </div>
    )
}
