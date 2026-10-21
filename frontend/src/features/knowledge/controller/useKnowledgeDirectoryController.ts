// frontend/src/features/knowledge/controller/useKnowledgeDirectoryController.ts
// Состояние каталога баз знаний, фильтрация, пагинация и операции изменения.
import { useCallback, useEffect, useState } from 'react'
import { createKnowledgeBase, getKnowledgeBase, getKnowledgeBases, searchKnowledgeBases, updateKnowledgeBase } from '../../../entities/knowledge/api/knowledgeApi'
import type { KnowledgeBase } from '../../../entities/knowledge/api/knowledgeApi'
import { getApiErrorMessage } from '../../../shared/api/http'
import { useAuth } from '../../auth/state/useAuth'
import type { KnowledgeBaseFormValue, ActivityFilter, VisibilityFilter } from '../model/knowledgeDirectoryTypes'
import { useAutoClearMessage } from '../../../shared/lib/hooks/useAutoClearMessage'
import type { PageResponse } from '../../../shared/lib/page'

const PAGE_SIZE = 12
const SEARCH_DELAY_MS = 300

const EMPTY_PAGE: PageResponse<KnowledgeBase> = {
    content: [],
    page: 0,
    size: PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
}

export function useKnowledgeDirectoryController() {
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

    // Запрос к серверу запускается по подтверждённой строке, а не при каждом вводе символа.
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
            // Повторная загрузка выполняется через отменяемый запрос: устаревшие ответы не применяются.
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

    return { canManage, page, setPage, basePage, searchInput, setSearchInput, activity, setActivity,
        visibility, setVisibility, loading, error, success, createOpen, setCreateOpen,
        editing, setEditing, membersBase, setMembersBase, busy, activeFilters,
        refreshList, resetFilters, handleCreate, reloadEditingBase, handleUpdate }
}
