import {
    useCallback,
    useEffect,
    useState,
} from 'react'
import {
    createKnowledgeBase,
    getKnowledgeBases,
    updateKnowledgeBase,
} from '../api/knowledgeApi'
import type {
    KnowledgeBase,
} from '../api/knowledgeApi'
import {
    getApiErrorMessage,
} from '../api/http'
import {
    useAuth,
} from '../auth/AuthContext'
import KnowledgeBaseCard
    from '../components/knowledge/KnowledgeBaseCard'
import KnowledgeBaseFormModal
    from '../components/knowledge/KnowledgeBaseFormModal'
import type {
    KnowledgeBaseFormValue,
} from '../components/knowledge/KnowledgeBaseFormModal'
import KnowledgeMembersModal
    from '../components/knowledge/KnowledgeMembersModal'
import KnowledgePagination
    from '../components/knowledge/KnowledgePagination'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import {
    useAutoClearMessage,
} from '../hooks/useAutoClearMessage'
import type {
    PageResponse,
} from '../utils/page'
import './KnowledgePage.css'

const PAGE_SIZE = 24

const EMPTY_PAGE:
    PageResponse<KnowledgeBase> = {
    content: [],
    page: 0,
    size: PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
}

function KnowledgePage() {
    const {
        currentUser,
    } = useAuth()

    const canManage =
        currentUser?.roles.includes(
            'ADMIN',
        ) ?? false

    const [
        page,
        setPage,
    ] = useState(0)

    const [
        basePage,
        setBasePage,
    ] = useState(
        EMPTY_PAGE,
    )

    const [
        loading,
        setLoading,
    ] = useState(true)

    const [
        error,
        setError,
    ] = useState('')

    const [
        success,
        setSuccess,
    ] = useState('')

    const [
        createOpen,
        setCreateOpen,
    ] = useState(false)

    const [
        editing,
        setEditing,
    ] = useState<
        KnowledgeBase | null
    >(null)

    const [
        membersBase,
        setMembersBase,
    ] = useState<
        KnowledgeBase | null
    >(null)

    const [
        busy,
        setBusy,
    ] = useState(false)

    useAutoClearMessage(
        success,
        setSuccess,
        4_000,
    )

    const loadBases =
        useCallback(
            async (
                targetPage: number,
                signal?: AbortSignal,
            ) => {
                const response =
                    await getKnowledgeBases(
                        targetPage,
                        PAGE_SIZE,
                        {
                            signal,
                        },
                    )

                if (
                    response.totalPages > 0
                    && targetPage
                        >= response.totalPages
                ) {
                    setPage(
                        response.totalPages - 1,
                    )
                    return
                }

                setBasePage(response)
            },
            [],
        )

    useEffect(() => {
        const controller =
            new AbortController()

        async function load() {
            setLoading(true)
            setError('')

            try {
                await loadBases(
                    page,
                    controller.signal,
                )
            } catch (loadError) {
                if (
                    !controller.signal.aborted
                ) {
                    setBasePage(
                        EMPTY_PAGE,
                    )
                    setError(
                        getApiErrorMessage(
                            loadError,
                            'Не удалось загрузить базы знаний.',
                        ),
                    )
                }
            } finally {
                if (
                    !controller.signal.aborted
                ) {
                    setLoading(false)
                }
            }
        }

        void load()

        return () => {
            controller.abort()
        }
    }, [
        page,
        loadBases,
    ])

    async function reloadCurrentPage() {
        await loadBases(page)
    }

    function showSuccess(
        message: string,
    ) {
        setSuccess(message)
    }

    async function handleCreate(
        form: KnowledgeBaseFormValue,
    ) {
        setBusy(true)

        try {
            await createKnowledgeBase({
                name: form.name,
                description:
                    form.description.trim()
                    || null,
                visibility:
                    form.visibility,
            })

            setCreateOpen(false)
            setPage(0)
            await loadBases(0)

            showSuccess(
                'База знаний создана.',
            )
        } finally {
            setBusy(false)
        }
    }

    async function handleUpdate(
        form: KnowledgeBaseFormValue,
    ) {
        if (!editing) {
            return
        }

        setBusy(true)

        try {
            await updateKnowledgeBase(
                editing.id,
                {
                    name: form.name,
                    description:
                        form.description.trim()
                        || null,
                    visibility:
                        form.visibility,
                    enabled:
                        form.enabled,
                    expectedVersion:
                        editing.version,
                },
            )

            setEditing(null)
            await reloadCurrentPage()

            showSuccess(
                'База знаний обновлена.',
            )
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
                        Корпоративные базы знаний
                        текущей организации.
                    </p>
                </div>

                {canManage && (
                    <button
                        type="button"
                        onClick={() =>
                            setCreateOpen(true)
                        }
                    >
                        Создать базу знаний
                    </button>
                )}
            </header>

            {success && (
                <div
                    className="success"
                    role="status"
                    aria-live="polite"
                >
                    {success}
                </div>
            )}

            {error && (
                <ErrorState
                    message={error}
                    action={
                        <button
                            type="button"
                            onClick={() =>
                                void reloadCurrentPage()
                            }
                        >
                            Повторить
                        </button>
                    }
                />
            )}

            {!error && loading && (
                <LoadingState
                    message={
                        'Загрузка баз знаний...'
                    }
                />
            )}

            {!error
                && !loading
                && basePage.content.length
                    === 0
                && (
                    <EmptyState
                        title={
                            'Баз знаний пока нет'
                        }
                        message={
                            canManage
                                ? (
                                    'Создайте первую базу для '
                                    + 'корпоративных документов и инструкций.'
                                )
                                : (
                                    'Вам пока не доступна '
                                    + 'ни одна база знаний.'
                                )
                        }
                    />
                )}

            {!error
                && !loading
                && basePage.content.length
                    > 0
                && (
                    <>
                        <div className="knowledge-grid">
                            {
                                basePage.content.map(
                                    (knowledgeBase) => (
                                        <KnowledgeBaseCard
                                            key={
                                                knowledgeBase.id
                                            }
                                            knowledgeBase={
                                                knowledgeBase
                                            }
                                            canManage={
                                                canManage
                                            }
                                            onEdit={
                                                setEditing
                                            }
                                            onMembers={
                                                setMembersBase
                                            }
                                        />
                                    ),
                                )
                            }
                        </div>

                        <KnowledgePagination
                            page={
                                basePage.page
                            }
                            totalPages={
                                basePage.totalPages
                            }
                            totalElements={
                                basePage.totalElements
                            }
                            disabled={loading}
                            onPageChange={
                                setPage
                            }
                        />
                    </>
                )}

            {createOpen && (
                <KnowledgeBaseFormModal
                    title="Новая база знаний"
                    submitText="Создать"
                    initial={{
                        name: '',
                        description: '',
                        visibility:
                            'ORGANIZATION',
                        enabled: true,
                    }}
                    busy={busy}
                    onClose={() => {
                        if (!busy) {
                            setCreateOpen(false)
                        }
                    }}
                    onSubmit={
                        handleCreate
                    }
                />
            )}

            {editing && (
                <KnowledgeBaseFormModal
                    title="Настройки базы знаний"
                    submitText="Сохранить"
                    initial={{
                        name: editing.name,
                        description:
                            editing.description
                            ?? '',
                        visibility:
                            editing.visibility,
                        enabled:
                            editing.enabled,
                    }}
                    busy={busy}
                    allowEnabled
                    onClose={() => {
                        if (!busy) {
                            setEditing(null)
                        }
                    }}
                    onSubmit={
                        handleUpdate
                    }
                />
            )}

            {membersBase && (
                <KnowledgeMembersModal
                    knowledgeBase={
                        membersBase
                    }
                    onClose={() =>
                        setMembersBase(null)
                    }
                    onChanged={() =>
                        showSuccess(
                            'Доступ к базе знаний обновлён.',
                        )
                    }
                />
            )}
        </div>
    )
}

export default KnowledgePage
