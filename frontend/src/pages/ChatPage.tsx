// frontend/src/pages/ChatPage.tsx
import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { createChat, getChatById, getChats, sendMessage } from '../api/chatApi'
import type { Chat, ChatDetails, ChatMessage } from '../api/chatApi'
import { getApiErrorMessage } from '../api/http'
import { getPageContent } from '../utils/page'
import { formatUsd } from '../utils/format'
import { EmptyState, LoadingState } from '../components/StateBlock'

function ChatPage() {
    const [chats, setChats] = useState<Chat[]>([])
    const [activeChat, setActiveChat] = useState<ChatDetails | null>(null)
    const [message, setMessage] = useState('')
    const [error, setError] = useState('')

    const [chatsLoading, setChatsLoading] = useState(true)
    const [chatCreating, setChatCreating] = useState(false)
    const [openingChatId, setOpeningChatId] = useState<string | null>(null)
    const [messageSendingChatId, setMessageSendingChatId] = useState<string | null>(null)

    const messagesEndRef = useRef<HTMLDivElement | null>(null)

    const activeChatSending =
        activeChat !== null && messageSendingChatId === activeChat.id

    useEffect(() => {
        async function loadChats() {
            setError('')
            setChatsLoading(true)

            try {
                const data = await getChats()
                const content = getPageContent(data)

                setChats(content)

                if (content.length > 0) {
                    const firstChat = await getChatById(content[0].id)
                    setActiveChat(normalizeChatDetails(firstChat))
                }
            } catch (err) {
                setError(getApiErrorMessage(err, 'Failed to load chats'))
            } finally {
                setChatsLoading(false)
            }
        }

        void loadChats()
    }, [])

    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({
            block: 'end',
        })
    }, [
        activeChat?.id,
        activeChat?.messages.length,
        messageSendingChatId,
    ])

    async function handleCreateChat() {
        if (chatCreating) {
            return
        }

        setError('')
        setChatCreating(true)

        try {
            const chat = await createChat(`Demo chat ${new Date().toLocaleTimeString()}`)

            setChats((prev) => [chat, ...prev])
            setActiveChat({
                id: chat.id,
                title: chat.title,
                createdAt: chat.createdAt,
                updatedAt: chat.updatedAt,
                messages: [],
            })
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to create chat'))
        } finally {
            setChatCreating(false)
        }
    }

    async function handleSendMessage() {
        const trimmedMessage = message.trim()

        if (!activeChat || !trimmedMessage || messageSendingChatId) {
            return
        }

        const chatId = activeChat.id
        const optimisticMessage = createOptimisticUserMessage(trimmedMessage)

        setError('')
        setMessage('')
        setMessageSendingChatId(chatId)

        setActiveChat((current) => {
            if (!current || current.id !== chatId) {
                return current
            }

            return {
                ...current,
                messages: [
                    ...current.messages,
                    optimisticMessage,
                ],
            }
        })

        try {
            const updatedChat = await sendMessage(chatId, trimmedMessage)
            const normalizedChat = normalizeChatDetails(updatedChat)

            setActiveChat((current) =>
                current?.id === chatId ? normalizedChat : current
            )

            updateChatListItem(normalizedChat)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to send message'))

            try {
                const reloadedChat = await getChatById(chatId)
                const normalizedChat = normalizeChatDetails(reloadedChat)

                setActiveChat((current) =>
                    current?.id === chatId ? normalizedChat : current
                )

                updateChatListItem(normalizedChat)
            } catch {
                // Не перетираем исходную ошибку отправки ошибкой reload.
            }
        } finally {
            setMessageSendingChatId(null)
        }
    }

    async function handleOpenChat(chatId: string) {
        if (openingChatId === chatId) {
            return
        }

        setError('')
        setOpeningChatId(chatId)

        try {
            const chatDetails = await getChatById(chatId)
            setActiveChat(normalizeChatDetails(chatDetails))
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to open chat'))
        } finally {
            setOpeningChatId(null)
        }
    }

    function updateChatListItem(chatDetails: ChatDetails) {
        setChats((prev) =>
            prev.map((chat) =>
                chat.id === chatDetails.id
                    ? {
                        ...chat,
                        title: chatDetails.title,
                        updatedAt: chatDetails.updatedAt,
                    }
                    : chat
            )
        )
    }

    function handleTextareaKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
        if (event.key === 'Enter' && event.ctrlKey && !activeChatSending) {
            event.preventDefault()
            void handleSendMessage()
        }
    }

    return (
        <div className="page">
            <h1>Chat</h1>

            {error && <div className="error">{error}</div>}

            <div className="chat-layout">
                <aside className="card sidebar">
                    <button
                        type="button"
                        onClick={() => void handleCreateChat()}
                        disabled={chatCreating}
                    >
                        {chatCreating ? 'Creating...' : 'Create chat'}
                    </button>

                    <h3>Chats</h3>

                    {chatsLoading && <p className="muted">Loading chats...</p>}

                    {!chatsLoading && chats.length === 0 && (
                        <p>No chats yet.</p>
                    )}

                    {chats.map((chat) => (
                        <button
                            key={chat.id}
                            type="button"
                            className={
                                activeChat?.id === chat.id
                                    ? 'chat-item active'
                                    : 'chat-item'
                            }
                            disabled={openingChatId === chat.id}
                            onClick={() => {
                                void handleOpenChat(chat.id)
                            }}
                        >
                            {openingChatId === chat.id ? 'Opening...' : chat.title}
                        </button>
                    ))}
                </aside>

                <section className="card chat-panel">
                    {chatsLoading && <LoadingState message="Loading chat..." />}

                    {!chatsLoading && !activeChat && (
                        <EmptyState message="Create a chat to start." />
                    )}

                    {activeChat && (
                        <>
                            <h2>{activeChat.title}</h2>

                            <div className="messages">
                                {activeChat.messages.length === 0 && !activeChatSending && (
                                    <p>No messages yet.</p>
                                )}

                                {activeChat.messages.map((msg) => (
                                    <div
                                        key={msg.id}
                                        className={`message ${msg.role.toLowerCase()} ${msg.status?.toLowerCase() ?? ''}`}
                                    >
                                        <strong>{msg.role}</strong>

                                        {msg.status === 'FAILED' && (
                                            <span className="status-badge status-disabled">
                                                FAILED
                                            </span>
                                        )}

                                        <p>{msg.content}</p>

                                        {msg.role === 'ASSISTANT' && (
                                            <small>
                                                model: {msg.model ?? '-'} | input:{' '}
                                                {msg.inputTokens ?? 0} | output:{' '}
                                                {msg.outputTokens ?? 0} | cost:{' '}
                                                {formatUsd(msg.costUsd)}
                                            </small>
                                        )}
                                    </div>
                                ))}

                                {activeChatSending && (
                                    <div className="message assistant pending">
                                        <strong>ASSISTANT</strong>
                                        <p>Thinking...</p>
                                    </div>
                                )}

                                <div ref={messagesEndRef} />
                            </div>

                            <div className="message-form">
                                <textarea
                                    rows={3}
                                    value={message}
                                    onChange={(event) => setMessage(event.target.value)}
                                    onKeyDown={handleTextareaKeyDown}
                                    placeholder="Type message... Ctrl+Enter to send"
                                    disabled={activeChatSending}
                                />

                                <button
                                    type="button"
                                    onClick={() => void handleSendMessage()}
                                    disabled={activeChatSending || !message.trim()}
                                >
                                    {activeChatSending ? 'Sending...' : 'Send'}
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
        messages: chat.messages ?? [],
    }
}

function createOptimisticUserMessage(content: string): ChatMessage {
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
    }
}

export default ChatPage