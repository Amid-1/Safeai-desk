// frontend/src/features/chat/controller/state/useChatRequestRefs.ts
// Одна точка владения DOM, сетевыми refs и блокировками отправки.
import { useEffect, useRef } from 'react'
import type { ChatDetails } from '../../api/chatApi'
import type { PendingTurn } from '../../lib/chatPage.helpers'
import type { ScrollIntent } from '../operations/chatControllerTypes'

export function useChatRequestRefs(
    activeChat: ChatDetails | null,
    pendingTurns: Record<string, PendingTurn>,
) {
    // Положение прокрутки и порядок асинхронных ответов.
    const messagesEndRef = useRef<HTMLDivElement | null>(null)
    const messagesContainerRef = useRef<HTMLDivElement | null>(null)
    const listRequestSequenceRef = useRef(0)
    const openRequestSequenceRef = useRef(0)
    const historyRequestSequenceRef = useRef(0)

    // Отмена устаревших запросов и сверок.
    const listControllerRef = useRef<AbortController | null>(null)
    const openControllerRef = useRef<AbortController | null>(null)
    const historyControllerRef = useRef<AbortController | null>(null)
    const reconciliationControllersRef = useRef(new Map<string, AbortController>())

    // Исключаем конкурентные вызовы между рендерами одного контроллера.
    const createInFlightRef = useRef(false)
    const sendInFlightRef = useRef(new Set<string>())
    const pendingTurnsRef = useRef(pendingTurns)
    const activeChatRef = useRef(activeChat)
    const scrollIntentRef = useRef<ScrollIntent>(null)

    useEffect(() => {
        pendingTurnsRef.current = pendingTurns
    }, [pendingTurns])
    useEffect(() => {
        activeChatRef.current = activeChat
    }, [activeChat])

    return {
        messagesEndRef, messagesContainerRef,
        listRequestSequenceRef, openRequestSequenceRef, historyRequestSequenceRef,
        listControllerRef, openControllerRef, historyControllerRef,
        reconciliationControllersRef, createInFlightRef, sendInFlightRef,
        pendingTurnsRef, activeChatRef, scrollIntentRef,
    }
}
