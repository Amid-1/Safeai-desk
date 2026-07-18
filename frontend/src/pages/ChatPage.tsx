// frontend/src/pages/ChatPage.tsx
import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import {
    createChat,
    getChatById,
    getChatMessages,
    getChats,
    sendMessage,
} from '../api/chatApi'
import type { Chat, ChatDetails, ChatMessage } from '../api/chatApi'
import { getApiErrorMessage } from '../api/http'
import { normalizePageResponse } from '../utils/page'
import { formatUsd } from '../utils/format'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlock'

const CHAT_PAGE_SIZE = 50
const MESSAGE_PAGE_SIZE = 50
const MESSAGE_MAX_LENGTH = 10_000

type DisplayMessage = ChatMessage & {
    uiStatus?: 'SENDING' | 'SEND_UNKNOWN'
}

function ChatPage() {
    const [chats, setChats] = useState<Chat[]>([])
    const [activeChat, setActiveChat] = useState<ChatDetails | null>(null)
    const [message, setMessage] = useState('')

    const [listError, setListError] = useState('')
    const [chatError, setChatError] = useState('')
    const [sendError, setSendError] = useState('')

    const [chatsLoading, setChatsLoading] = useState(true)
    const [moreChatsLoading, setMoreChatsLoading] = useState(false)
    const [chatCreating, setChatCreating] = useState(false)
    const [openingChatId, setOpeningChatId] = useState<string | null>(null)
    const [messageSendingChatId, setMessageSendingChatId] = useState<string | null>(null)
    const [historyLoading, setHistoryLoading] = useState(false)

    const [chatPage, setChatPage] = useState(0)
    const [chatTotalPages, setChatTotalPages] = useState(0)
    const [historyPage, setHistoryPage] = useState(0)
    const [historyTotalPages, setHistoryTotalPages] = useState<number | null>(null)
    const [reloadToken, setReloadToken] = useState(0)

    const messagesEndRef = useRef<HTMLDivElement | null>(null)
    const listRequestSequenceRef = useRef(0)
    const openRequestSequenceRef = useRef(0)
    const historyRequestSequenceRef = useRef(0)

    const activeChatSending =
        activeChat !== null && messageSendingChatId === activeChat.id

    useEffect(() => {
        const sequence = ++listRequestSequenceRef.current

        async function loadInitialChats() {
            setChatsLoading(true)
            setListError('')

            try {
                const response = await getChats(0, CHAT_PAGE_SIZE)

                if (sequence !== listRequestSequenceRef.current) {
                    return
                }

                const normalized = normalizePageResponse(response)
                setChats(normalized.content)
                setChatPage(0)
                setChatTotalPages(normalized.totalPages)

                if (normalized.content.length > 0) {
                    await openChat(normalized.content[0].id)
                } else {
                    setActiveChat(null)
                }
            } catch (error) {
                if (sequence === listRequestSequenceRef.current) {
                    setListError(getApiErrorMessage(error, 'Не удалось загрузить чаты.'))
                }
            } finally {
                if (sequence === listRequestSequenceRef.current) {
                    setChatsLoading(false)
                }
            }
        }

        void loadInitialChats()

        return () => {
            listRequestSequenceRef.current += 1
            openRequestSequenceRef.current += 1
        }
    }, [reloadToken])

    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ block: 'end' })
    }, [activeChat?.id, activeChat?.messages.length, messageSendingChatId])

    async function openChat(chatId: string) {
        const sequence = ++openRequestSequenceRef.current

        setOpeningChatId(chatId)
        setChatError('')
        setSendError('')
        setHistoryPage(0)
        setHistoryTotalPages(null)
        historyRequestSequenceRef.current += 1

        try {
            const details = await getChatById(chatId)

            if (sequence !== openRequestSequenceRef.current) {
                return
            }

            setActiveChat(normalizeChatDetails(details))
        } catch (error) {
            if (sequence === openRequestSequenceRef.current) {
                setChatError(getApiErrorMessage(error, 'Не удалось открыть чат.'))
            }
        } finally {
            if (sequence === openRequestSequenceRef.current) {
                setOpeningChatId(null)
            }
        }
    }

    async function loadMoreChats() {
        if (moreChatsLoading || chatPage + 1 >= chatTotalPages) {
            return
        }

        const nextPage = chatPage + 1
        setMoreChatsLoading(true)
        setListError('')

        try {
            const response = await getChats(nextPage, CHAT_PAGE_SIZE)
            const normalized = normalizePageResponse(response)

            setChats((current) => mergeChats(current, normalized.content))
            setChatPage(nextPage)
            setChatTotalPages(normalized.totalPages)
        } catch (error) {
            setListError(getApiErrorMessage(error, 'Не удалось загрузить дополнительные чаты.'))
        } finally {
            setMoreChatsLoading(false)
        }
    }

    async function handleCreateChat() {
        if (chatCreating) return

        setChatCreating(true)
        setListError('')

        try {
            const chat = await createChat('Новый чат')
            setChats((current) => [chat, ...current.filter((item) => item.id !== chat.id)])
            setActiveChat({ ...chat, messages: [] })
            setHistoryPage(0)
            setHistoryTotalPages(null)
        } catch (error) {
            setListError(getApiErrorMessage(error, 'Не удалось создать чат.'))
        } finally {
            setChatCreating(false)
        }
    }

    async function handleSendMessage() {
        const content = message.trim()

        if (!activeChat || !content || messageSendingChatId) {
            return
        }

        const chatId = activeChat.id
        const optimisticMessage = createOptimisticUserMessage(content)

        setSendError('')
        setMessage('')
        setMessageSendingChatId(chatId)
        appendMessage(chatId, optimisticMessage)

        try {
            const updated = normalizeChatDetails(await sendMessage(chatId, content))

            setActiveChat((current) => current?.id === chatId ? updated : current)
            moveChatToTop(updated)
        } catch (error) {
            setSendError(getApiErrorMessage(error, 'Не удалось отправить сообщение.'))

            try {
                const reloaded = normalizeChatDetails(await getChatById(chatId))
                setActiveChat((current) => current?.id === chatId ? reloaded : current)
                moveChatToTop(reloaded)
            } catch {
                markOptimisticMessageUnknown(chatId, optimisticMessage.id)
            }
        } finally {
            setMessageSendingChatId(null)
        }
    }

    async function loadEarlierHistory() {
        if (!activeChat || historyLoading) {
            return
        }

        if (historyTotalPages !== null && historyPage >= historyTotalPages) {
            return
        }

        const chatId = activeChat.id
        const sequence = ++historyRequestSequenceRef.current
        const pageToLoad = historyPage

        setHistoryLoading(true)
        setChatError('')

        try {
            const response = await getChatMessages(chatId, pageToLoad, MESSAGE_PAGE_SIZE)

            if (
                sequence !== historyRequestSequenceRef.current
                || activeChat.id !== chatId
            ) {
                return
            }

            const normalized = normalizePageResponse(response)
            setHistoryPage(pageToLoad + 1)
            setHistoryTotalPages(normalized.totalPages)
            setActiveChat((current) => {
                if (!current || current.id !== chatId) {
                    return current
                }

                return {
                    ...current,
                    messages: mergeMessages(normalized.content, current.messages),
                }
            })
        } catch (error) {
            if (sequence === historyRequestSequenceRef.current) {
                setChatError(getApiErrorMessage(error, 'Не удалось загрузить историю сообщений.'))
            }
        } finally {
            if (sequence === historyRequestSequenceRef.current) {
                setHistoryLoading(false)
            }
        }
    }

    function appendMessage(chatId: string, optimisticMessage: DisplayMessage) {
        setActiveChat((current) => {
            if (!current || current.id !== chatId) return current
            return { ...current, messages: [...current.messages, optimisticMessage] }
        })
    }

    function markOptimisticMessageUnknown(chatId: string, messageId: string) {
        setActiveChat((current) => {
            if (!current || current.id !== chatId) return current
            return {
                ...current,
                messages: current.messages.map((item) =>
                    item.id === messageId
                        ? { ...item, uiStatus: 'SEND_UNKNOWN' } as DisplayMessage
                        : item
                ),
            }
        })
    }

    function moveChatToTop(details: ChatDetails) {
        const updated: Chat = {
            id: details.id,
            title: details.title,
            createdAt: details.createdAt,
            updatedAt: details.updatedAt,
        }

        setChats((current) => [
            updated,
            ...current.filter((chat) => chat.id !== updated.id),
        ])
    }

    function handleTextareaKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
        if (event.key === 'Enter' && event.ctrlKey && !activeChatSending) {
            event.preventDefault()
            void handleSendMessage()
        }
    }

    const canLoadEarlier =
        activeChat !== null
        && (historyTotalPages === null || historyPage < historyTotalPages)

    return (
        <div className="page">
            <h1>Чат</h1>

            {listError && (
                <ErrorState
                    title="Ошибка списка чатов"
                    message={listError}
                    action={
                        <button type="button" onClick={() => setReloadToken((value) => value + 1)}>
                            Повторить
                        </button>
                    }
                />
            )}

            <div className="chat-layout">
                <aside className="card sidebar">
                    <button type="button" onClick={() => void handleCreateChat()} disabled={chatCreating}>
                        {chatCreating ? 'Создание...' : 'Создать чат'}
                    </button>
                    <h3>Чаты</h3>

                    {chatsLoading && <p className="muted">Загрузка чатов...</p>}
                    {!chatsLoading && chats.length === 0 && <p>Чатов пока нет.</p>}

                    {chats.map((chat) => (
                        <button
                            key={chat.id}
                            type="button"
                            className={activeChat?.id === chat.id ? 'chat-item active' : 'chat-item'}
                            disabled={openingChatId === chat.id}
                            onClick={() => void openChat(chat.id)}
                        >
                            {openingChatId === chat.id ? 'Открытие...' : chat.title}
                        </button>
                    ))}

                    {chatPage + 1 < chatTotalPages && (
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={moreChatsLoading}
                            onClick={() => void loadMoreChats()}
                        >
                            {moreChatsLoading ? 'Загрузка...' : 'Показать ещё чаты'}
                        </button>
                    )}
                </aside>

                <section className="card chat-panel">
                    {chatsLoading && <LoadingState message="Загрузка чата..." />}
                    {!chatsLoading && !activeChat && (
                        <EmptyState message="Создайте чат, чтобы начать общение." />
                    )}

                    {activeChat && (
                        <>
                            <h2>{activeChat.title}</h2>

                            {chatError && <div className="error">{chatError}</div>}
                            {sendError && <div className="error">{sendError}</div>}

                            {canLoadEarlier && (
                                <button
                                    type="button"
                                    className="secondary-button"
                                    disabled={historyLoading}
                                    onClick={() => void loadEarlierHistory()}
                                >
                                    {historyLoading ? 'Загрузка истории...' : 'Загрузить более ранние сообщения'}
                                </button>
                            )}

                            <div className="messages" aria-live="polite">
                                {activeChat.messages.length === 0 && !activeChatSending && (
                                    <p>Сообщений пока нет.</p>
                                )}

                                {(activeChat.messages as DisplayMessage[]).map((item) => (
                                    <div
                                        key={item.id}
                                        className={`message ${item.role.toLowerCase()} ${item.status.toLowerCase()}`}
                                    >
                                        <strong>{item.role}</strong>

                                        {item.status === 'FAILED' && (
                                            <span className="status-badge status-disabled">Ошибка</span>
                                        )}

                                        {item.uiStatus === 'SENDING' && (
                                            <span className="status-badge status-disabled">Отправка</span>
                                        )}

                                        {item.uiStatus === 'SEND_UNKNOWN' && (
                                            <span className="status-badge status-disabled">
                                                Статус отправки неизвестен
                                            </span>
                                        )}

                                        <p>{item.content}</p>

                                        {item.role === 'ASSISTANT' && (
                                            <small>
                                                модель: {item.model ?? '—'} | вход: {item.inputTokens ?? 0}
                                                {' '}| выход: {item.outputTokens ?? 0} | стоимость: {formatUsd(item.costUsd)}
                                            </small>
                                        )}
                                    </div>
                                ))}

                                {activeChatSending && (
                                    <div className="message assistant pending">
                                        <strong>ASSISTANT</strong>
                                        <p>Формируется ответ...</p>
                                    </div>
                                )}

                                <div ref={messagesEndRef} />
                            </div>

                            <div className="message-form">
                                <div style={{ width: '100%' }}>
                                    <textarea
                                        rows={3}
                                        value={message}
                                        onChange={(event) => setMessage(event.target.value)}
                                        onKeyDown={handleTextareaKeyDown}
                                        placeholder="Введите сообщение. Ctrl+Enter — отправить"
                                        maxLength={MESSAGE_MAX_LENGTH}
                                        disabled={activeChatSending}
                                    />
                                    <small className="muted">
                                        {message.length} / {MESSAGE_MAX_LENGTH}
                                    </small>
                                </div>

                                <button
                                    type="button"
                                    onClick={() => void handleSendMessage()}
                                    disabled={activeChatSending || !message.trim()}
                                >
                                    {activeChatSending ? 'Отправка...' : 'Отправить'}
                                </button>
                            </div>
                        </>
                    )}
                </section>
            </div>
        </div>
    )
}

function normalizeChatDetails(chat: ChatDetails): ChatDetails {
    return {
        ...chat,
        messages: Array.isArray(chat.messages) ? chat.messages : [],
    }
}

function createOptimisticUserMessage(content: string): DisplayMessage {
    return {
        id: `temp-${Date.now()}-${Math.random().toString(16).slice(2)}`,
        role: 'USER',
        content,
        model: null,
        inputTokens: null,
        outputTokens: null,
        costUsd: null,
        createdAt: new Date().toISOString(),
        status: 'COMPLETED',
        uiStatus: 'SENDING',
    }
}

function mergeChats(current: Chat[], incoming: Chat[]): Chat[] {
    const map = new Map(current.map((chat) => [chat.id, chat]))
    incoming.forEach((chat) => map.set(chat.id, chat))
    return [...map.values()]
}

function mergeMessages(first: ChatMessage[], second: ChatMessage[]): ChatMessage[] {
    const map = new Map<string, ChatMessage>()
    ;[...first, ...second].forEach((message) => map.set(message.id, message))

    return [...map.values()].sort((a, b) => {
        const time = a.createdAt.localeCompare(b.createdAt)
        return time !== 0 ? time : a.id.localeCompare(b.id)
    })
}

export default ChatPage
