// frontend/src/features/chat/ui/ChatMessageViews.tsx
/* frontend/src/features/chat/ui/ChatMessageViews.tsx */
import type { KnowledgeMode } from '../api/chatApi'
import type { AnswerPassport } from '../api/chatApi'
import type { DisplayMessage, PendingTurn } from '../lib/chatPage.helpers'
import {
    formatPricingValue,
    formatUsageValue,
    getAiResponseLabel,
    getModelDisplayName,
    getVisibleMessageContent,
    isMockModel,
    isSafeToPrepareNewRequest,
    canRetryPendingStatus,
} from '../lib/chatPage.helpers'
import { getPendingLabel, getPendingShortLabel } from '../lib/chatPageSupport'

type MessageViewProps = {
    message: DisplayMessage
    answerPassport?: AnswerPassport
}

export function MessageView({
    message,
    answerPassport,
}: MessageViewProps) {
    const aiResponseLabel =
        getAiResponseLabel(message)
    const mockModel =
        isMockModel(message.model)

    return (
        <article
            className={
                `message ${
                    message.role.toLowerCase()
                } ${
                    message.status.toLowerCase()
                }`
            }
        >
            <div className="message__header">
                <span className="message__avatar" aria-hidden="true">
                    {message.role === 'ASSISTANT'
                        ? 'AI'
                        : message.role === 'USER'
                            ? 'ВЫ'
                            : 'SYS'}
                </span>
                <strong>
                    {message.role === 'ASSISTANT'
                        ? 'SafeAI'
                        : message.role === 'USER'
                            ? 'Вы'
                            : 'Система'}
                </strong>
                {message.role === 'ASSISTANT' && mockModel && (
                    <span
                        className="message__demo-badge"
                        title="Демонстрационный режим, не внешний AI"
                    >
                        Тестовый ответ
                    </span>
                )}
            </div>

            {message.status === 'FAILED' && (
                <span className="status-badge status-disabled">
                    Ошибка
                </span>
            )}

            {message.uiStatus && (
                <span className="status-badge status-disabled">
                    {getPendingShortLabel({
                        status: message.uiStatus,
                    })}
                </span>
            )}

            {aiResponseLabel && (
                <span className="status-badge">
                    {aiResponseLabel}
                </span>
            )}

            <p>{getVisibleMessageContent(message)}</p>

            {message.role === 'ASSISTANT' && (
                <>
                    <details className="answer-details">
                        <summary>
                            <span className="answer-details__icon" aria-hidden="true">
                                i
                            </span>
                            <span className="answer-details__heading">
                                <strong>Как сформирован ответ</strong>
                                <small>Модель, объём и стоимость запроса</small>
                            </span>
                            <span className="answer-details__action">
                                Подробнее
                            </span>
                        </summary>
                        <dl className="answer-details__grid">
                            <div>
                                <dt>Модель AI</dt>
                                <dd>{getModelDisplayName(message.model)}</dd>
                                <small>
                                    {mockModel
                                        ? 'Демонстрационный ответ. Это не ответ внешней AI-модели.'
                                        : 'Модель, которая фактически сформировала этот ответ.'}
                                </small>
                            </div>
                            <div>
                                <dt>Объём запроса</dt>
                                <dd>{formatUsageValue(message)}</dd>
                                <small>
                                    Вход — запрос и контекст, выход — текст ответа модели.
                                </small>
                            </div>
                            <div>
                                <dt>Стоимость</dt>
                                <dd>{formatPricingValue(message)}</dd>
                                <small>
                                    Расчёт хранится вместе с операцией и доступен в отчёте использования.
                                </small>
                            </div>
                        </dl>
                    </details>
                    {answerPassport && (
                        <details className="answer-passport">
                            <summary>
                                Источники ответа ({answerPassport.citations.length})
                            </summary>
                            <p>
                                Режим: {getKnowledgeModeLabel(answerPassport.knowledgeMode)}
                                {' · '}Ссылки: {answerPassport.citationsValid ? 'проверены' : 'требуют проверки'}
                                {' · '}Источников: {answerPassport.evidenceSufficient ? 'достаточно' : 'недостаточно'}
                            </p>
                            <ul>
                                {answerPassport.citations.map((citation) => (
                                    <li key={citation.chunkId}>
                                        <strong>[{citation.label}]</strong>
                                        {' '}{citation.documentName}
                                        {' · версия '}{citation.versionNumber}
                                        {citation.pageFrom != null
                                            ? ` · стр. ${citation.pageFrom}`
                                            : ''}
                                    </li>
                                ))}
                            </ul>
                            <details className="answer-passport__technical">
                                <summary>Сведения для поддержки</summary>
                                <p>Номер поиска: <code>{answerPassport.retrievalRunId}</code></p>
                                {answerPassport.modelRouteDecisionId && (
                                    <p>Номер решения: <code>{answerPassport.modelRouteDecisionId}</code></p>
                                )}
                            </details>
                        </details>
                    )}
                </>
            )}
        </article>
    )
}

function getKnowledgeModeLabel(mode: KnowledgeMode): string {
    switch (mode) {
        case 'KNOWLEDGE_ASSISTED':
            return 'AI + корпоративные знания'
        case 'KNOWLEDGE_ONLY':
            return 'Только корпоративные знания'
        default:
            return 'Обычный AI'
    }
}

type PendingTurnStateProps = {
    pending: PendingTurn
    now: number
    retryAfterSeconds: number
    onRetry: () => void
    onCheck: () => void
    onPrepareNew: () => void
    onCopy: () => void
    onDismissUnsafe: () => void
}

export function PendingTurnState({
    pending,
    now,
    retryAfterSeconds,
    onRetry,
    onCheck,
    onPrepareNew,
    onCopy,
    onDismissUnsafe,
}: PendingTurnStateProps) {
    const canRetrySameId = canRetryPendingStatus(pending.status)

    const canCheck =
        pending.status === 'PROCESSING'
        || pending.status === 'SEND_UNKNOWN'
        || pending.status === 'AMBIGUOUS'
        || pending.status === 'RECONCILIATION_REQUIRED'

    const canPrepareNew =
        isSafeToPrepareNewRequest(
            pending.status,
        )

    const unsafeTerminal =
        pending.status === 'AMBIGUOUS'
        || pending.status === 'IDEMPOTENCY_CONFLICT'

    return (
        <div
            className="card"
            role={
                pending.status === 'FAILED'
                || pending.status === 'AMBIGUOUS'
                || pending.status === 'RECONCILIATION_REQUIRED'
                || pending.status === 'ACCESS_REVOKED'
                || pending.status === 'IDEMPOTENCY_CONFLICT'
                    ? 'alert'
                    : 'status'
            }
            aria-live={
                pending.status === 'FAILED'
                || pending.status === 'AMBIGUOUS'
                    ? 'assertive'
                    : 'polite'
            }
        >
            <strong>
                {getPendingLabel(
                    pending,
                    now,
                )}
            </strong>

            {pending.error && (
                <p>{pending.error}</p>
            )}

            <div className="modal-actions">
                {canRetrySameId && (
                    <button
                        type="button"
                        disabled={retryAfterSeconds > 0}
                        onClick={onRetry}
                    >
                        Повторить отправку
                    </button>
                )}

                {canCheck && (
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={onCheck}
                    >
                        Проверить статус
                    </button>
                )}

                {canPrepareNew && (
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={onPrepareNew}
                    >
                        Вернуть текст как новый запрос
                    </button>
                )}

                {(unsafeTerminal || pending.status === 'RECONCILIATION_REQUIRED') && (
                    <>
                        <button
                            type="button"
                            className="secondary-button"
                            onClick={onCopy}
                        >
                            Скопировать текст
                        </button>
                        {unsafeTerminal && <button
                            type="button"
                            className="secondary-button"
                            onClick={onDismissUnsafe}
                        >
                            Закрыть без повтора
                        </button>}
                    </>
                )}
            </div>
        </div>
    )
}
