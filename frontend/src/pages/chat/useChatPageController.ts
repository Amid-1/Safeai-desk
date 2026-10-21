// ============================================================
// frontend/src/pages/chat/useChatPageController.ts
// ============================================================
import {
    useCallback,
    useEffect,
    useLayoutEffect,
    useMemo,
    useRef,
    useState,
} from 'react'
import type { KeyboardEvent } from 'react'
import {
    archiveChat,
    createChat,
    getAnswerPassport,
    getChatById,
    getChatCapabilities,
    getChatMessages,
    getChats,
    getChatTurnStatus,
    sendMessage,
    updateChatTitle,
} from '../../api/chatApi'
import type {
    AnswerPassport,
    Chat,
    ChatCapabilities,
    ChatDetails,
    ChatMessage,
    ChatTurnStatus,
    SendMessageResponse,
    KnowledgeMode,
} from '../../api/chatApi'
import {
    getKnowledgeBases,
} from '../../api/knowledgeApi'
import type {
    KnowledgeBase,
} from '../../api/knowledgeApi'
import {
    ApiError,
    getApiErrorMessage,
} from '../../api/http'
import {
    buildDisplayMessages,
    createPendingTurn,
    hasMeaningfulContent,
    isProcessingPendingStatus,
    isSafeToPrepareNewRequest,
    mergeChatDetails,
    mergeChats,
    mergeMessages,
    moveChatToTop,
    normalizeMessageContent,
} from '../chatPage.helpers'
import type {
    PendingTurn,
} from '../chatPage.helpers'
import {
    createSecureUuid,
} from '../../utils/secureUuid'

import {
    delay,
    failureMessage,
    getRetryAfterSeconds,
    isRequestAborted,
} from '../chatPageSupport'

import type { ChatTrustItem } from './chatTrustInfo'

const DEFAULT_CHAT_PAGE_SIZE = 50
const DEFAULT_MESSAGE_PAGE_SIZE = 50
const DEFAULT_MESSAGE_MAX_LENGTH = 16_000

const RECONCILIATION_ATTEMPTS = 12
const RECONCILIATION_DELAY_MS = 2_500

const DEFAULT_CAPABILITIES: ChatCapabilities = {
    maxMessageChars: DEFAULT_MESSAGE_MAX_LENGTH,
    maxChatPageSize: 100,
    maxMessagePageSize: 100,
    detailsMessageLimit: 50,
}

type ScrollIntent =
    | { type: 'BOTTOM' }
    | {
        type: 'PRESERVE_TOP'
        scrollHeight: number
        scrollTop: number
    }
    | null

export function useChatPageController() {
    const [capabilities, setCapabilities] =
        useState<ChatCapabilities>(
            DEFAULT_CAPABILITIES,
        )

    const [chats, setChats] =
        useState<Chat[]>([])
    const [activeChat, setActiveChat] =
        useState<ChatDetails | null>(null)
    const [knowledgeBases, setKnowledgeBases] =
        useState<KnowledgeBase[]>([])
    const [knowledgeMode, setKnowledgeMode] =
        useState<KnowledgeMode>('GENERAL')
    const [knowledgeBaseId, setKnowledgeBaseId] =
        useState<string>('')
    const [answerPassports, setAnswerPassports] = useState<
        Record<string, AnswerPassport>
    >({})

    const [drafts, setDrafts] = useState<
        Record<string, string>
    >({})

    const [pendingTurns, setPendingTurns] =
        useState<Record<string, PendingTurn>>(
            {},
        )

    const [listError, setListError] =
        useState('')
    const [chatError, setChatError] =
        useState('')
    const [sendError, setSendError] =
        useState('')

    const [chatsLoading, setChatsLoading] =
        useState(true)
    const [moreChatsLoading, setMoreChatsLoading] =
        useState(false)
    const [chatCreating, setChatCreating] =
        useState(false)
    const [chatToArchive, setChatToArchive] =
        useState<Chat | null>(null)
    const [chatToRename, setChatToRename] =
        useState<Chat | null>(null)
    const [renamingChatId, setRenamingChatId] =
        useState<string | null>(null)
    const [archivingChatId, setArchivingChatId] =
        useState<string | null>(null)
    const [openingChatId, setOpeningChatId] =
        useState<string | null>(null)
    const [historyLoading, setHistoryLoading] =
        useState(false)

    const [chatPage, setChatPage] =
        useState(0)
    const [chatHasNext, setChatHasNext] =
        useState(false)
    const [historyPage, setHistoryPage] =
        useState(1)
    const [historyHasNext, setHistoryHasNext] =
        useState(false)
    const [reloadToken, setReloadToken] =
        useState(0)
    const [clock, setClock] =
        useState(Date.now())
    const [selectedTrustItem, setSelectedTrustItem] =
        useState<ChatTrustItem | null>(null)

    const messagesEndRef =
        useRef<HTMLDivElement | null>(null)
    const messagesContainerRef =
        useRef<HTMLDivElement | null>(null)

    const listRequestSequenceRef =
        useRef(0)
    const openRequestSequenceRef =
        useRef(0)
    const historyRequestSequenceRef =
        useRef(0)

    const listControllerRef =
        useRef<AbortController | null>(null)
    const openControllerRef =
        useRef<AbortController | null>(null)
    const historyControllerRef =
        useRef<AbortController | null>(null)
    const reconciliationControllersRef =
        useRef(
            new Map<string, AbortController>(),
        )

    const createInFlightRef =
        useRef(false)
    const pendingTurnsRef =
        useRef(pendingTurns)
    const activeChatRef =
        useRef(activeChat)
    const scrollIntentRef =
        useRef<ScrollIntent>(null)

    useEffect(() => {
        pendingTurnsRef.current = pendingTurns
    }, [pendingTurns])

    useEffect(() => {
        activeChatRef.current = activeChat
    }, [activeChat])

    useEffect(() => {
        const controller = new AbortController()
        void getKnowledgeBases(0, 100, {
            signal: controller.signal,
        }).then((response) => {
            setKnowledgeBases(
                response.content.filter((base) => base.enabled),
            )
        }).catch(() => {
            // Chat remains available in GENERAL mode if Knowledge is offline.
            setKnowledgeBases([])
        })
        return () => controller.abort()
    }, [])

    const activePendingTurn = activeChat
        ? pendingTurns[activeChat.id]
        : undefined

    const activeDraft = activeChat
        ? drafts[activeChat.id] ?? ''
        : ''

    const displayMessages = useMemo(
        () => activeChat
            ? buildDisplayMessages(
                activeChat.messages,
                activePendingTurn,
            )
            : [],
        [activeChat, activePendingTurn],
    )

    // Provider information describes the latest known assistant response,
    // not the security level of the chat and not an unverified future reply.
    const lastAssistantModel = useMemo(() => {
        for (let index = displayMessages.length - 1; index >= 0; index--) {
            const message = displayMessages[index]
            if (message.role === 'ASSISTANT') {
                return message.model
            }
        }
        return null
    }, [displayMessages])

    const activeChatHasPending =
        Boolean(activePendingTurn)

    const activeChatBusy = Boolean(
        activePendingTurn
        && isProcessingPendingStatus(
            activePendingTurn.status,
        ),
    )

    const messagePageSize = Math.min(
        DEFAULT_MESSAGE_PAGE_SIZE,
        capabilities.maxMessagePageSize,
    )

    const chatPageSize = Math.min(
        DEFAULT_CHAT_PAGE_SIZE,
        capabilities.maxChatPageSize,
    )

    /*
     * SendMessageResponse carries a passport for a live turn, but a browser
     * reload only has persisted messages.  Reconcile completed turns by their
     * immutable clientRequestId and restore their immutable evidence record.
     * A 404 is normal for GENERAL mode, so an individual failure must not make
     * chat history unavailable.
     */
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

            const succeededTurns = turns.flatMap((result) =>
                result.status === 'fulfilled'
                && result.value.state === 'SUCCEEDED'
                && result.value.assistantMessage
                    ? [result.value]
                    : [],
            )

            const passports = await Promise.allSettled(
                succeededTurns.map(async (turn) => ({
                    assistantMessageId: turn.assistantMessage!.id,
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
        [],
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
                    // /messages is the source of truth for pagination.
                    // mergeMessages restores chronological display order.
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
        [messagePageSize, restoreAnswerPassports],
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
                // Capabilities were introduced together with this frontend.
                // A 404 during a rolling deployment is safe: bounded defaults
                // keep the older backend usable until all nodes are upgraded.
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
                    // messagePageSize will be updated on the following render;
                    // using the conservative default here is still accepted by
                    // every supported backend configuration (<=100).
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
    }, [reloadToken, openChat])

    useEffect(() => {
        const retryAfterUntil =
            activePendingTurn?.retryAfterUntil

        if (!retryAfterUntil) {
            return
        }

        const timerId = window.setInterval(
            () => {
                setClock(Date.now())
            },
            1_000,
        )

        return () => {
            window.clearInterval(timerId)
        }
    }, [activePendingTurn?.retryAfterUntil])

    useLayoutEffect(() => {
        const intent = scrollIntentRef.current

        if (!intent) {
            return
        }

        if (intent.type === 'BOTTOM') {
            messagesEndRef.current?.scrollIntoView({
                block: 'end',
            })
        } else {
            const container =
                messagesContainerRef.current

            if (container) {
                container.scrollTop =
                    intent.scrollTop
                    + container.scrollHeight
                    - intent.scrollHeight
            }
        }

        scrollIntentRef.current = null
    }, [
        activeChat?.id,
        activeChat?.messages.length,
        activePendingTurn?.status,
    ])

    useEffect(() => {
        const controllers =
            reconciliationControllersRef.current

        return () => {
            listControllerRef.current?.abort()
            openControllerRef.current?.abort()
            historyControllerRef.current?.abort()

            controllers.forEach(
                (controller) => {
                    controller.abort()
                },
            )

            controllers.clear()
        }
    }, [])

    async function loadMoreChats() {
        if (
            moreChatsLoading
            || !chatHasNext
        ) {
            return
        }

        const nextPage = chatPage + 1

        setMoreChatsLoading(true)
        setListError('')

        try {
            const response = await getChats(
                nextPage,
                chatPageSize,
            )

            setChats((current) =>
                mergeChats(
                    current,
                    response.content,
                ),
            )
            setChatPage(response.page)
            setChatHasNext(response.hasNext)
        } catch (error) {
            setListError(
                getApiErrorMessage(
                    error,
                    'Не удалось загрузить дополнительные чаты.',
                ),
            )
        } finally {
            setMoreChatsLoading(false)
        }
    }

    async function renameChatById(chatId: string, title: string): Promise<void> {
        const updated = await updateChatTitle(chatId, title)
        setChats((items) => items.map((item) =>
            item.id === chatId ? updated : item,
        ))
        setActiveChat((current) => current?.id === chatId
            ? { ...current, ...updated }
            : current,
        )
    }

    async function handleRenameChat(title: string): Promise<void> {
        const chatId = activeChatRef.current?.id
        if (!chatId) {
            throw new Error('Чат не выбран')
        }
        await renameChatById(chatId, title)
    }

    async function handleRenameFromHistory(title: string): Promise<void> {
        const chatId = chatToRename?.id
        if (!chatId || renamingChatId) {
            throw new Error('Переименование чата недоступно')
        }
        setRenamingChatId(chatId)
        try {
            await renameChatById(chatId, title)
        } finally {
            setRenamingChatId(null)
        }
    }

    async function refreshChatTitle(chatId: string): Promise<void> {
        // The backend owns title provenance. Never derive or overwrite it in UI.
        try {
            const serverChat = await getChatById(chatId)
            setChats((items) => items.map((item) =>
                item.id === chatId
                    ? { ...item, title: serverChat.title, updatedAt: serverChat.updatedAt }
                    : item,
            ))
            setActiveChat((current) => current?.id === chatId
                ? { ...current, title: serverChat.title, updatedAt: serverChat.updatedAt }
                : current,
            )
        } catch {
            // Message processing has already committed; the next normal load
            // will synchronize the title. Never resend the message to fix UI.
        }
    }

    async function handleCreateChat() {
        if (createInFlightRef.current) {
            return
        }

        // Не плодим пустые "Новый чат" при повторных кликах после
        // сетевых/контрактных ошибок. Пользователь уже находится в пустом чате.
        if (
            activeChat
            && activeChat.title === 'Новый чат'
            && activeChat.messages.length === 0
            && !pendingTurns[activeChat.id]
        ) {
            return
        }

        createInFlightRef.current = true
        setChatCreating(true)
        setListError('')

        openRequestSequenceRef.current += 1
        historyRequestSequenceRef.current += 1

        openControllerRef.current?.abort()
        historyControllerRef.current?.abort()

        setOpeningChatId(null)
        setHistoryLoading(false)

        try {
            const chat = await createChat()

            setChats((current) => [
                chat,
                ...current.filter(
                    (item) => item.id !== chat.id,
                ),
            ])

            scrollIntentRef.current = {
                type: 'BOTTOM',
            }

            setActiveChat({
                ...chat,
                messages: [],
            })
            setHistoryPage(1)
            setHistoryHasNext(false)
        } catch (error) {
            setListError(
                getApiErrorMessage(
                    error,
                    'Не удалось создать чат.',
                ),
            )
        } finally {
            createInFlightRef.current = false
            setChatCreating(false)
        }
    }

    async function handleArchiveChat(chat: Chat) {
        if (archivingChatId || pendingTurnsRef.current[chat.id]) {
            return
        }

        setArchivingChatId(chat.id)
        setListError('')

        try {
            await archiveChat(chat.id)

            const remainingChats = chats.filter(
                (item) => item.id !== chat.id,
            )

            setChats(remainingChats)
            setDrafts((current) => {
                const next = { ...current }
                delete next[chat.id]
                return next
            })
            setChatToArchive(null)

            if (activeChatRef.current?.id === chat.id) {
                setActiveChat(null)
                const nextChat = remainingChats[0]
                if (nextChat) {
                    await openChat(nextChat.id)
                }
            }
        } catch (error) {
            setListError(
                getApiErrorMessage(
                    error,
                    'Не удалось убрать чат из списка.',
                ),
            )
            throw error
        } finally {
            setArchivingChatId(null)
        }
    }

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

    async function executePendingTurn(
        pending: PendingTurn,
    ) {
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
        }
    }

    async function handleSendFailure(
        pending: PendingTurn,
        error: unknown,
    ) {
        if (error instanceof ApiError) {
            switch (error.errorCode) {
                case 'AI_QUOTA_EXCEEDED':
                case 'CHAT_QUOTA_EXCEEDED':
                case 'QUOTA_EXCEEDED':
                    putPendingTurn({
                        ...pending,
                        status: 'QUOTA_BLOCKED',
                        error: error.message
                            || 'Квота AI исчерпана.',
                        retryAfterUntil: null,
                    })
                    setSendError(
                        error.message
                        || 'Квота AI исчерпана.',
                    )
                    return

                case 'AI_OUTCOME_AMBIGUOUS':
                case 'CHAT_PROCESSOR_FENCED':
                    putPendingTurn({
                        ...pending,
                        status: 'AMBIGUOUS',
                        error:
                            'Результат AI-вызова неоднозначен. '
                            + 'Повтор с новым clientRequestId запрещён.',
                        retryAfterUntil: null,
                    })
                    setSendError(
                        'Проверьте состояние исходной операции. '
                        + 'Новый запрос автоматически не создаётся.',
                    )
                    return

                case 'CHAT_ACCESS_REVOKED_DURING_PROCESSING':
                    putPendingTurn({
                        ...pending,
                        status: 'ACCESS_REVOKED',
                        error:
                            'Доступ был отозван во время обработки.',
                        retryAfterUntil: null,
                    })
                    setSendError(
                        'Доступ к чату или организации отозван.',
                    )
                    return

                case 'IDEMPOTENCY_KEY_REUSED':
                    putPendingTurn({
                        ...pending,
                        status: 'IDEMPOTENCY_CONFLICT',
                        error:
                            'Этот clientRequestId уже связан с другим содержимым. '
                            + 'Повторять запрос автоматически нельзя.',
                        retryAfterUntil: null,
                    })
                    setSendError(
                        'Обнаружен конфликт идемпотентности.',
                    )
                    return

                case 'CHAT_TURN_IN_PROGRESS':
                case 'CHAT_BUSY':
                    putPendingTurn({
                        ...pending,
                        status: 'PROCESSING',
                        error: null,
                        retryAfterUntil: null,
                    })
                    await reconcilePendingTurn(pending)
                    return

                default:
                    break
            }

            if (error.status === 429) {
                const retryAfterSeconds =
                    error.retryAfterSeconds ?? 1

                putPendingTurn({
                    ...pending,
                    status: 'RATE_LIMITED',
                    error:
                        'Лимит запросов временно исчерпан.',
                    retryAfterUntil:
                        Date.now()
                        + retryAfterSeconds * 1_000,
                })
                setClock(Date.now())
                setSendError(
                    'После Retry-After будет выполнен только повтор '
                    + 'с тем же clientRequestId.',
                )
                return
            }
        }

        putPendingTurn({
            ...pending,
            status: 'SEND_UNKNOWN',
            error: getApiErrorMessage(
                error,
                'Статус отправки неизвестен.',
            ),
            retryAfterUntil: null,
        })

        await reconcilePendingTurn(pending)
    }

    async function reconcilePendingTurn(
        pending: PendingTurn,
    ) {
        reconciliationControllersRef.current
            .get(pending.chatId)
            ?.abort()

        const controller =
            new AbortController()

        reconciliationControllersRef.current.set(
            pending.chatId,
            controller,
        )

        try {
            for (
                let attempt = 0;
                attempt < RECONCILIATION_ATTEMPTS;
                attempt += 1
            ) {
                if (controller.signal.aborted) {
                    return
                }

                try {
                    const turn = await getChatTurnStatus(
                        pending.chatId,
                        pending.clientRequestId,
                        {
                            signal:
                                controller.signal,
                        },
                    )

                    applyTurnMessages(
                        pending.chatId,
                        turn,
                    )

                    if (
                        turn.state === 'NEW'
                        || turn.state === 'PROCESSING'
                    ) {
                        putPendingTurn({
                            ...pending,
                            status: 'PROCESSING',
                            error: null,
                            retryAfterUntil: null,
                        })

                        await delay(
                            RECONCILIATION_DELAY_MS,
                            controller.signal,
                        )
                        continue
                    }

                    if (turn.state === 'SUCCEEDED') {
                        if (
                            turn.userMessage
                            && turn.assistantMessage
                        ) {
                            applyTurnSuccess(
                                pending,
                                turn,
                            )
                            void refreshChatTitle(pending.chatId)
                            return
                        }

                        const details = await getChatById(
                            pending.chatId,
                            {
                                signal:
                                    controller.signal,
                            },
                        )

                        applyReloadedSuccess(
                            pending,
                            details,
                        )
                        return
                    }

                    if (turn.state === 'FAILED') {
                        const message =
                            failureMessage(turn)

                        putPendingTurn({
                            ...pending,
                            status: 'FAILED',
                            error: message,
                            retryAfterUntil: null,
                        })
                        setSendError(message)
                        return
                    }

                    putPendingTurn({
                        ...pending,
                        status: 'AMBIGUOUS',
                        error:
                            'Результат операции неоднозначен. '
                            + 'Нельзя создавать автоматический повтор с новым ID.',
                        retryAfterUntil: null,
                    })
                    setSendError(
                        'Проверьте исходную операцию или закройте предупреждение '
                        + 'без её повторной отправки.',
                    )
                    return
                } catch (error) {
                    if (isRequestAborted(error)) {
                        return
                    }

                    if (
                        error instanceof ApiError
                        && error.status === 404
                    ) {
                        putPendingTurn({
                            ...pending,
                            status: 'SEND_UNKNOWN',
                            error:
                                'Backend пока не видит turn по clientRequestId. '
                                + 'Безопасен только повтор исходного запроса '
                                + 'с тем же clientRequestId.',
                            retryAfterUntil: null,
                        })
                        return
                    }

                    if (
                        attempt
                        === RECONCILIATION_ATTEMPTS - 1
                    ) {
                        putPendingTurn({
                            ...pending,
                            status: 'SEND_UNKNOWN',
                            error: getApiErrorMessage(
                                error,
                                'Не удалось определить состояние turn.',
                            ),
                            retryAfterUntil: null,
                        })
                        return
                    }

                    await delay(
                        RECONCILIATION_DELAY_MS,
                        controller.signal,
                    )
                }
            }
        } finally {
            if (
                reconciliationControllersRef.current
                    .get(pending.chatId)
                === controller
            ) {
                reconciliationControllersRef.current.delete(
                    pending.chatId,
                )
            }
        }
    }

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

        setPendingTurns((current) => {
            const next = { ...current }
            delete next[chatId]
            return next
        })
    }

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
        setPendingTurns((current) => ({
            ...current,
            [pending.chatId]: pending,
        }))
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

    const retryAfterSeconds =
        activePendingTurn
            ? getRetryAfterSeconds(
                activePendingTurn,
                clock,
            )
            : 0

    return {
        capabilities,
        chats,
        activeChat,
        knowledgeBases,
        knowledgeMode,
        knowledgeBaseId,
        answerPassports,
        pendingTurns,
        listError,
        chatError,
        sendError,
        chatsLoading,
        moreChatsLoading,
        chatCreating,
        chatToArchive,
        chatToRename,
        renamingChatId,
        archivingChatId,
        openingChatId,
        historyLoading,
        chatHasNext,
        historyHasNext,
        clock,
        selectedTrustItem,
        messagesEndRef,
        messagesContainerRef,
        activePendingTurn,
        activeDraft,
        displayMessages,
        lastAssistantModel,
        activeChatHasPending,
        activeChatBusy,
        retryAfterSeconds,
        setSelectedTrustItem,
        setReloadToken,
        setChatToArchive,
        setChatToRename,
        setKnowledgeMode,
        setKnowledgeBaseId,
        setDraft,
        openChat,
        loadMoreChats,
        handleRenameChat,
        handleRenameFromHistory,
        handleCreateChat,
        handleArchiveChat,
        handleSendMessage,
        retryPendingTurn,
        reconcilePendingTurn,
        loadEarlierHistory,
        prepareNewRequestFromPending,
        dismissUnsafePending,
        copyPendingText,
        handleTextareaKeyDown,
    }
}

export type ChatPageController = ReturnType<typeof useChatPageController>
