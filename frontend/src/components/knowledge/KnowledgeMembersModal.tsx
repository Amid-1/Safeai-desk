import {
    useCallback,
    useEffect,
    useMemo,
    useRef,
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
import { useAuth } from '../../auth/useAuth'
import './KnowledgeMembersModal.css'
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

const ACCESS_LEVEL_LABEL:
    Record<KnowledgeBaseAccessLevel, string> = {
    VIEWER: 'Только просмотр',
    EDITOR: 'Просмотр и загрузка',
    OWNER: 'Полное управление',
}

const ACCESS_LEVEL_HINT:
    Record<KnowledgeBaseAccessLevel, string> = {
    VIEWER: 'Читает, скачивает и использует документы в AI-чате.',
    EDITOR: 'Также загружает документы и новые версии.',
    OWNER: 'Также меняет настройки и управляет доступом.',
}

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
    const { currentUser } = useAuth()

    const mutationRef = useRef(false)
    const [success, setSuccess] = useState('')
    const [refreshKey, setRefreshKey] = useState(0)
    const [accessTarget, setAccessTarget] = useState<{
        member: KnowledgeBaseMember
        accessLevel: KnowledgeBaseAccessLevel
    } | null>(null)

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
        refreshKey,
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

    function beginMutation(userId: string): boolean {
        if (mutationRef.current) {
            return false
        }
        mutationRef.current = true
        setBusyUserId(userId)
        setError('')
        setSuccess('')
        return true
    }

    function endMutation() {
        mutationRef.current = false
        setBusyUserId(null)
    }

    async function reloadAfterMutation() {
        try {
            await loadMembers(currentPage)
        } catch (refreshError) {
            setError(getApiErrorMessage(
                refreshError,
                'Изменение сохранено, но список не обновился. Нажмите «Обновить».',
            ))
        }
        onChanged()
    }

    async function addCandidate(candidate: KnowledgeMemberCandidate) {
        if (!beginMutation(candidate.userId)) return
        try {
            await addKnowledgeBaseMember(knowledgeBase.id, {
                userId: candidate.userId,
                accessLevel: 'VIEWER',
            })
            setCandidates(current => current.filter(
                item => item.userId !== candidate.userId,
            ))
            setSuccess(`Участник ${candidate.email} добавлен с правами просмотра.`)
            await reloadAfterMutation()
        } catch (saveError) {
            setError(getApiErrorMessage(saveError, 'Не удалось добавить участника.'))
        } finally {
            endMutation()
        }
    }

    async function changeAccess(
        member: KnowledgeBaseMember,
        accessLevel: KnowledgeBaseAccessLevel,
    ): Promise<boolean> {
        if (accessLevel === member.accessLevel) return true
        if (!beginMutation(member.userId)) return false
        try {
            await updateKnowledgeBaseMember(knowledgeBase.id, member.userId, {
                accessLevel,
                expectedVersion: member.version,
            })
            setSuccess(`Права ${member.email} изменены: ${ACCESS_LEVEL_LABEL[accessLevel]}.`)
            await reloadAfterMutation()
            return true
        } catch (saveError) {
            setError(getApiErrorMessage(saveError, 'Не удалось изменить доступ.'))
            return false
        } finally {
            endMutation()
        }
    }

    function requestAccessChange(
        member: KnowledgeBaseMember,
        accessLevel: KnowledgeBaseAccessLevel,
    ) {
        if (accessLevel === member.accessLevel || mutationRef.current) return

        // Only deliberate privilege reductions require an extra confirmation.
        // ADMIN keeps system-level access independently of the membership row.
        const selfChange = member.userId === currentUser?.id
        const ownerReduction = member.accessLevel === 'OWNER'
        if (selfChange || ownerReduction) {
            setAccessTarget({ member, accessLevel })
            return
        }
        void changeAccess(member, accessLevel)
    }

    async function removeMember(member: KnowledgeBaseMember): Promise<boolean> {
        if (!beginMutation(member.userId)) return false
        try {
            await removeKnowledgeBaseMember(
                knowledgeBase.id,
                member.userId,
                member.version,
            )
            setRemoveTarget(null)
            setSuccess(`Персональный доступ ${member.email} удалён.`)

            const remaining = memberPage.totalElements - 1
            if (remaining > 0 && memberPage.content.length === 1 && currentPage > 0) {
                setCurrentPage(currentPage - 1)
                onChanged()
            } else {
                await reloadAfterMutation()
            }
            return true
        } catch (removeError) {
            setError(getApiErrorMessage(removeError, 'Не удалось удалить доступ.'))
            return false
        } finally {
            endMutation()
        }
    }

    const mutating = busyUserId !== null

    return (
        <>
            <Modal
                title={
                    `Доступ к базе «${knowledgeBase.name}»`
                }
                onClose={onClose}
                size="lg"
                className="knowledge-members-modal"
                closeDisabled={mutating}
            >
                <p className="modal-subtitle">
                    Добавляйте сотрудников организации и назначайте им права
                    только на эту базу знаний.
                </p>

                <div className="knowledge-access-guide" aria-label="Возможности уровней доступа">
                    {
                        (Object.keys(ACCESS_LEVEL_LABEL) as KnowledgeBaseAccessLevel[])
                            .map((accessLevel) => (
                                <div
                                    key={accessLevel}
                                    className={`knowledge-access-guide__item knowledge-access-guide__item--${accessLevel.toLowerCase()}`}
                                >
                                    <strong>{ACCESS_LEVEL_LABEL[accessLevel]}</strong>
                                    <span>{ACCESS_LEVEL_HINT[accessLevel]}</span>
                                </div>
                            ))
                    }
                </div>

                <div className="knowledge-member-search">
                    <label>
                        Добавить сотрудника
                        <input
                            aria-label="Имя или email сотрудника"
                            placeholder="Введите имя или email"
                            value={query}
                            maxLength={255}
                            disabled={mutating}
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
                            candidateLoading || mutating
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
                                                    mutating
                                                }
                                                onClick={() =>
                                                    void addCandidate(
                                                        candidate,
                                                    )
                                                }
                                            >
                                                Добавить для просмотра
                                            </button>
                                        </div>
                                    ),
                                )
                        }
                    </div>
                )}

                {mutating && (
                    <p className="knowledge-members-status" role="status" aria-live="polite">
                        Сохранение изменений…
                    </p>
                )}

                {success && !mutating && (
                    <p className="knowledge-members-status knowledge-members-status--success"
                       role="status" aria-live="polite">
                        {success}
                    </p>
                )}

                {error && (
                    <div
                        className="error"
                        role="alert"
                    >
                        {error}
                        <button type="button" className="secondary-button"
                            disabled={mutating || loading}
                            onClick={() => setRefreshKey(value => value + 1)}>
                            Обновить список
                        </button>
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
                            <div className="admin-table-wrapper knowledge-members-table-wrap">
                                <table className="admin-table knowledge-members-table">
                                    <thead>
                                        <tr>
                                            <th>
                                                Участник базы
                                            </th>
                                            <th>
                                                Права в этой базе
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
                                                        <td data-label="Участник базы">
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

                                                        <td data-label="Права в этой базе">
                                                            <select
                                                                aria-label={`Права в этой базе для ${member.email}`}
                                                                className={`knowledge-member-access knowledge-member-access--${member.accessLevel.toLowerCase()}`}
                                                                value={
                                                                    member.accessLevel
                                                                }
                                                                disabled={
                                                                    mutating
                                                                }
                                                                onChange={
                                                                    (event) =>
                                                                        requestAccessChange(
                                                                            member,
                                                                            event.target.value as KnowledgeBaseAccessLevel,
                                                                        )
                                                                }
                                                            >
                                                                <option value="VIEWER">
                                                                    Только просмотр
                                                                </option>
                                                                <option value="EDITOR">
                                                                    Просмотр и загрузка
                                                                </option>
                                                                <option value="OWNER">
                                                                    Полное управление
                                                                </option>
                                                            </select>

                                                            <small className="knowledge-member-access-hint">
                                                                {mutating && busyUserId === member.userId
                                                                    ? 'Сохраняем права…'
                                                                    : ACCESS_LEVEL_HINT[member.accessLevel]}
                                                            </small>
                                                        </td>

                                                        <td data-label="Действия">
                                                            <button
                                                                type="button"
                                                                className="danger-button knowledge-members-remove"
                                                                disabled={
                                                                    mutating
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
                    ariaLabel="Пагинация участников базы знаний"
                    singlePageMessage="Все участники показаны"
                    page={
                        memberPage.page
                    }
                    totalPages={
                        memberPage.totalPages
                    }
                    totalElements={
                        memberPage.totalElements
                    }
                    disabled={loading || mutating}
                    onPageChange={
                        setCurrentPage
                    }
                />

                <p className="knowledge-members-note">
                    Эти права действуют только внутри данной базы и не меняют
                    системную роль сотрудника. Изменения сохраняются сразу.
                </p>

                <div className="modal-actions">
                    <button
                        type="button"
                        className="secondary-button"
                        disabled={mutating}
                        onClick={onClose}
                    >
                        Закрыть
                    </button>
                </div>
            </Modal>

            {accessTarget && (
                <ConfirmDialog
                    title="Подтвердить изменение прав?"
                    message={[
                        accessTarget.member.userId === currentUser?.id
                            ? 'Вы изменяете собственную запись участника. ' : '',
                        accessTarget.member.accessLevel === 'OWNER'
                            ? 'Будут сняты права полного управления. '
                              + 'Убедитесь, что у базы остаётся ответственный управляющий. ' : '',
                        `Новый уровень для ${accessTarget.member.email}: `
                            + `${ACCESS_LEVEL_LABEL[accessTarget.accessLevel]}.`,
                    ].filter(Boolean).join(' ')}
                    confirmText="Изменить права"
                    loading={busyUserId === accessTarget.member.userId}
                    onCancel={() => setAccessTarget(null)}
                    onConfirm={async () => {
                        const { member, accessLevel } = accessTarget
                        if (!(await changeAccess(member, accessLevel))) {
                            throw new Error('Изменение прав не выполнено')
                        }
                        setAccessTarget(null)
                    }}
                />
            )}

            {removeTarget && (
                <ConfirmDialog
                    title="Удалить персональный доступ?"
                    message={
                        [
                            removeTarget.userId === currentUser?.id
                                ? 'Вы удаляете собственную запись участника. '
                                  + 'Роль ADMIN обеспечивает отдельный административный доступ, '
                                  + 'но изменение прав пользователя может повлиять на доступ в дальнейшем.'
                                : '',
                            removeTarget.accessLevel === 'OWNER'
                                ? 'Удаляется запись с полным управлением. '
                                  + 'Убедитесь, что у базы останется ответственный управляющий. '
                                : '',
                            knowledgeBase.visibility === 'MEMBERS'
                                ? `${removeTarget.fullName ?? removeTarget.email} потеряет персональный доступ к базе.`
                                : `Персональные права ${removeTarget.fullName ?? removeTarget.email} будут удалены. `
                                  + 'При доступе для всей организации останется базовый просмотр.',
                        ].filter(Boolean).join(' ')
                    }
                    confirmText="Удалить доступ"
                    danger
                    loading={
                        busyUserId
                        === removeTarget.userId
                    }
                    onCancel={() => setRemoveTarget(null)}
                    onConfirm={async () => {
                        if (!(await removeMember(removeTarget))) {
                            throw new Error('Удаление доступа не выполнено')
                        }
                    }}
                    onConfirmError={
                        () => undefined
                    }
                />
            )}
        </>
    )
}

export default KnowledgeMembersModal
