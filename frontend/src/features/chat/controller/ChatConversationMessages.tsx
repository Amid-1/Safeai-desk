// frontend/src/features/chat/controller/ChatConversationMessages.tsx
import type { ChatPageController } from './useChatPageController'
import { MessageView } from '../ui/ChatMessageViews'

type Props = { controller: ChatPageController }

export default function ChatConversationMessages({ controller }: Props) {
    const {
        historyHasNext,
        historyLoading,
        loadEarlierHistory,
        messagesContainerRef,
        activeChatBusy,
        displayMessages,
        answerPassports,
        activePendingTurn,
        messagesEndRef,
    } = controller

    const isAwaitingAnswer = activePendingTurn?.status === 'SENDING'
        || activePendingTurn?.status === 'PROCESSING'

    return (
        <>
            {historyHasNext && (
                <button
                    type="button"
                    className="secondary-button"
                    disabled={historyLoading}
                    onClick={() => void loadEarlierHistory()}
                >
                    {historyLoading ? 'Загрузка истории...' : 'Загрузить более ранние сообщения'}
                </button>
            )}
            <div
                ref={messagesContainerRef}
                className="messages"
                aria-busy={activeChatBusy}
            >
                {displayMessages.length === 0 && !activeChatBusy && (
                    <p>Сообщений пока нет.</p>
                )}
                {displayMessages.map((item) => (
                    <MessageView
                        key={item.id}
                        message={item}
                        answerPassport={answerPassports[item.id]}
                    />
                ))}
                {isAwaitingAnswer && (
                    <div className="message assistant pending" role="status" aria-live="polite">
                        <strong>ASSISTANT</strong>
                        <p>{activePendingTurn?.status === 'SENDING'
                            ? 'Запрос отправляется...'
                            : 'Формируется ответ...'}</p>
                    </div>
                )}
                <div ref={messagesEndRef} />
            </div>
        </>
    )
}
