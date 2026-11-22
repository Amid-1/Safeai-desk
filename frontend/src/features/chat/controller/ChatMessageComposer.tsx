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
        knowledgeBases,
        knowledgeBasesStatus,
        setDraft,
        handleTextareaKeyDown,
        handleSendMessage,
    } = controller

    if (!activeChat) return null

    const canSend = !activeChatHasPending && !activeChatBusy
        && hasMeaningfulContent(activeDraft)
        && activeDraft.length <= capabilities.maxMessageChars
        && (knowledgeMode === 'GENERAL' || (knowledgeBasesStatus === 'ready'
            && knowledgeBases.some(base => base.id === knowledgeBaseId && base.enabled
                && base.lifecycleState !== 'DELETING')))

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
                    {activeDraft.length}{' / '}{capabilities.maxMessageChars} символов
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
