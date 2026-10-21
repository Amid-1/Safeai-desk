// frontend/src/features/chat/controller/ChatSidebar.tsx
// ============================================================
// frontend/src/features/chat/controller/ChatSidebar.tsx
// ============================================================
import ChatHistoryRow from '../ui/ChatHistoryRow'
import type { ChatPageController } from './useChatPageController'

type ChatSidebarProps = {
    controller: Pick<ChatPageController,
    | 'chats'
    | 'chatCreating'
    | 'chatsLoading'
    | 'pendingTurns'
    | 'activeChat'
    | 'openingChatId'
    | 'archivingChatId'
    | 'chatHasNext'
    | 'moreChatsLoading'
    | 'clock'
    | 'handleCreateChat'
    | 'setChatToRename'
    | 'setChatToArchive'
    | 'openChat'
    | 'loadMoreChats'
>
}

export default function ChatSidebar({ controller }: ChatSidebarProps) {
    const {
        chats,
        chatCreating,
        chatsLoading,
        pendingTurns,
        activeChat,
        openingChatId,
        archivingChatId,
        chatHasNext,
        moreChatsLoading,
        clock,
        handleCreateChat,
        setChatToRename,
        setChatToArchive,
        openChat,
        loadMoreChats,
    } = controller

    return (
        <aside className="card sidebar chat-sidebar">
            <button
                type="button"
                onClick={() =>
                    void handleCreateChat()
                }
                disabled={chatCreating}
            >
                {chatCreating
                    ? 'Создание...'
                    : 'Создать чат'}
            </button>

            <div className="chat-sidebar__heading">
                <div>
                    <span className="chat-sidebar__eyebrow">История</span>
                    <h2>Ваши чаты</h2>
                </div>
                <span className="chat-count">{chats.length}</span>
            </div>

            <div className="chat-list">
                {chatsLoading && (
                    <p className="muted">
                        Загрузка чатов...
                    </p>
                )}

                {!chatsLoading
                    && chats.length === 0
                    && (
                        <p>Чатов пока нет.</p>
                    )}

                {chats.map((chat) => (
                    <ChatHistoryRow
                        key={chat.id}
                        chat={chat}
                        active={activeChat?.id === chat.id}
                        pending={pendingTurns[chat.id]}
                        now={clock}
                        opening={openingChatId === chat.id}
                        archiving={archivingChatId === chat.id}
                        onOpen={(chatId) => void openChat(chatId)}
                        onRename={setChatToRename}
                        onArchive={setChatToArchive}
                    />
                ))}

                {chatHasNext && (
                    <button
                        type="button"
                        className="secondary-button"
                        disabled={moreChatsLoading}
                        onClick={() =>
                            void loadMoreChats()
                        }
                    >
                        {moreChatsLoading
                            ? 'Загрузка...'
                            : 'Показать ещё чаты'}
                    </button>
                )}
            </div>
        </aside>
    )
}
