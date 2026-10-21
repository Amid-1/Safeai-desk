// ============================================================
// frontend/src/pages/chat/ChatConversationPanel.tsx
// ============================================================
import type { ChatPageController } from './useChatPageController'
import type { KnowledgeMode } from '../../api/chatApi'
import ChatTitleEditor from '../../components/chat/ChatTitleEditor'
import { MessageView, PendingTurnState } from '../../components/chat/ChatMessageViews'
import { EmptyState, LoadingState } from '../../components/StateBlock'
import { CHAT_TRUST_ITEMS } from './chatTrustInfo'
import { hasMeaningfulContent, isMockModel } from '../chatPage.helpers'
import { getPendingLabel } from '../chatPageSupport'

type ChatConversationPanelProps = {
    controller: Pick<ChatPageController,
    | 'chatsLoading'
    | 'activeChat'
    | 'activeChatBusy'
    | 'chatError'
    | 'sendError'
    | 'activePendingTurn'
    | 'retryAfterSeconds'
    | 'clock'
    | 'historyHasNext'
    | 'historyLoading'
    | 'messagesContainerRef'
    | 'displayMessages'
    | 'answerPassports'
    | 'messagesEndRef'
    | 'knowledgeMode'
    | 'knowledgeBaseId'
    | 'knowledgeBases'
    | 'activeDraft'
    | 'capabilities'
    | 'activeChatHasPending'
    | 'lastAssistantModel'
    | 'setSelectedTrustItem'
    | 'handleRenameChat'
    | 'retryPendingTurn'
    | 'reconcilePendingTurn'
    | 'prepareNewRequestFromPending'
    | 'copyPendingText'
    | 'dismissUnsafePending'
    | 'loadEarlierHistory'
    | 'setKnowledgeMode'
    | 'setKnowledgeBaseId'
    | 'setDraft'
    | 'handleTextareaKeyDown'
    | 'handleSendMessage'
>
}

export default function ChatConversationPanel({ controller }: ChatConversationPanelProps) {
    const {
        chatsLoading,
        activeChat,
        activeChatBusy,
        chatError,
        sendError,
        activePendingTurn,
        retryAfterSeconds,
        clock,
        historyHasNext,
        historyLoading,
        messagesContainerRef,
        displayMessages,
        answerPassports,
        messagesEndRef,
        knowledgeMode,
        knowledgeBaseId,
        knowledgeBases,
        activeDraft,
        capabilities,
        activeChatHasPending,
        lastAssistantModel,
        setSelectedTrustItem,
        handleRenameChat,
        retryPendingTurn,
        reconcilePendingTurn,
        prepareNewRequestFromPending,
        copyPendingText,
        dismissUnsafePending,
        loadEarlierHistory,
        setKnowledgeMode,
        setKnowledgeBaseId,
        setDraft,
        handleTextareaKeyDown,
        handleSendMessage,
    } = controller

    return (
        <section className="card chat-panel">
            {chatsLoading && (
                <LoadingState
                    message="Загрузка чата..."
                />
            )}

            {!chatsLoading
                && !activeChat
                && (
                    <EmptyState
                        message={
                            'Создайте чат, чтобы начать общение.'
                        }
                    />
                )}

            {activeChat && (
                <>
                    <div className="chat-panel__header">
                        <div>
                            <span className="chat-panel__eyebrow">Текущий чат</span>
                            <ChatTitleEditor
                                key={activeChat.id}
                                title={activeChat.title}
                                onSave={handleRenameChat}
                            />
                        </div>
                        <div className="chat-panel__indicators">
                            {lastAssistantModel && (
                                <span
                                    className={
                                        isMockModel(lastAssistantModel)
                                            ? 'chat-panel__model-badge chat-panel__model-badge--mock'
                                            : 'chat-panel__model-badge'
                                    }
                                    title={`Модель последнего ответа: ${lastAssistantModel}`}
                                >
                                    {isMockModel(lastAssistantModel)
                                        ? 'Тестовый ответ · mock'
                                        : 'Ответ AI-модели'}
                                </span>
                            )}
                            <button
                                type="button"
                                className="chat-panel__status"
                                onClick={() => setSelectedTrustItem(
                                    CHAT_TRUST_ITEMS[0],
                                )}
                                title="Информация об изоляции и защите данных"
                            >
                                <span aria-hidden="true">●</span>
                                Защита данных
                            </button>
                        </div>
                    </div>

                    {chatError && (
                        <div
                            className="error"
                            role="alert"
                            aria-live="assertive"
                        >
                            {chatError}
                        </div>
                    )}

                    {sendError && (
                        <div
                            className="error"
                            role="alert"
                            aria-live="assertive"
                        >
                            {sendError}
                        </div>
                    )}

                    {activePendingTurn && (
                        <PendingTurnState
                            pending={activePendingTurn}
                            now={clock}
                            retryAfterSeconds={
                                retryAfterSeconds
                            }
                            onRetry={() =>
                                void retryPendingTurn()
                            }
                            onCheck={() =>
                                void reconcilePendingTurn(
                                    activePendingTurn,
                                )
                            }
                            onPrepareNew={
                                prepareNewRequestFromPending
                            }
                            onCopy={() =>
                                void copyPendingText()
                            }
                            onDismissUnsafe={
                                dismissUnsafePending
                            }
                        />
                    )}

                    {historyHasNext && (
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={historyLoading}
                            onClick={() =>
                                void loadEarlierHistory()
                            }
                        >
                            {historyLoading
                                ? 'Загрузка истории...'
                                : 'Загрузить более ранние сообщения'}
                        </button>
                    )}

                    <div
                        ref={messagesContainerRef}
                        className="messages"
                        aria-busy={activeChatBusy}
                    >
                        {displayMessages.length
                            === 0
                            && !activeChatBusy
                            && (
                                <p>
                                    Сообщений пока нет.
                                </p>
                            )}

                        {displayMessages.map(
                            (item) => (
                                <MessageView
                                    key={item.id}
                                    message={item}
                                    answerPassport={
                                        answerPassports[item.id]
                                    }
                                />
                            ),
                        )}

                        {activePendingTurn
                            && (
                                activePendingTurn.status
                                    === 'SENDING'
                                || activePendingTurn.status
                                    === 'PROCESSING'
                            )
                            && (
                                <div
                                    className="message assistant pending"
                                    role="status"
                                    aria-live="polite"
                                >
                                    <strong>
                                        ASSISTANT
                                    </strong>
                                    <p>
                                        {activePendingTurn.status
                                            === 'SENDING'
                                            ? 'Запрос отправляется...'
                                            : 'Формируется ответ...'}
                                    </p>
                                </div>
                            )}

                        <div ref={messagesEndRef} />
                    </div>

                    <div className="message-form">
                        <div className="message-form__controls">
                            <label htmlFor="knowledge-mode">
                                Режим ответа
                            </label>
                            <select
                                id="knowledge-mode"
                                value={knowledgeMode}
                                disabled={activeChatBusy || activeChatHasPending}
                                onChange={(event) => {
                                    const mode = event.target.value as KnowledgeMode
                                    setKnowledgeMode(mode)
                                    if (mode === 'GENERAL') {
                                        setKnowledgeBaseId('')
                                    }
                                }}
                            >
                                <option value="GENERAL">Обычный AI</option>
                                <option value="KNOWLEDGE_ASSISTED">AI + корпоративные знания</option>
                                <option value="KNOWLEDGE_ONLY">Только корпоративные знания</option>
                            </select>

                            {knowledgeMode !== 'GENERAL' && (
                                <>
                                    <label htmlFor="knowledge-base">
                                        База знаний
                                    </label>
                                    <select
                                        id="knowledge-base"
                                        value={knowledgeBaseId}
                                        disabled={activeChatBusy || activeChatHasPending}
                                        onChange={(event) =>
                                            setKnowledgeBaseId(event.target.value)
                                        }
                                    >
                                        <option value="">Выберите базу знаний</option>
                                        {knowledgeBases.map((base) => (
                                            <option key={base.id} value={base.id}>
                                                {base.name}
                                            </option>
                                        ))}
                                    </select>
                                </>
                            )}
                        </div>
                        <div className="message-form__composer">
                            <label htmlFor="chat-message">
                                Сообщение
                            </label>

                            <textarea
                                id="chat-message"
                                rows={3}
                                value={activeDraft}
                                onChange={(event) =>
                                    setDraft(
                                        activeChat.id,
                                        event.target.value,
                                    )
                                }
                                onKeyDown={
                                    handleTextareaKeyDown
                                }
                                placeholder={
                                    'Введите сообщение. Ctrl+Enter — отправить'
                                }
                                maxLength={
                                    capabilities.maxMessageChars
                                }
                                disabled={
                                    activeChatBusy
                                    || activeChatHasPending
                                }
                                aria-describedby="chat-message-counter"
                            />

                            <small
                                id="chat-message-counter"
                                className="muted"
                            >
                                {activeDraft.length}
                                {' / '}
                                {capabilities.maxMessageChars}
                            </small>
                        </div>

                        <button
                            type="button"
                            className="message-send-button"
                            onClick={() =>
                                void handleSendMessage()
                            }
                            disabled={
                                activeChatHasPending
                                || !hasMeaningfulContent(
                                    activeDraft,
                                )
                                || activeDraft.length
                                    > capabilities.maxMessageChars
                                || (
                                    knowledgeMode !== 'GENERAL'
                                    && !knowledgeBaseId
                                )
                            }
                        >
                            {activeChatBusy
                                ? 'Ожидание...'
                                : 'Отправить'}
                        </button>
                    </div>

                    <div
                        className="sr-only"
                        role="status"
                        aria-live="polite"
                    >
                        {activePendingTurn
                            ? getPendingLabel(
                                activePendingTurn,
                                clock,
                            )
                            : ''}
                    </div>
                </>
            )}
        </section>
    )
}
