// frontend/src/pages/ChatPage.tsx
import { useEffect, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { createChat, getChatById, getChats, sendMessage } from '../api/chatApi'
import type { Chat, ChatDetails } from '../api/chatApi'
import { getApiErrorMessage } from '../api/http'

function ChatPage() {
    const [chats, setChats] = useState<Chat[]>([])
    const [activeChat, setActiveChat] = useState<ChatDetails | null>(null)
    const [message, setMessage] = useState('')
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)

    useEffect(() => {
        async function loadChats() {
            try {
                const data = await getChats()
                setChats(data)
            } catch (err) {
                setError(getApiErrorMessage(err, 'Failed to load chats'))
            }
        }

        void loadChats()
    }, [])

    async function handleCreateChat() {
        setError('')
        setLoading(true)

        try {
            const chat = await createChat(`Demo chat ${new Date().toLocaleTimeString()}`)
            setChats((prev) => [chat, ...prev])
            setActiveChat({
                id: chat.id,
                title: chat.title,
                createdAt: chat.createdAt,
                messages: [],
            })
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to create chat'))
        } finally {
            setLoading(false)
        }
    }

    async function handleSendMessage() {
        if (!activeChat || !message.trim()) {
            return
        }

        setError('')
        setLoading(true)

        try {
            const updatedChat = await sendMessage(activeChat.id, message.trim())
            setActiveChat(updatedChat)
            setMessage('')
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to send message'))
        } finally {
            setLoading(false)
        }
    }

    async function handleOpenChat(chatId: string) {
        setError('')
        setLoading(true)

        try {
            const chatDetails = await getChatById(chatId)
            setActiveChat(chatDetails)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Failed to open chat'))
        } finally {
            setLoading(false)
        }
    }

    function handleInputKeyDown(event: KeyboardEvent<HTMLInputElement>) {
        if (event.key === 'Enter') {
            void handleSendMessage()
        }
    }

    return (
        <div className="page">
            <h1>Chat</h1>

            {error && <div className="error">{error}</div>}

            <div className="chat-layout">
                <aside className="card sidebar">
                    <button onClick={handleCreateChat} disabled={loading}>
                        Create chat
                    </button>

                    <h3>Chats</h3>

                    {chats.map((chat) => (
                        <button
                            key={chat.id}
                            className="chat-item"
                            onClick={() => {
                                void handleOpenChat(chat.id)
                            }}
                        >
                            {chat.title}
                        </button>
                    ))}
                </aside>

                <section className="card chat-panel">
                    {!activeChat && <p>Create a chat to start.</p>}

                    {activeChat && (
                        <>
                            <h2>{activeChat.title}</h2>

                            <div className="messages">
                                {activeChat.messages.map((msg) => (
                                    <div key={msg.id} className={`message ${msg.role.toLowerCase()}`}>
                                        <strong>{msg.role}</strong>
                                        <p>{msg.content}</p>

                                        {msg.role === 'ASSISTANT' && (
                                            <small>
                                                model: {msg.model} | input: {msg.inputTokens} | output: {msg.outputTokens} | cost: {msg.costUsd}
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
                                />

                                <button onClick={() => void handleSendMessage()} disabled={loading}>
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

export default ChatPage