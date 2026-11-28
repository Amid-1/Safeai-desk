// frontend/src/features/chat/controller/ChatSidebar.tsx
import {useCallback, useId, useRef, useState} from 'react'
import type {SubmitEvent} from 'react'
import Modal from '../../../shared/ui/Modal'
import ConfirmDialog from '../../../shared/ui/ConfirmDialog'
import {useAuth} from '../../auth/state/useAuth'
import type {Chat} from '../api/chatApi'
import ChatArchiveModal from './ChatArchiveModal'
import ChatHistoryRow from '../ui/ChatHistoryRow'
import type {ChatPageController} from './useChatPageController'

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
        | 'handleArchiveChat'
        | 'setChatToRename'
        | 'setChatToArchive'
        | 'openChat'
        | 'loadMoreChats'
        | 'setReloadToken'
    >
}

export default function ChatSidebar({controller}: ChatSidebarProps) {
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
        handleArchiveChat,
        setChatToRename,
        setChatToArchive,
        openChat,
        loadMoreChats,
        setReloadToken,
    } = controller

    const [creating, setCreating] = useState(false)
    const [archiveOpen, setArchiveOpen] = useState(false)
    const [eraseTarget, setEraseTarget] = useState<Chat | null>(null)
    const [archiveSelection, setArchiveSelection] = useState<string | undefined>()
    const {currentUser} = useAuth()
    const canErase = currentUser?.roles.includes('ADMIN') ?? false
    const [title, setTitle] = useState('')
    const [error, setError] = useState('')
    const titleId = useId()
    const hintId = useId()
    const titleRef = useRef<HTMLInputElement>(null)
    const archiveChanged = useCallback(() => {
        setReloadToken(value => value + 1)
    }, [setReloadToken])

    async function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault()
        const normalized = title.replace(/\s+/g, ' ').trim()
        if (Array.from(normalized).length > 255) {
            setError('Название не должно превышать 255 символов.')
            return
        }

        setError('')
        const createError = await handleCreateChat(normalized || undefined)
        if (createError) {
            setError(createError)
            return
        }
        setCreating(false)
    }

    return (
        <aside className="card sidebar chat-sidebar">
            <button
                type="button"
                onClick={() => {
                    setTitle('')
                    setError('')
                    setCreating(true)
                }}
                disabled={chatCreating}
            >
                {chatCreating
                    ? 'Создание...'
                    : 'Создать чат'}
            </button>

            {creating && (
                <Modal
                    title="Создать чат"
                    size="sm"
                    onClose={() => setCreating(false)}
                    closeDisabled={chatCreating}
                    initialFocusRef={titleRef}
                >
                    <form className="form" onSubmit={(event) => void submit(event)}>
                        <label htmlFor={titleId}>Название чата</label>
                        <input
                            id={titleId}
                            ref={titleRef}
                            aria-describedby={hintId}
                            aria-invalid={Boolean(error)}
                            autoComplete="off"
                            maxLength={510}
                            value={title}
                            disabled={chatCreating}
                            onChange={(event) => {
                                setTitle(event.target.value)
                                setError('')
                            }}
                        />
                        <p id={hintId} className="muted">
                            Если оставить поле пустым, название появится после принятия первого сообщения.
                        </p>
                        {error && <p role="alert">{error}</p>}
                        <button type="submit" disabled={chatCreating}>
                            {chatCreating ? 'Создание…' : 'Создать'}
                        </button>
                    </form>
                </Modal>
            )}

            <button type="button" className="secondary-button"
                    onClick={() => {
                        setArchiveSelection(undefined)
                        setArchiveOpen(true)
                    }}>Архив чатов
            </button>
            {eraseTarget && canErase && <ConfirmDialog
                title="Перейти к удалению содержимого?"
                message={`Чат «${eraseTarget.title}» сначала будет архивирован. `
                    + 'Несохранённые черновики будут удалены. '
                    + 'Удаление содержимого требует отдельной проверки сервера и подтверждения.'}
                confirmText="Архивировать и продолжить"
                loading={archivingChatId === eraseTarget.id}
                onCancel={() => setEraseTarget(null)}
                onConfirm={async () => {
                    if (!await handleArchiveChat(eraseTarget)) {
                        throw new Error('Дождитесь завершения запроса.')
                    }
                    setArchiveSelection(eraseTarget.id)
                    setEraseTarget(null)
                    setArchiveOpen(true)
                }}
            />}
            {archiveOpen && <ChatArchiveModal
                onClose={() => setArchiveOpen(false)}
                onChanged={archiveChanged}
                initialChatId={archiveSelection}
            />}
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
                        onErase={canErase ? setEraseTarget : undefined}
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
