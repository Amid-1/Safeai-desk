/* frontend/src/components/chat/ChatHistoryRow.tsx */
import {
    useEffect,
    useId,
    useRef,
    useState,
} from 'react'
import type {
    KeyboardEvent,
} from 'react'
import type {
    Chat,
} from '../../api/chatApi'
import type {
    PendingTurn,
} from '../../pages/chatPage.helpers'
import {
    getPendingLabel,
    getPendingShortLabel,
} from '../../pages/chatPageSupport'
import {
    formatDateTime,
} from '../../utils/format'
import './ChatHistoryRow.css'

type ChatHistoryRowProps = {
    chat: Chat
    active: boolean
    opening: boolean
    archiving: boolean
    pending?: PendingTurn
    now: number
    onOpen: (chatId: string) => void
    onRename: (chat: Chat) => void
    onArchive: (chat: Chat) => void
}

/**
 * An expanding, keyboard-operable action panel avoids clipped floating menus
 * inside the independently scrolling chat-history sidebar.
 */
export default function ChatHistoryRow({
    chat,
    active,
    opening,
    archiving,
    pending,
    now,
    onOpen,
    onRename,
    onArchive,
}: ChatHistoryRowProps) {
    const [actionsOpen, setActionsOpen] = useState(false)
    const rowRef = useRef<HTMLDivElement | null>(null)
    const toggleRef = useRef<HTMLButtonElement | null>(null)
    const actionsId = useId()

    useEffect(() => {
        if (!actionsOpen) {
            return
        }

        function closeOnOutsideClick(event: PointerEvent) {
            if (event.target instanceof Node
                && !rowRef.current?.contains(event.target)) {
                setActionsOpen(false)
            }
        }

        document.addEventListener('pointerdown', closeOnOutsideClick)
        return () => {
            document.removeEventListener('pointerdown', closeOnOutsideClick)
        }
    }, [actionsOpen])

    function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
        if (actionsOpen && event.key === 'Escape') {
            event.preventDefault()
            event.stopPropagation()
            setActionsOpen(false)
            toggleRef.current?.focus()
        }
    }

    return (
        <div
            ref={rowRef}
            className={active ? 'chat-list-row active' : 'chat-list-row'}
            onKeyDown={handleKeyDown}
        >
            <button
                type="button"
                className="chat-item"
                disabled={opening}
                onClick={() => onOpen(chat.id)}
                title={`Открыть чат «${chat.title}»`}
                aria-label={`Открыть чат «${chat.title}»`}
            >
                <span className="chat-item__icon" aria-hidden="true">✦</span>
                <span className="chat-item__body">
                    <strong title={chat.title}>
                        {opening ? 'Открытие...' : chat.title}
                    </strong>
                    <small>{formatDateTime(chat.updatedAt)}</small>
                    {pending && (
                        <span
                            className="chat-item__pending"
                            aria-label={getPendingLabel(pending, now)}
                        >
                            {getPendingShortLabel(pending)}
                        </span>
                    )}
                </span>
            </button>

            <button
                ref={toggleRef}
                type="button"
                className="chat-history-menu-toggle"
                aria-label={`Действия с чатом «${chat.title}»`}
                title={`Действия с чатом «${chat.title}»`}
                aria-controls={actionsId}
                aria-expanded={actionsOpen}
                onClick={() => setActionsOpen((value) => !value)}
            >
                <span aria-hidden="true">⋯</span>
            </button>

            {actionsOpen && (
                <div
                    id={actionsId}
                    className="chat-history-actions"
                    role="group"
                    aria-label={`Действия с чатом «${chat.title}»`}
                >
                    <button
                        type="button"
                        onClick={() => {
                            setActionsOpen(false)
                            onRename(chat)
                        }}
                    >
                        Переименовать
                    </button>
                    <button
                        type="button"
                        disabled={Boolean(pending) || archiving}
                        title={pending
                            ? 'Дождитесь завершения запроса'
                            : 'Убрать из списка, сохранив историю'}
                        onClick={() => {
                            setActionsOpen(false)
                            onArchive(chat)
                        }}
                    >
                        Убрать из списка
                    </button>
                </div>
            )}
        </div>
    )
}
