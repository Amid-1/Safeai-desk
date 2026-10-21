// frontend/src/features/chat/controller/operations/chatTurnSubmission.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatTurnSubmission.ts
// Операции контроллера чата. Исходные сценарии сохранены.
// ============================================================
import type { KeyboardEvent } from 'react'
import { sendMessage } from '../../api/chatApi'
import type { PendingTurn } from '../../lib/chatPage.helpers'
import { createPendingTurn, hasMeaningfulContent, isSafeToPrepareNewRequest, normalizeMessageContent } from '../../lib/chatPage.helpers'
import { createSecureUuid } from '../../../../shared/lib/secureUuid'
import { getRetryAfterSeconds } from '../../lib/chatPageSupport'
import type { ChatControllerDependencies } from './chatControllerTypes'

export function createTurnSubmission(deps: Pick<ChatControllerDependencies,
    | 'capabilities'
    | 'activeChat'
    | 'knowledgeMode'
    | 'knowledgeBaseId'
    | 'drafts'
    | 'clock'
    | 'pendingTurnsRef'
    | 'activeChatRef'
    | 'scrollIntentRef'
    | 'setSendError'
    | 'activePendingTurn'
    | 'activeChatHasPending'
    | 'refreshChatTitle'
    | 'handleSendFailure'
    | 'applySendSuccess'
    | 'finishPendingTurn'
    | 'setDraft'
    | 'putPendingTurn'
    | 'sendInFlightRef'
>) {
    const { capabilities, activeChat, knowledgeMode, knowledgeBaseId, drafts, clock, pendingTurnsRef, activeChatRef, scrollIntentRef, setSendError, activePendingTurn, activeChatHasPending, refreshChatTitle, handleSendFailure, applySendSuccess, finishPendingTurn, setDraft, putPendingTurn, sendInFlightRef } = deps

    async function handleSendMessage() {
        const chat = activeChatRef.current

        if (!chat) {
            return
        }

        const rawDraft = drafts[chat.id] ?? ''
        const content = normalizeMessageContent(
            rawDraft,
        )

        if (
            !hasMeaningfulContent(content)
            || pendingTurnsRef.current[chat.id]
            || sendInFlightRef.current.has(chat.id)
        ) {
            return
        }

        if (
            content.length
            > capabilities.maxMessageChars
        ) {
            setSendError(
                `Сообщение не должно превышать ${capabilities.maxMessageChars} символов.`,
            )
            return
        }

        if (knowledgeMode !== 'GENERAL' && !knowledgeBaseId) {
            setSendError('Выберите базу знаний для RAG-режима.')
            return
        }

        const clientRequestId =
            createSecureUuid()

        const pending = createPendingTurn(
            chat.id,
            content,
            clientRequestId,
            knowledgeMode === 'GENERAL' ? null : knowledgeBaseId,
            knowledgeMode,
        )

        setSendError('')
        setDraft(chat.id, '')
        putPendingTurn(pending)

        scrollIntentRef.current = {
            type: 'BOTTOM',
        }

        await executePendingTurn(pending)
    }

    async function retryPendingTurn() {
        if (!activePendingTurn) {
            return
        }

        const remaining = getRetryAfterSeconds(
            activePendingTurn,
            clock,
        )

        if (
            activePendingTurn.status
                === 'RATE_LIMITED'
            && remaining > 0
        ) {
            return
        }

        await executePendingTurn({
            ...activePendingTurn,
            status: 'SENDING',
            error: null,
            retryAfterUntil: null,
        })
    }

    const sendingRequests = new Set<string>()

    async function executePendingTurn(
        pending: PendingTurn,
    ) {
        // Предотвращаем конкурентные POST для одного turn в пределах рендера.
        // Между рендерами блокировка хранится в неизменяемом ref контроллера.
        const requestKey = `${pending.chatId}:${pending.clientRequestId}`
        if (sendingRequests.has(requestKey)
            || sendInFlightRef.current.has(pending.chatId)) {
            return
        }
        sendingRequests.add(requestKey)
        sendInFlightRef.current.add(pending.chatId)

        putPendingTurn({
            ...pending,
            status: 'SENDING',
            error: null,
            retryAfterUntil: null,
        })

        try {
            const result = await sendMessage(
                pending.chatId,
                {
                    content: pending.content,
                    clientRequestId:
                        pending.clientRequestId,
                    knowledgeBaseId:
                        pending.knowledgeBaseId,
                    knowledgeMode:
                        pending.knowledgeMode,
                },
            )

            applySendSuccess(
                pending,
                result,
            )
            void refreshChatTitle(pending.chatId)
        } catch (error) {
            await handleSendFailure(
                pending,
                error,
            )
        } finally {
            sendingRequests.delete(requestKey)
            sendInFlightRef.current.delete(pending.chatId)
        }
    }

    function prepareNewRequestFromPending() {
        if (
            !activeChat
            || !activePendingTurn
            || !isSafeToPrepareNewRequest(
                activePendingTurn.status,
            )
        ) {
            return
        }

        setDraft(
            activeChat.id,
            activePendingTurn.content,
        )
        finishPendingTurn(activeChat.id)
        setSendError('')
    }

    function dismissUnsafePending() {
        if (!activeChat || !activePendingTurn) {
            return
        }

        if (
            activePendingTurn.status !== 'AMBIGUOUS'
            && activePendingTurn.status !== 'IDEMPOTENCY_CONFLICT'
        ) {
            return
        }

        finishPendingTurn(activeChat.id)
        setSendError('')
    }

    async function copyPendingText() {
        if (!activePendingTurn) {
            return
        }

        try {
            await navigator.clipboard.writeText(
                activePendingTurn.content,
            )
        } catch {
            setSendError(
                'Не удалось скопировать текст в буфер обмена.',
            )
        }
    }

    function handleTextareaKeyDown(
        event: KeyboardEvent<HTMLTextAreaElement>,
    ) {
        if (
            event.key === 'Enter'
            && event.ctrlKey
            && !activeChatHasPending
        ) {
            event.preventDefault()
            void handleSendMessage()
        }
    }

    return { handleSendMessage, retryPendingTurn, prepareNewRequestFromPending, dismissUnsafePending, copyPendingText, handleTextareaKeyDown }
}
