// frontend/src/features/chat/controller/operations/useChatSessionLifecycle.ts
// ============================================================
// frontend/src/features/chat/controller/operations/useChatSessionLifecycle.ts
// Загрузка каталога и открытие чата с защитой от устаревших ответов.
// ============================================================
import { useCallback, useEffect } from 'react'
import {
    getAnswerPassport, getChatById, getChatCapabilities,
    getChatMessages, getChats, getChatTurnStatus,
} from '../../api/chatApi'
import type { ChatMessage } from '../../api/chatApi'
import { getApiErrorMessage } from '../../../../shared/api/http'
import { mergeMessages } from '../../lib/chatPage.helpers'
import { isRequestAborted } from '../../lib/chatPageSupport'
import { DEFAULT_CAPABILITIES, DEFAULT_CHAT_PAGE_SIZE } from './chatControllerDefaults'
import type { ChatControllerDependencies } from './chatControllerTypes'

export function useChatSessionLifecycle(deps: Pick<ChatControllerDependencies,
    | 'messagePageSize'
    | 'reloadToken'
    | 'openRequestSequenceRef'
    | 'historyRequestSequenceRef'
    | 'listRequestSequenceRef'
    | 'openControllerRef'
    | 'historyControllerRef'
    | 'listControllerRef'
    | 'scrollIntentRef'
    | 'setOpeningChatId'
    | 'setChatError'
    | 'setSendError'
    | 'setHistoryPage'
    | 'setHistoryHasNext'
    | 'setHistoryLoading'
    | 'setActiveChat'
    | 'setAnswerPassports'
    | 'setCapabilities'
    | 'setChats'
    | 'setChatPage'
    | 'setChatHasNext'
    | 'setChatsLoading'
    | 'setListError'
>) {
    const { messagePageSize, reloadToken, openRequestSequenceRef, historyRequestSequenceRef, listRequestSequenceRef, openControllerRef, historyControllerRef, listControllerRef, scrollIntentRef, setOpeningChatId, setChatError, setSendError, setHistoryPage, setHistoryHasNext, setHistoryLoading, setActiveChat, setAnswerPassports, setCapabilities, setChats, setChatPage, setChatHasNext, setChatsLoading, setListError } = deps

    const restoreAnswerPassports = useCallback(
        async (
            chatId: string,
            messages: ChatMessage[],
            signal: AbortSignal,
            expectedOpenSequence?: number,
        ) => {
            const clientRequestIds = [
                ...new Set(
                    messages
                        .filter((message) =>
                            message.role === 'USER'
                            && message.clientRequestId,
                        )
                        .map((message) => message.clientRequestId!),
                ),
            ]

            if (!clientRequestIds.length) {
                return
            }

            const turns = await Promise.allSettled(
                clientRequestIds.map((clientRequestId) =>
                    getChatTurnStatus(chatId, clientRequestId, { signal }),
                ),
            )

            if (
                signal.aborted
                || (
                    expectedOpenSequence !== undefined
                    && expectedOpenSequence !== openRequestSequenceRef.current
                )
            ) {
                return
            }

            const succeededTurns = turns.flatMap((result, index) =>
                result.status === 'fulfilled'
                && result.value.chatId === chatId
                && result.value.clientRequestId === clientRequestIds[index]
                && result.value.state === 'SUCCEEDED'
                && result.value.assistantMessage
                    ? [result.value]
                    : [],
            )

            const passports = await Promise.allSettled(
                succeededTurns.map(async (turn) => ({
                    assistantMessageId: turn.assistantMessage!.id,
                    expectedTurnId: turn.turnId,
                    passport: await getAnswerPassport(
                        chatId,
                        turn.turnId,
                        { signal },
                    ),
                })),
            )

            if (
                signal.aborted
                || (
                    expectedOpenSequence !== undefined
                    && expectedOpenSequence !== openRequestSequenceRef.current
                )
            ) {
                return
            }

            const restored = passports.flatMap((result) =>
                result.status === 'fulfilled'
                && result.value.passport.chatTurnId === result.value.expectedTurnId
                    ? [result.value]
                    : [],
            )

            if (!restored.length) {
                return
            }

            setAnswerPassports((current) => ({
                ...current,
                ...Object.fromEntries(
                    restored.map((item) => [
                        item.assistantMessageId,
                        item.passport,
                    ]),
                ),
            }))
        },
        [openRequestSequenceRef, setAnswerPassports],
    )

    const openChat = useCallback(
        async (chatId: string) => {
            const sequence =
                ++openRequestSequenceRef.current

            openControllerRef.current?.abort()
            historyControllerRef.current?.abort()

            const controller =
                new AbortController()

            openControllerRef.current =
                controller

            setOpeningChatId(chatId)
            setChatError('')
            setSendError('')
            setHistoryPage(1)
            setHistoryHasNext(false)
            setHistoryLoading(false)

            historyRequestSequenceRef.current += 1

            try {
                const [details, firstMessageSlice] =
                    await Promise.all([
                        getChatById(
                            chatId,
                            {
                                signal:
                                    controller.signal,
                            },
                        ),
                        getChatMessages(
                            chatId,
                            0,
                            messagePageSize,
                            {
                                signal:
                                    controller.signal,
                            },
                        ),
                    ])

                if (
                    sequence
                    !== openRequestSequenceRef.current
                ) {
                    return
                }

                scrollIntentRef.current = {
                    type: 'BOTTOM',
                }

                setActiveChat({
                    ...details,
                    // Источник истины для пагинации — ответ /messages.
                    // mergeMessages восстанавливает хронологический порядок.
                    messages: mergeMessages(
                        [],
                        firstMessageSlice.content,
                    ),
                })
                setHistoryPage(1)
                setHistoryHasNext(
                    firstMessageSlice.hasNext,
                )

                void restoreAnswerPassports(
                    chatId,
                    firstMessageSlice.content,
                    controller.signal,
                    sequence,
                )
            } catch (error) {
                if (
                    sequence
                    === openRequestSequenceRef.current
                    && !isRequestAborted(error)
                ) {
                    setChatError(
                        getApiErrorMessage(
                            error,
                            'Не удалось открыть чат.',
                        ),
                    )
                }
            } finally {
                if (
                    sequence
                    === openRequestSequenceRef.current
                ) {
                    setOpeningChatId(null)
                }
            }
        },
        [
            messagePageSize,
            restoreAnswerPassports,
            openRequestSequenceRef,
            historyRequestSequenceRef,
            openControllerRef,
            historyControllerRef,
            scrollIntentRef,
            setOpeningChatId,
            setChatError,
            setSendError,
            setHistoryPage,
            setHistoryHasNext,
            setHistoryLoading,
            setActiveChat,
        ],
    )

    useEffect(() => {
        const sequence =
            ++listRequestSequenceRef.current

        listControllerRef.current?.abort()

        const controller =
            new AbortController()

        listControllerRef.current =
            controller

        async function loadInitialChats() {
            setChatsLoading(true)
            setListError('')

            try {
                // Контракт возможностей появился вместе с новой версией Frontend.
                // При поэтапном развёртывании старый backend может ответить 404.
                // Ограниченные значения по умолчанию сохраняют совместимость.
                let runtimeCapabilities =
                    DEFAULT_CAPABILITIES

                try {
                    runtimeCapabilities =
                        await getChatCapabilities({
                            signal: controller.signal,
                        })
                } catch (error) {
                    if (isRequestAborted(error)) {
                        return
                    }
                }

                if (
                    sequence
                    !== listRequestSequenceRef.current
                ) {
                    return
                }

                setCapabilities(runtimeCapabilities)

                const requestedSize = Math.min(
                    DEFAULT_CHAT_PAGE_SIZE,
                    runtimeCapabilities.maxChatPageSize,
                )

                const response = await getChats(
                    0,
                    requestedSize,
                    {
                        signal:
                            controller.signal,
                    },
                )

                if (
                    sequence
                    !== listRequestSequenceRef.current
                ) {
                    return
                }

                setChats(response.content)
                setChatPage(response.page)
                setChatHasNext(response.hasNext)

                const firstChat =
                    response.content[0]

                if (firstChat) {
                    // messagePageSize пересчитается при следующем рендере.
                    // Консервативное значение сейчас допустимо для всех
                    // поддерживаемых конфигураций backend (не более 100).
                    await openChat(firstChat.id)
                } else {
                    setActiveChat(null)
                }
            } catch (error) {
                if (
                    sequence
                    === listRequestSequenceRef.current
                    && !isRequestAborted(error)
                ) {
                    setListError(
                        getApiErrorMessage(
                            error,
                            'Не удалось загрузить чаты.',
                        ),
                    )
                }
            } finally {
                if (
                    sequence
                    === listRequestSequenceRef.current
                ) {
                    setChatsLoading(false)
                }
            }
        }

        void loadInitialChats()

        return () => {
            controller.abort()
            listRequestSequenceRef.current += 1
            openRequestSequenceRef.current += 1
        }
    }, [
        reloadToken,
        openChat,
        listRequestSequenceRef,
        listControllerRef,
        openRequestSequenceRef,
        setChatsLoading,
        setListError,
        setCapabilities,
        setChats,
        setChatPage,
        setChatHasNext,
        setActiveChat,
    ])

    return { openChat, restoreAnswerPassports }
}
