import type { KnowledgeMode } from '../../api/chatApi'
import type { AnswerPassport } from '../../api/chatApi'
import type { DisplayMessage, PendingTurn } from '../../pages/chatPage.helpers'
import {
    formatPricingValue,
    formatUsageValue,
    getAiResponseLabel,
    getModelDisplayName,
    getVisibleMessageContent,
    isMockModel,
    isSafeToPrepareNewRequest,
} from '../../pages/chatPage.helpers'
import { getPendingLabel, getPendingShortLabel } from '../../pages/chatPageSupport'

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
                    <span className="message__demo-badge">
                        Демо-режим
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
                                {message.model && (
                                    <code>{message.model}</code>
                                )}
                                <small>
                                    {mockModel
                                        ? 'Тестовый провайдер для демонстрации интерфейса. Это не ответ внешней LLM.'
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
                                Паспорт ответа · источников: {answerPassport.citations.length}
                            </summary>
                            <p>
                                Режим: {getKnowledgeModeLabel(answerPassport.knowledgeMode)}
                                {' · '}Ссылки: {answerPassport.citationsValid ? 'проверены' : 'требуют проверки'}
                                {' · '}Доказательств: {answerPassport.evidenceSufficient ? 'достаточно' : 'недостаточно'}
                            </p>
                            <ul>
                                {answerPassport.citations.map((citation) => (
                                    <li key={citation.chunkId}>
                                        <strong>[{citation.label}]</strong>
                                        {' '}{citation.documentName}
                                        {' · v'}{citation.versionNumber}
                                        {citation.pageFrom != null
                                            ? ` · стр. ${citation.pageFrom}`
                                            : ''}
                                        {' · chunk '}{citation.chunkOrdinal}
                                    </li>
                                ))}
                            </ul>
                            <small>
                                Идентификатор поиска: {answerPassport.retrievalRunId}
                            </small>
                            {answerPassport.modelRouteDecisionId && (
                                <small>
                                    {' · '}Route decision: {answerPassport.modelRouteDecisionId}
                                </small>
                            )}
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
    const canRetrySameId =
        pending.status === 'SEND_UNKNOWN'
        || pending.status === 'RATE_LIMITED'

    const canCheck =
        pending.status === 'PROCESSING'
        || pending.status === 'SEND_UNKNOWN'
        || pending.status === 'AMBIGUOUS'

    const canPrepareNew =
        isSafeToPrepareNewRequest(
            pending.status,
        )
        && pending.status !== 'RATE_LIMITED'

    const unsafeTerminal =
        pending.status === 'AMBIGUOUS'
        || pending.status === 'IDEMPOTENCY_CONFLICT'

    return (
        <div
            className="card"
            role={
                pending.status === 'FAILED'
                || pending.status === 'AMBIGUOUS'
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
                        Повторить с тем же ID
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

                {unsafeTerminal && (
                    <>
                        <button
                            type="button"
                            className="secondary-button"
                            onClick={onCopy}
                        >
                            Скопировать текст
                        </button>
                        <button
                            type="button"
                            className="secondary-button"
                            onClick={onDismissUnsafe}
                        >
                            Закрыть без повтора
                        </button>
                    </>
                )}
            </div>
        </div>
    )
}
