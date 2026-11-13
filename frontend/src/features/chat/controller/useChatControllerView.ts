// Производное состояние и жизненный цикл визуального представления чата.
import { useEffect, useLayoutEffect, useMemo } from 'react'
import { buildDisplayMessages, isProcessingPendingStatus } from '../lib/chatPage.helpers'
import { getRetryAfterSeconds } from '../lib/chatPageSupport'
import { DEFAULT_CHAT_PAGE_SIZE, DEFAULT_MESSAGE_PAGE_SIZE } from './operations/chatControllerDefaults'
import type { ChatControllerState } from './useChatControllerState'

export function useChatControllerView(state: ChatControllerState) {
    const {
        activeChat, pendingTurns, drafts, capabilities, clock, setClock,
        messagesEndRef, messagesContainerRef, scrollIntentRef,
        listControllerRef, openControllerRef, historyControllerRef,
        reconciliationControllersRef,
    } = state

    const activePendingTurn = activeChat
        ? pendingTurns[activeChat.id]
        : undefined
    const activeDraft = activeChat ? drafts[activeChat.id] ?? '' : ''

    const displayMessages = useMemo(
        () => activeChat
            ? buildDisplayMessages(activeChat.messages, activePendingTurn)
            : [],
        [activeChat, activePendingTurn],
    )

    const lastAssistantModel = useMemo(() => {
        for (let index = displayMessages.length - 1; index >= 0; index -= 1) {
            const message = displayMessages[index]
            if (message.role === 'ASSISTANT') {
                return message.model
            }
        }
        return null
    }, [displayMessages])

    const activeChatHasPending = Boolean(activePendingTurn)
    const activeChatBusy = Boolean(
        activePendingTurn && isProcessingPendingStatus(activePendingTurn.status),
    )
    const messagePageSize = Math.min(
        DEFAULT_MESSAGE_PAGE_SIZE, capabilities.maxMessagePageSize,
    )
    const chatPageSize = Math.min(
        DEFAULT_CHAT_PAGE_SIZE, capabilities.maxChatPageSize,
    )

    useEffect(() => {
        if (!activePendingTurn?.retryAfterUntil) return

        const timerId = window.setInterval(() => setClock(Date.now()), 1_000)
        return () => window.clearInterval(timerId)
    }, [activePendingTurn?.retryAfterUntil, setClock])

    useLayoutEffect(() => {
        const intent = scrollIntentRef.current
        if (!intent) return

        if (intent.type === 'BOTTOM') {
            messagesEndRef.current?.scrollIntoView({ block: 'end' })
        } else {
            const container = messagesContainerRef.current
            if (container) {
                container.scrollTop = intent.scrollTop
                    + container.scrollHeight - intent.scrollHeight
            }
        }
        scrollIntentRef.current = null
    }, [activeChat?.id, activeChat?.messages.length, activePendingTurn?.status,
        scrollIntentRef, messagesEndRef, messagesContainerRef])

    useEffect(() => {
        const controllers = reconciliationControllersRef.current
        return () => {
            listControllerRef.current?.abort()
            openControllerRef.current?.abort()
            historyControllerRef.current?.abort()
            controllers.forEach((controller) => controller.abort())
            controllers.clear()
        }
    }, [listControllerRef, openControllerRef, historyControllerRef,
        reconciliationControllersRef])

    return {
        activePendingTurn,
        activeDraft,
        displayMessages,
        lastAssistantModel,
        activeChatHasPending,
        activeChatBusy,
        messagePageSize,
        chatPageSize,
        retryAfterSeconds: activePendingTurn
            ? getRetryAfterSeconds(activePendingTurn, clock)
            : 0,
    }
}

export type ChatControllerView = ReturnType<typeof useChatControllerView>
