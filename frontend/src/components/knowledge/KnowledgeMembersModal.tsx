import {
    useCallback,
    useEffect,
    useMemo,
    useState,
} from 'react'
import {
    addKnowledgeBaseMember,
    getKnowledgeBaseMembers,
    removeKnowledgeBaseMember,
    searchKnowledgeMemberCandidates,
    updateKnowledgeBaseMember,
} from '../../api/knowledgeApi'
import type {
    KnowledgeBase,
    KnowledgeBaseAccessLevel,
    KnowledgeBaseMember,
    KnowledgeMemberCandidate,
} from '../../api/knowledgeApi'
import {
    getApiErrorMessage,
} from '../../api/http'
import type {
    PageResponse,
} from '../../utils/page'
import ConfirmDialog
    from '../ConfirmDialog'
import Modal from '../Modal'
import {
    EmptyState,
    LoadingState,
} from '../StateBlock'
import KnowledgePagination
    from './KnowledgePagination'

const MEMBER_PAGE_SIZE = 50

const EMPTY_MEMBER_PAGE:
    PageResponse<KnowledgeBaseMember> = {
    content: [],
    page: 0,
    size: MEMBER_PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
}

type KnowledgeMembersModalProps = {
    knowledgeBase: KnowledgeBase
    onClose: () => void
    onChanged: () => void
}

function KnowledgeMembersModal({
    knowledgeBase,
    onClose,
    onChanged,
}: KnowledgeMembersModalProps) {
    const [
        memberPage,
        setMemberPage,
    ] = useState(
        EMPTY_MEMBER_PAGE,
    )

    const [
        currentPage,
        setCurrentPage,
    ] = useState(0)

    const [
        loading,
        setLoading,
    ] = useState(true)

    const [
        error,
        setError,
    ] = useState('')

    const [
        query,
        setQuery,
    ] = useState('')

    const [
        candidates,
        setCandidates,
    ] = useState<
        KnowledgeMemberCandidate[]
    >([])

    const [
        candidateLoading,
        setCandidateLoading,
    ] = useState(false)

    const [
        busyUserId,
        setBusyUserId,
    ] = useState<
        string | null
    >(null)

    const [
        removeTarget,
        setRemoveTarget,
    ] = useState<
        KnowledgeBaseMember | null
    >(null)

    const memberIds =
        useMemo(
            () =>
                new Set(
                    memberPage.content.map(
                        (member) =>
                            member.userId,
                    ),
                ),
            [memberPage.content],
        )

    const loadMembers =
        useCallback(
            async (
                targetPage: number,
                signal?: AbortSignal,
            ) => {
                const response =
                    await getKnowledgeBaseMembers(
                        knowledgeBase.id,
                        targetPage,
                        MEMBER_PAGE_SIZE,
                        {
                            signal,
                        },
                    )

                if (
                    response.totalPages > 0
                    && targetPage
                        >= response.totalPages
                ) {
                    setCurrentPage(
                        response.totalPages - 1,
                    )
                    return
                }

                setMemberPage(response)
            },
            [knowledgeBase.id],
        )

    useEffect(() => {
        const controller =
            new AbortController()

        async function load() {
            setLoading(true)
            setError('')

            try {
                await loadMembers(
                    currentPage,
                    controller.signal,
                )
            } catch (loadError) {
                if (
                    !controller.signal.aborted
                ) {
                    setError(
                        getApiErrorMessage(
                            loadError,
                            'Не удалось загрузить участников.',
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
        currentPage,
        loadMembers,
    ])

    async function searchCandidates() {
        setCandidateLoading(true)
        setError('')

        try {
            const result =
                await searchKnowledgeMemberCandidates(
                    query,
                    20,
                )

            setCandidates(result)
        } catch (searchError) {
            setError(
                getApiErrorMessage(
                    searchError,
                    'Не удалось найти пользователей.',
                ),
            )
        } finally {
            setCandidateLoading(false)
        }
    }

    async function addCandidate(
        candidate:
            KnowledgeMemberCandidate,
    ) {
        setBusyUserId(
            candidate.userId,
        )
        setError('')

        try {
            await addKnowledgeBaseMember(
                knowledgeBase.id,
                {
                    userId:
                        candidate.userId,
                    accessLevel:
                        'VIEWER',
                },
            )

            setCandidates(
                (current) =>
                    current.filter(
                        (item) =>
                            item.userId
                            !== candidate.userId,
                    ),
            )

            await loadMembers(
                currentPage,
            )
            onChanged()
        } catch (saveError) {
            setError(
                getApiErrorMessage(
                    saveError,
                    'Не удалось добавить участника.',
                ),
            )
        } finally {
            setBusyUserId(null)
        }
    }

    async function changeAccess(
        member: KnowledgeBaseMember,
        accessLevel:
            KnowledgeBaseAccessLevel,
    ) {
        setBusyUserId(member.userId)
        setError('')

        try {
            await updateKnowledgeBaseMember(
                knowledgeBase.id,
                member.userId,
                {
                    accessLevel,
                    expectedVersion:
                        member.version,
                },
            )

            await loadMembers(
                currentPage,
            )
            onChanged()
        } catch (saveError) {
            setError(
                getApiErrorMessage(
                    saveError,
                    'Не удалось изменить доступ.',
                ),
            )
        } finally {
            setBusyUserId(null)
        }
    }

    async function removeMember(
        member: KnowledgeBaseMember,
    ) {
        setBusyUserId(member.userId)
        setError('')

        try {
            await removeKnowledgeBaseMember(
                knowledgeBase.id,
                member.userId,
                member.version,
            )

            setRemoveTarget(null)

            const remaining =
                memberPage.totalElements - 1

            if (
                remaining > 0
                && memberPage.content.length
                    === 1
                && currentPage > 0
            ) {
                setCurrentPage(
                    currentPage - 1,
                )
            } else {
                await loadMembers(
                    currentPage,
                )
            }

            onChanged()
        } catch (removeError) {
            setError(
                getApiErrorMessage(
                    removeError,
                    'Не удалось удалить участника.',
                ),
            )
        } finally {
            setBusyUserId(null)
        }
    }

    return (
        <>
            <Modal
                title={
                    `Доступ: ${knowledgeBase.name}`
                }
                onClose={onClose}
                size="lg"
            >
                <p className="modal-subtitle">
                    Участники — resource-level
                    permissions. Они не создают
                    новые глобальные роли.
                </p>

                <div className="knowledge-member-search">
                    <label>
                        Найти пользователя организации
                        <input
                            value={query}
                            maxLength={255}
                            onChange={(event) =>
                                setQuery(
                                    event.target.value,
                                )
                            }
                            onKeyDown={(event) => {
                                if (
                                    event.key
                                    === 'Enter'
                                ) {
                                    event.preventDefault()
                                    void searchCandidates()
                                }
                            }}
                        />
                    </label>

                    <button
                        type="button"
                        className="secondary-button"
                        disabled={
                            candidateLoading
                        }
                        onClick={() =>
                            void searchCandidates()
                        }
                    >
                        {
                            candidateLoading
                                ? 'Поиск...'
                                : 'Найти'
                        }
                    </button>
                </div>

                {candidates.length > 0 && (
                    <div className="knowledge-candidates">
                        {
                            candidates
                                .filter(
                                    (candidate) =>
                                        !memberIds.has(
                                            candidate.userId,
                                        ),
                                )
                                .map(
                                    (candidate) => (
                                        <div
                                            key={
                                                candidate.userId
                                            }
                                            className={
                                                'knowledge-candidate'
                                            }
                                        >
                                            <div>
                                                <strong>
                                                    {
                                                        candidate.fullName
                                                        ?? candidate.email
                                                    }
                                                </strong>
                                                <span>
                                                    {
                                                        candidate.email
                                                    }
                                                </span>
                                            </div>

                                            <button
                                                type="button"
                                                disabled={
                                                    busyUserId
                                                    === candidate.userId
                                                }
                                                onClick={() =>
                                                    void addCandidate(
                                                        candidate,
                                                    )
                                                }
                                            >
                                                Добавить
                                            </button>
                                        </div>
                                    ),
                                )
                        }
                    </div>
                )}

                {error && (
                    <div
                        className="error"
                        role="alert"
                    >
                        {error}
                    </div>
                )}

                {loading
                    ? (
                        <LoadingState
                            variant="inline"
                            message={
                                'Загрузка участников...'
                            }
                        />
                    )
                    : memberPage.content.length
                        === 0
                        ? (
                            <EmptyState
                                variant="inline"
                                message={
                                    'Явных участников нет.'
                                }
                            />
                        )
                        : (
                            <div className="admin-table-wrapper">
                                <table className="admin-table knowledge-members-table">
                                    <thead>
                                        <tr>
                                            <th>
                                                Пользователь
                                            </th>
                                            <th>
                                                Доступ
                                            </th>
                                            <th>
                                                Версия
                                            </th>
                                            <th>
                                                Действия
                                            </th>
                                        </tr>
                                    </thead>

                                    <tbody>
                                        {
                                            memberPage.content.map(
                                                (member) => (
                                                    <tr
                                                        key={
                                                            member.userId
                                                        }
                                                    >
                                                        <td>
                                                            <strong>
                                                                {
                                                                    member.fullName
                                                                    ?? 'Без имени'
                                                                }
                                                            </strong>
                                                            <br />
                                                            <span className="muted">
                                                                {
                                                                    member.email
                                                                }
                                                            </span>
                                                        </td>

                                                        <td>
                                                            <select
                                                                value={
                                                                    member.accessLevel
                                                                }
                                                                disabled={
                                                                    busyUserId
                                                                    === member.userId
                                                                }
                                                                onChange={
                                                                    (event) =>
                                                                        void changeAccess(
                                                                            member,
                                                                            event.target.value as KnowledgeBaseAccessLevel,
                                                                        )
                                                                }
                                                            >
                                                                <option value="VIEWER">
                                                                    VIEWER
                                                                </option>
                                                                <option value="EDITOR">
                                                                    EDITOR
                                                                </option>
                                                                <option value="OWNER">
                                                                    OWNER
                                                                </option>
                                                            </select>
                                                        </td>

                                                        <td>
                                                            {
                                                                member.version
                                                            }
                                                        </td>

                                                        <td>
                                                            <button
                                                                type="button"
                                                                className="danger-button"
                                                                disabled={
                                                                    busyUserId
                                                                    === member.userId
                                                                }
                                                                onClick={() =>
                                                                    setRemoveTarget(
                                                                        member,
                                                                    )
                                                                }
                                                            >
                                                                Удалить доступ
                                                            </button>
                                                        </td>
                                                    </tr>
                                                ),
                                            )
                                        }
                                    </tbody>
                                </table>
                            </div>
                        )}

                <KnowledgePagination
                    page={
                        memberPage.page
                    }
                    totalPages={
                        memberPage.totalPages
                    }
                    totalElements={
                        memberPage.totalElements
                    }
                    disabled={loading}
                    onPageChange={
                        setCurrentPage
                    }
                />

                <p className="knowledge-members-note">
                    В V38 VIEWER/EDITOR/OWNER
                    задают membership. Права
                    EDITOR/OWNER на документы
                    будут активированы в V39.
                </p>

                <div className="modal-actions">
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={onClose}
                    >
                        Закрыть
                    </button>
                </div>
            </Modal>

            {removeTarget && (
                <ConfirmDialog
                    title="Удалить доступ"
                    message={
                        `Удалить ${removeTarget.email} `
                        + 'из этой базы знаний?'
                    }
                    confirmText="Удалить доступ"
                    danger
                    loading={
                        busyUserId
                        === removeTarget.userId
                    }
                    onCancel={() =>
                        setRemoveTarget(null)
                    }
                    onConfirm={() =>
                        removeMember(
                            removeTarget,
                        )
                    }
                    onConfirmError={
                        () => undefined
                    }
                />
            )}
        </>
    )
}

export default KnowledgeMembersModal
