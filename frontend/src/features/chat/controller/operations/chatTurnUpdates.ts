// frontend/src/features/chat/controller/operations/chatTurnUpdates.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatTurnUpdates.ts
// Операции контроллера чата. Исходные сценарии сохранены.
// ============================================================
import type { ChatDetails, ChatMessage, ChatTurnStatus, SendMessageResponse } from '../../api/chatApi'
import type { PendingTurn } from '../../lib/chatPage.helpers'
import { mergeChatDetails, mergeMessages, moveChatToTop } from '../../lib/chatPage.helpers'
import type { ChatControllerDependencies } from './chatControllerTypes'

export function createTurnUpdates(deps: Pick<ChatControllerDependencies,
    | 'reconciliationControllersRef'
    | 'scrollIntentRef'
    | 'pendingTurnsRef'
    | 'setChats'
    | 'setActiveChat'
    | 'setAnswerPassports'
    | 'setDrafts'
    | 'setPendingTurns'
    | 'setSendError'
>) {
    const { reconciliationControllersRef, scrollIntentRef, pendingTurnsRef, setChats, setActiveChat, setAnswerPassports, setDrafts, setPendingTurns, setSendError } = deps

    function applySendSuccess(
        pending: PendingTurn,
        result: SendMessageResponse,
    ) {
        if (
            result.chatId !== pending.chatId
            || result.clientRequestId
                !== pending.clientRequestId
        ) {
            throw new Error(
                'Backend вернул SendMessageResponse для другого turn',
            )
        }

        finishPendingTurn(pending.chatId)

        if (result.answerPassport) {
            setAnswerPassports((current) => ({
                ...current,
                [result.assistantMessage.id]: result.answerPassport!,
            }))
        }

        setActiveChat((current) => {
            if (
                !current
                || current.id !== pending.chatId
            ) {
                return current
            }

            const updated: ChatDetails = {
                ...current,
                updatedAt:
                    result.chatUpdatedAt
                    ?? current.updatedAt,
                messages: mergeMessages(
                    current.messages,
                    [
                        result.userMessage,
                        result.assistantMessage,
                    ],
                ),
            }

            scrollIntentRef.current = {
                type: 'BOTTOM',
            }

            setChats((chatList) =>
                moveChatToTop(
                    chatList,
                    updated,
                ),
            )

            return updated
        })

        setSendError('')
    }

    function applyTurnSuccess(
        pending: PendingTurn,
        turn: ChatTurnStatus,
    ) {
        /*
         * Сохраняем nullable-поля в локальные const до передачи
         * callback в setActiveChat. TypeScript не обязан сохранять
         * narrowing для свойств объекта внутри отложенного callback,
         * поэтому прямое использование turn.userMessage /
         * turn.assistantMessage там приводит к TS2322.
         */
        const userMessage = turn.userMessage
        const assistantMessage =
            turn.assistantMessage

        if (!userMessage || !assistantMessage) {
            return
        }

        finishPendingTurn(pending.chatId)

        setActiveChat((current) => {
            if (
                !current
                || current.id !== pending.chatId
            ) {
                return current
            }

            const updated: ChatDetails = {
                ...current,
                updatedAt: turn.completedAt
                    ?? turn.updatedAt,
                messages: mergeMessages(
                    current.messages,
                    [
                        userMessage,
                        assistantMessage,
                    ],
                ),
            }

            scrollIntentRef.current = {
                type: 'BOTTOM',
            }

            setChats((chatList) =>
                moveChatToTop(
                    chatList,
                    updated,
                ),
            )

            return updated
        })

        setSendError('')
    }

    function applyReloadedSuccess(
        pending: PendingTurn,
        details: ChatDetails,
    ) {
        finishPendingTurn(pending.chatId)

        setActiveChat((current) => {
            if (
                !current
                || current.id !== pending.chatId
            ) {
                return current
            }

            return mergeChatDetails(
                current,
                details,
            )
        })

        setChats((current) =>
            moveChatToTop(
                current,
                details,
            ),
        )
        setSendError('')
    }

    function applyTurnMessages(
        chatId: string,
        turn: ChatTurnStatus,
    ) {
        const messages: ChatMessage[] = []

        if (turn.userMessage) {
            messages.push(turn.userMessage)
        }

        if (turn.assistantMessage) {
            messages.push(turn.assistantMessage)
        }

        if (messages.length === 0) {
            return
        }

        setActiveChat((current) => {
            if (!current || current.id !== chatId) {
                return current
            }

            return {
                ...current,
                updatedAt: turn.updatedAt,
                messages: mergeMessages(
                    current.messages,
                    messages,
                ),
            }
        })
    }

    function finishPendingTurn(chatId: string) {
        reconciliationControllersRef.current
            .get(chatId)
            ?.abort()
        reconciliationControllersRef.current.delete(
            chatId,
        )

        const currentPending = { ...pendingTurnsRef.current }
        delete currentPending[chatId]
        pendingTurnsRef.current = currentPending

        setPendingTurns((current) => {
            const next = { ...current }
            delete next[chatId]
            return next
        })
    }

    function setDraft(
        chatId: string,
        value: string,
    ) {
        setDrafts((current) => ({
            ...current,
            [chatId]: value,
        }))
    }

    function putPendingTurn(
        pending: PendingTurn,
    ) {
        // Немедленно резервируем turn до следующего рендера React.
        pendingTurnsRef.current = {
            ...pendingTurnsRef.current,
            [pending.chatId]: pending,
        }
        setPendingTurns((current) => ({
            ...current,
            [pending.chatId]: pending,
        }))
    }



    return { applySendSuccess, applyTurnSuccess, applyReloadedSuccess, applyTurnMessages, finishPendingTurn, setDraft, putPendingTurn }
}
