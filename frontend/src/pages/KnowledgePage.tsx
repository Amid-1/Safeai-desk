import {
    useCallback,
    useEffect,
    useState,
} from 'react'
import {
    createKnowledgeBase,
    getKnowledgeBase,
    getKnowledgeBases,
    searchKnowledgeBases,
    updateKnowledgeBase,
} from '../api/knowledgeApi'
import type {
    KnowledgeBase,
    KnowledgeBaseVisibility,
} from '../api/knowledgeApi'
import { getApiErrorMessage } from '../api/http'
import { useAuth } from '../auth/useAuth'
import KnowledgeBaseCard from '../components/knowledge/KnowledgeBaseCard'
import KnowledgeBaseFormModal from '../components/knowledge/KnowledgeBaseFormModal'
import type {
    KnowledgeBaseFormValue,
} from '../components/knowledge/KnowledgeBaseFormModal'
import KnowledgeMembersModal from '../components/knowledge/KnowledgeMembersModal'
import KnowledgePagination from '../components/knowledge/KnowledgePagination'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import { useAutoClearMessage } from '../hooks/useAutoClearMessage'
import type { PageResponse } from '../utils/page'
import './KnowledgePage.css'

const PAGE_SIZE = 12
const SEARCH_DELAY_MS = 300

type ActivityFilter = 'all' | 'enabled' | 'disabled'
type VisibilityFilter = 'all' | KnowledgeBaseVisibility

const EMPTY_PAGE: PageResponse<KnowledgeBase> = {
    content: [],
    page: 0,
    size: PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
}

function KnowledgePage() {
    const { currentUser } = useAuth()
    const canManage = currentUser?.roles.includes('ADMIN') ?? false

    const [page, setPage] = useState(0)
    const [basePage, setBasePage] = useState(EMPTY_PAGE)
    const [searchInput, setSearchInput] = useState('')
    const [searchQuery, setSearchQuery] = useState('')
    const [activity, setActivity] = useState<ActivityFilter>('all')
    const [visibility, setVisibility] = useState<VisibilityFilter>('all')
    const [reloadVersion, setReloadVersion] = useState(0)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [success, setSuccess] = useState('')
    const [createOpen, setCreateOpen] = useState(false)
    const [editing, setEditing] = useState<KnowledgeBase | null>(null)
    const [membersBase, setMembersBase] = useState<KnowledgeBase | null>(null)
    const [busy, setBusy] = useState(false)

    useAutoClearMessage(success, setSuccess, 4_000)

    // Only the committed query triggers a server request. No partial-page filtering.
    useEffect(() => {
        const timeout = window.setTimeout(
            () => setSearchQuery(searchInput.trim()),
            SEARCH_DELAY_MS,
        )
        return () => window.clearTimeout(timeout)
    }, [searchInput])

    const activeFilters = searchQuery !== ''
        || activity !== 'all'
        || visibility !== 'all'

    const loadBases = useCallback(async (
        targetPage: number,
        signal: AbortSignal,
    ) => {
        const result = activeFilters
            ? await searchKnowledgeBases(
                targetPage,
                PAGE_SIZE,
                {
                    query: searchQuery || undefined,
                    enabled: activity === 'all'
                        ? undefined
                        : activity === 'enabled',
                    visibility: visibility === 'all'
                        ? undefined
                        : visibility,
                },
                { signal },
            )
            : await getKnowledgeBases(targetPage, PAGE_SIZE, { signal })

        if (signal.aborted) return

        if (targetPage > 0 && targetPage >= result.totalPages) {
            setPage(Math.max(0, result.totalPages - 1))
            return
        }
        setBasePage(result)
    }, [activeFilters, activity, searchQuery, visibility])

    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        setError('')

        void loadBases(page, controller.signal)
            .catch((loadError: unknown) => {
                if (controller.signal.aborted) return
                setBasePage(EMPTY_PAGE)
                setError(getApiErrorMessage(
                    loadError,
                    'Не удалось загрузить базы знаний.',
                ))
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false)
            })

        return () => controller.abort()
    }, [page, loadBases, reloadVersion, currentUser?.id])

    function refreshList() {
        setReloadVersion((previous) => previous + 1)
    }

    function resetFilters() {
        setSearchInput('')
        setSearchQuery('')
        setActivity('all')
        setVisibility('all')
        setPage(0)
    }

    async function handleCreate(form: KnowledgeBaseFormValue) {
        setBusy(true)
        try {
            await createKnowledgeBase({
                name: form.name,
                description: form.description.trim() || null,
                visibility: form.visibility,
            })
            setCreateOpen(false)
            // Do not bypass the normal abortable loader (prevents stale responses).
            setPage(0)
            refreshList()
            setSuccess('База знаний создана.')
        } finally {
            setBusy(false)
        }
    }

    async function reloadEditingBase(): Promise<void> {
        if (!editing) return
        const fresh = await getKnowledgeBase(editing.id)
        setEditing(fresh)
        refreshList()
    }

    async function handleUpdate(form: KnowledgeBaseFormValue) {
        if (!editing) return
        setBusy(true)
        try {
            await updateKnowledgeBase(editing.id, {
                name: form.name,
                description: form.description.trim() || null,
                visibility: form.visibility,
                enabled: form.enabled,
                expectedVersion: editing.version,
            })
            setEditing(null)
            refreshList()
            setSuccess('База знаний обновлена.')
        } finally {
            setBusy(false)
        }
    }

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

export default KnowledgePage
