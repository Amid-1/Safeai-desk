// frontend/src/features/chat/controller/ChatConversationPanel.tsx
import type { ChatPageController } from './useChatPageController'
import { EmptyState, LoadingState } from '../../../shared/ui/StateBlock'
import ChatConversationHeader from './ChatConversationHeader'
import ChatConversationNotifications from './ChatConversationNotifications'
import ChatConversationMessages from './ChatConversationMessages'
import ChatMessageComposer from './ChatMessageComposer'
import { getPendingLabel } from '../lib/chatPageSupport'

type ChatConversationPanelProps = {
    controller: ChatPageController
}

/** Chat layout only. State, transport, and status logic remain in the controller. */
export default function ChatConversationPanel({ controller }: ChatConversationPanelProps) {
    const { activeChat, chatsLoading } = controller

    return (
        <section className="card chat-panel">
            {chatsLoading && <LoadingState message="Загрузка чата..." />}
            {!chatsLoading && !activeChat && (
                <EmptyState message="Создайте чат, чтобы начать общение." />
            )}
            {activeChat && (
                <>
                    <ChatConversationHeader controller={controller} />
                    <ChatConversationNotifications controller={controller} />
                    <ChatConversationMessages controller={controller} />
                    <ChatMessageComposer controller={controller} />
                    <div className="sr-only" role="status" aria-live="polite">
                        {controller.activePendingTurn
                            ? getPendingLabel(controller.activePendingTurn, controller.clock)
                            : ''}
                    </div>
                </>
            )}
        </section>
    )
}
