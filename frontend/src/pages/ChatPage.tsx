// frontend/src/pages/ChatPage.tsx
import { useEffect, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { createChat, getChatById, getChats, sendMessage } from '../api/chatApi'
import type { Chat, ChatDetails } from '../api/chatApi'
import { getApiErrorMessage } from '../api/http'
import { getPageContent } from '../utils/page'

function ChatPage() {
    const [chats, setChats] = useState<Chat[]>([])
    const [activeChat, setActiveChat] = useState<ChatDetails | null>(null)
    const [message, setMessage] = useState('')
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)
    const [initialLoading, setInitialLoading] = useState(true)

    useEffect(() => {
        async function loadChats() {
            setError('')
            setInitialLoading(true)

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
                setInitialLoading(false)
            }
        }

        void loadChats()
    }, [])

    async function handleCreateChat() {
        if (loading) {
            return
        }

        setError('')
        setLoading(true)

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
            setLoading(false)
        }
    }

    async function handleSendMessage() {
        const trimmedMessage = message.trim()

        if (loading || !activeChat || !trimmedMessage) {
            return
        }

        setError('')
        setLoading(true)

        try {
            const updatedChat = await sendMessage(activeChat.id, trimmedMessage)

            setActiveChat(normalizeChatDetails(updatedChat))
            setMessage('')

            setChats((prev) =>
                prev.map((chat) =>
                    chat.id === updatedChat.id
                        ? {
                            ...chat,
                            title: updatedChat.title,
                            updatedAt: updatedChat.updatedAt,
                        }
                        : chat
                )
            )
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to send message'))
        } finally {
            setLoading(false)
        }
    }

    async function handleOpenChat(chatId: string) {
        if (loading) {
            return
        }

        setError('')
        setLoading(true)

        try {
            const chatDetails = await getChatById(chatId)
            setActiveChat(normalizeChatDetails(chatDetails))
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to open chat'))
        } finally {
            setLoading(false)
        }
    }

    function handleInputKeyDown(event: KeyboardEvent<HTMLInputElement>) {
        if (event.key === 'Enter' && !loading) {
            void handleSendMessage()
        }
    }

    return (
        <div className="page">
            <h1>Chat</h1>

            {error && <div className="error">{error}</div>}
            {initialLoading && <p>Loading chats...</p>}

            <div className="chat-layout">
                <aside className="card sidebar">
                    <button onClick={() => void handleCreateChat()} disabled={loading}>
                        {loading ? 'Loading...' : 'Create chat'}
                    </button>

                    <h3>Chats</h3>

                    {!initialLoading && chats.length === 0 && <p>No chats yet.</p>}

                    {chats.map((chat) => (
                        <button
                            key={chat.id}
                            className={
                                activeChat?.id === chat.id
                                    ? 'chat-item active'
                                    : 'chat-item'
                            }
                            disabled={loading}
                            onClick={() => {
                                void handleOpenChat(chat.id)
                            }}
                        >
                            {chat.title}
                        </button>
                    ))}
                </aside>

                <section className="card chat-panel">
                    {!activeChat && !initialLoading && <p>Create a chat to start.</p>}

                    {activeChat && (
                        <>
                            <h2>{activeChat.title}</h2>

                            <div className="messages">
                                {activeChat.messages.length === 0 && (
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
                                                {msg.costUsd ?? 0}
                                            </small>
                                        )}
                                    </div>
                                ))}
                            </div>

                            <div className="message-form">
                                <input
                                    value={message}
                                    onChange={(event) => setMessage(event.target.value)}
                                    onKeyDown={handleInputKeyDown}
                                    placeholder="Type message..."
                                    disabled={loading}
                                />

                                <button
                                    onClick={() => void handleSendMessage()}
                                    disabled={loading || !message.trim()}
                                >
                                    {loading ? 'Sending...' : 'Send'}
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

export default ChatPage