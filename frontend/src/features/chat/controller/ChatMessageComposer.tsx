// frontend/src/features/chat/controller/ChatMessageComposer.tsx
import type { ChatPageController } from './useChatPageController'
import { hasMeaningfulContent } from '../lib/chatPage.helpers'
import ChatKnowledgeModeSelector from './ChatKnowledgeModeSelector'

type Props = { controller: ChatPageController }

export default function ChatMessageComposer({ controller }: Props) {
    const {
        activeChat,
        activeDraft,
        activeChatBusy,
        activeChatHasPending,
        capabilities,
        knowledgeMode,
        knowledgeBaseId,
        setDraft,
        handleTextareaKeyDown,
        handleSendMessage,
    } = controller

    if (!activeChat) return null

    const canSend = !activeChatHasPending
        && hasMeaningfulContent(activeDraft)
        && activeDraft.length <= capabilities.maxMessageChars
        && (knowledgeMode === 'GENERAL' || Boolean(knowledgeBaseId))

    return (
        <div className="message-form">
            <ChatKnowledgeModeSelector controller={controller} />
            <div className="message-form__composer">
                <label htmlFor="chat-message">Сообщение</label>
                <textarea
                    id="chat-message"
                    rows={3}
                    value={activeDraft}
                    onChange={(event) => setDraft(activeChat.id, event.target.value)}
                    onKeyDown={handleTextareaKeyDown}
                    placeholder="Введите сообщение. Ctrl+Enter — отправить"
                    maxLength={capabilities.maxMessageChars}
                    disabled={activeChatBusy || activeChatHasPending}
                    aria-describedby="chat-message-counter"
                />
                <small id="chat-message-counter" className="muted">
                    {activeDraft.length}{' / '}{capabilities.maxMessageChars}
                </small>
            </div>
            <button
                type="button"
                className="message-send-button"
                onClick={() => void handleSendMessage()}
                disabled={!canSend}
            >
                {activeChatBusy ? 'Ожидание...' : 'Отправить'}
            </button>
        </div>
    )
}
