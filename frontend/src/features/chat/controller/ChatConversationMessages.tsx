// frontend/src/features/chat/controller/ChatConversationMessages.tsx
import type { ChatPageController } from './useChatPageController'
import { MessageView } from '../ui/ChatMessageViews'
import ChatConversationNotifications from './ChatConversationNotifications'

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
        messagesEndRef,
    } = controller

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
                    <p>Напишите сообщение, чтобы начать разговор.</p>
                )}
                {displayMessages.map((item) => (
                    <MessageView
                        key={item.id}
                        message={item}
                        answerPassport={answerPassports[item.id]}
                    />
                ))}
                <ChatConversationNotifications key={controller.activeChat?.id} controller={controller} />
                <div ref={messagesEndRef} />
            </div>
        </>
    )
}
