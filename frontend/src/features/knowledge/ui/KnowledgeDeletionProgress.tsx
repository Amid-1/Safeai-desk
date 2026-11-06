// frontend/src/features/knowledge/ui/KnowledgeDeletionProgress.tsx
import type {KnowledgeDeletionState} from '../../../entities/knowledge/api/knowledgeDocumentDeletionApi'
import {formatDateTime} from '../../../shared/lib/format'

/** Общая модель отображения статуса; источник истины — ответ backend. */
export type KnowledgeErasureOperation = {
    operationId: string
    state: KnowledgeDeletionState
    createdAt: string
    completedAt: string | null
    errorCode?: string | null
}

const STATUS: Record<KnowledgeDeletionState, {title: string; description: string}> = {
    DELETING: {
        title: 'Удаление выполняется',
        description: 'Запрос принят сервером. Проверяются активные операции, очищаются данные и фиксируются доказательства. Завершение ещё не подтверждено.',
    },
    NEEDS_REVIEW: {
        title: 'Требуется проверка администратором',
        description: 'Операция приостановлена до безопасного выяснения причины. Повторное удаление создавать нельзя.',
    },
    BLOCKED_BY_RETENTION: {
        title: 'Удаление ограничено сроком хранения',
        description: 'Применяется retention или иное ограничение. Нельзя обходить политику хранения повторным запросом.',
    },
    DELETED: {
        title: 'Удаление завершено',
        description: 'Сервер подтвердил конечное состояние операции. Допустимые записи истории и аудита могут сохраняться.',
    },
}

export function KnowledgeDeletionProgress({
    operation,
    historyHref,
    onHistoryClick,
}: {
    operation: KnowledgeErasureOperation
    historyHref?: string
    onHistoryClick?: () => void
}) {
    const status = STATUS[operation.state]

    return (
        <div
            className={`knowledge-erasure-status knowledge-erasure-status--${operation.state.toLowerCase()}`}
            role="status"
            aria-live="polite"
            data-erasure-state={operation.state}
        >
            <div className="knowledge-erasure-status__heading">
                <span aria-hidden="true" className="knowledge-erasure-status__dot" />
                <strong>{status.title}</strong>
            </div>

            <p>{status.description}</p>

            {operation.state === 'DELETING' && (
                <div className="knowledge-erasure-status__busy">
                    <progress aria-label="Удаление выполняется — точный процент неизвестен" />
                    <small>Точный процент выполнения сервер не предоставляет.</small>
                </div>
            )}

            <dl className="knowledge-erasure-status__facts">
                <dt>Операция</dt>
                <dd><code>{operation.operationId}</code></dd>

                <dt>Начата</dt>
                <dd>{formatDateTime(operation.createdAt)}</dd>

                {operation.completedAt && (
                    <>
                        <dt>Завершена</dt>
                        <dd>{formatDateTime(operation.completedAt)}</dd>
                    </>
                )}

                {operation.errorCode && (
                    <>
                        <dt>Код диагностики</dt>
                        <dd><code>{operation.errorCode}</code></dd>
                    </>
                )}
            </dl>

            {historyHref && (
                <a
                    className="knowledge-erasure-status__link"
                    href={historyHref}
                    onClick={onHistoryClick}
                >
                    Посмотреть историю операций
                </a>
            )}
        </div>
    )
}