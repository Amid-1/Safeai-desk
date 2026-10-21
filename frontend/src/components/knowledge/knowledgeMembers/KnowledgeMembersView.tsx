// frontend/src/components/knowledge/knowledgeMembers/KnowledgeMembersView.tsx
import type { KnowledgeBase, KnowledgeBaseAccessLevel } from '../../../api/knowledgeApi'
import Modal from '../../Modal'
import ConfirmDialog from '../../ConfirmDialog'
import { EmptyState, LoadingState } from '../../StateBlock'
import KnowledgePagination from '../KnowledgePagination'
import { ACCESS_LEVEL_LABEL, ACCESS_LEVEL_HINT } from './knowledgeMembersConfig'
import type { KnowledgeMembersController } from './useKnowledgeMembersController'

type Props = KnowledgeMembersController & { knowledgeBase: KnowledgeBase; onClose: () => void }
export function KnowledgeMembersView({
    knowledgeBase,
    onClose,
    currentUser,
    accessTarget,
    setAccessTarget,
    removeTarget,
    setRemoveTarget,
    memberPage,
    setCurrentPage,
    loading,
    error,
    query,
    setQuery,
    candidates,
    candidateLoading,
    busyUserId,
    memberIds,
    mutating,
    success,
    setRefreshKey,
    searchCandidates,
    addCandidate,
    requestAccessChange,
    changeAccess,
    removeMember,
}: Props) {
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
