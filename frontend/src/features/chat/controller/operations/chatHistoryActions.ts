// frontend/src/features/chat/controller/operations/chatHistoryActions.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatHistoryActions.ts
// Операции контроллера чата. Исходные сценарии сохранены.
// ============================================================
import { getChatMessages } from '../../api/chatApi'
import { mergeMessages } from '../../lib/chatPage.helpers'
import { getApiErrorMessage } from '../../../../shared/api/http'
import { isRequestAborted } from '../../lib/chatPageSupport'
import type { ChatControllerDependencies } from './chatControllerTypes'

export function createHistoryActions(deps: Pick<ChatControllerDependencies,
    | 'historyLoading'
    | 'historyPage'
    | 'historyHasNext'
    | 'messagesContainerRef'
    | 'historyRequestSequenceRef'
    | 'historyControllerRef'
    | 'activeChatRef'
    | 'scrollIntentRef'
    | 'setActiveChat'
    | 'setChatError'
    | 'setHistoryLoading'
    | 'setHistoryPage'
    | 'setHistoryHasNext'
    | 'messagePageSize'
    | 'restoreAnswerPassports'
>) {
    const { historyLoading, historyPage, historyHasNext, messagesContainerRef, historyRequestSequenceRef, historyControllerRef, activeChatRef, scrollIntentRef, setActiveChat, setChatError, setHistoryLoading, setHistoryPage, setHistoryHasNext, messagePageSize, restoreAnswerPassports } = deps

    async function loadEarlierHistory() {
        const chat = activeChatRef.current

        if (
            !chat
            || historyLoading
            || !historyHasNext
        ) {
            return
        }

        const chatId = chat.id
        const sequence =
            ++historyRequestSequenceRef.current
        const pageToLoad = historyPage

        historyControllerRef.current?.abort()

        const controller =
            new AbortController()

        historyControllerRef.current =
            controller

        const container =
            messagesContainerRef.current

        if (container) {
            scrollIntentRef.current = {
                type: 'PRESERVE_TOP',
                scrollHeight:
                    container.scrollHeight,
                scrollTop:
                    container.scrollTop,
            }
        }

        setHistoryLoading(true)
        setChatError('')

        try {
            const response = await getChatMessages(
                chatId,
                pageToLoad,
                messagePageSize,
                {
                    signal:
                        controller.signal,
                },
            )

            if (
                sequence
                    !== historyRequestSequenceRef.current
                || activeChatRef.current?.id
                    !== chatId
            ) {
                return
            }

            setHistoryPage(response.page + 1)
            setHistoryHasNext(response.hasNext)

            setActiveChat((current) => {
                if (
                    !current
                    || current.id !== chatId
                ) {
                    return current
                }

                return {
                    ...current,
                    messages: mergeMessages(
                        response.content,
                        current.messages,
                    ),
                }
            })

            void restoreAnswerPassports(
                chatId,
                response.content,
                controller.signal,
            )
        } catch (error) {
            if (
                sequence
                    === historyRequestSequenceRef.current
                && !isRequestAborted(error)
            ) {
                setChatError(
                    getApiErrorMessage(
                        error,
                        'Не удалось загрузить историю сообщений.',
                    ),
                )
            }
        } finally {
            if (
                sequence
                === historyRequestSequenceRef.current
            ) {
                setHistoryLoading(false)
            }
        }
    }

    return { loadEarlierHistory }
}
