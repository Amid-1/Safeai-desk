// frontend/src/pages/ChatPage.tsx
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
} from '../api/chatApi'
import type {
    AnswerPassport,
    Chat,
    ChatCapabilities,
    ChatDetails,
    ChatMessage,
    ChatTurnStatus,
    SendMessageResponse,
    KnowledgeMode,
} from '../api/chatApi'
import {
    getKnowledgeBases,
} from '../api/knowledgeApi'
import type {
    KnowledgeBase,
} from '../api/knowledgeApi'
import {
    ApiError,
    getApiErrorMessage,
} from '../api/http'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import PageErrorBoundary
    from '../components/PageErrorBoundary'
import ConfirmDialog
    from '../components/ConfirmDialog'
import Modal
    from '../components/Modal'
import {
    buildDisplayMessages,
    createPendingTurn,
    formatPricingValue,
    formatUsageValue,
    getAiResponseLabel,
    getModelDisplayName,
    getVisibleMessageContent,
    hasMeaningfulContent,
    isMockModel,
    isProcessingPendingStatus,
    isSafeToPrepareNewRequest,
    mergeChatDetails,
    mergeChats,
    mergeMessages,
    moveChatToTop,
    normalizeMessageContent,
} from './chatPage.helpers'
import type {
    PendingTurn,
} from './chatPage.helpers'
import {
    createSecureUuid,
} from '../utils/secureUuid'
import {
    formatDateTime,
} from '../utils/format'

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

type ChatTrustItem = {
    id: 'TENANT' | 'AUDIT' | 'PASSPORT'
    icon: string
    label: string
    title: string
    description: string
    checks: readonly string[]
}

const CHAT_TRUST_ITEMS: readonly ChatTrustItem[] = [
    {
        id: 'TENANT',
        icon: '◫',
        label: 'Изоляция данных',
        title: 'Данные вашей организации изолированы',
        description:
            'SafeAI отделяет данные организаций и проверяет права пользователя до обращения к чату или базе знаний.',
        checks: [
            'Чаты и документы доступны только внутри вашей организации.',
            'Базы знаний дополнительно защищены ролями и списками доступа.',
            'Поиск выполняется только по разрешённым документам и версиям.',
        ],
    },
    {
        id: 'AUDIT',
        icon: '✓',
        label: 'Журнал действий',
        title: 'Действия сохраняются для аудита',
        description:
            'SafeAI фиксирует доказательную цепочку AI-операции, чтобы администратор мог восстановить её ход.',
        checks: [
            'Сохраняются пользователь, организация, чат и время запроса.',
            'Фиксируются выбранная модель, токены, стоимость и качество расчёта.',
            'Для ответов по знаниям сохраняются поиск, документы, версии и фрагменты.',
        ],
    },
    {
        id: 'PASSPORT',
        icon: 'i',
        label: 'Паспорт ответа',
        title: 'Паспорт объясняет происхождение ответа',
        description:
            'Для ответа с корпоративными знаниями SafeAI показывает, на каких источниках он основан и прошли ли ссылки проверку.',
        checks: [
            'Указаны база знаний, документ, версия, страница и фрагмент.',
            'Видны режим ответа и достаточность найденных доказательств.',
            'Паспорт открывается под ответом, когда использовались корпоративные знания.',
        ],
    },
]

type ScrollIntent =
    | { type: 'BOTTOM' }
    | {
        type: 'PRESERVE_TOP'
        scrollHeight: number
        scrollTop: number
    }
    | null

function ChatPage() {
    return (
        <PageErrorBoundary>
            <ChatPageContent />
        </PageErrorBoundary>
    )
}

function ChatPageContent() {
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
            const chat = await createChat(
                'Новый чат',
            )

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

    return (
        <div className="page chat-page">
            <header className="chat-hero">
                <div>
                    <span className="chat-hero__eyebrow">
                        Защищённое рабочее пространство
                    </span>
                    <h1>Корпоративный AI</h1>
                    <p>
                        Общайтесь с AI, подключайте разрешённые базы знаний
                        и проверяйте источники каждого ответа.
                    </p>
                </div>
                <div className="chat-hero__trust" aria-label="Как SafeAI защищает работу с AI">
                    {CHAT_TRUST_ITEMS.map((item) => (
                        <button
                            key={item.id}
                            type="button"
                            className="chat-trust-button"
                            onClick={() => setSelectedTrustItem(item)}
                            aria-label={`${item.label}: открыть объяснение`}
                        >
                            <span aria-hidden="true">{item.icon}</span>
                            {item.label}
                        </button>
                    ))}
                </div>
            </header>

            {listError && (
                <ErrorState
                    title="Ошибка списка чатов"
                    message={listError}
                    action={
                        <button
                            type="button"
                            onClick={() =>
                                setReloadToken(
                                    (value) => value + 1,
                                )
                            }
                        >
                            Повторить
                        </button>
                    }
                />
            )}

            <div className="chat-layout">
                <aside className="card sidebar chat-sidebar">
                    <button
                        type="button"
                        onClick={() =>
                            void handleCreateChat()
                        }
                        disabled={chatCreating}
                    >
                        {chatCreating
                            ? 'Создание...'
                            : 'Создать чат'}
                    </button>

                    <div className="chat-sidebar__heading">
                        <div>
                            <span className="chat-sidebar__eyebrow">История</span>
                            <h2>Ваши чаты</h2>
                        </div>
                        <span className="chat-count">{chats.length}</span>
                    </div>

                    <div className="chat-list">
                        {chatsLoading && (
                            <p className="muted">
                                Загрузка чатов...
                            </p>
                        )}

                        {!chatsLoading
                            && chats.length === 0
                            && (
                                <p>Чатов пока нет.</p>
                            )}

                        {chats.map((chat) => {
                            const pending =
                                pendingTurns[chat.id]

                            return (
                                <div
                                    key={chat.id}
                                    className={
                                        activeChat?.id === chat.id
                                            ? 'chat-list-row active'
                                            : 'chat-list-row'
                                    }
                                >
                                    <button
                                        type="button"
                                        className="chat-item"
                                        disabled={openingChatId === chat.id}
                                        onClick={() => void openChat(chat.id)}
                                        title={`Открыть чат «${chat.title}»`}
                                    >
                                        <span className="chat-item__icon" aria-hidden="true">✦</span>
                                        <span className="chat-item__body">
                                            <strong>
                                                {openingChatId === chat.id
                                                    ? 'Открытие...'
                                                    : chat.title}
                                            </strong>
                                            <small>{formatDateTime(chat.updatedAt)}</small>
                                            {pending && (
                                                <span
                                                    className="chat-item__pending"
                                                    aria-label={getPendingLabel(pending, clock)}
                                                >
                                                    {getPendingShortLabel(pending)}
                                                </span>
                                            )}
                                        </span>
                                    </button>
                                    <button
                                        type="button"
                                        className="chat-archive-button"
                                        aria-label={`Убрать чат «${chat.title}» из списка`}
                                        title={pending
                                            ? 'Дождитесь завершения запроса'
                                            : 'Убрать из списка'}
                                        disabled={Boolean(pending) || archivingChatId === chat.id}
                                        onClick={() => setChatToArchive(chat)}
                                    >
                                        ×
                                    </button>
                                </div>
                            )
                        })}

                        {chatHasNext && (
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={moreChatsLoading}
                                onClick={() =>
                                    void loadMoreChats()
                                }
                            >
                                {moreChatsLoading
                                    ? 'Загрузка...'
                                    : 'Показать ещё чаты'}
                            </button>
                        )}
                    </div>
                </aside>

                <section className="card chat-panel">
                    {chatsLoading && (
                        <LoadingState
                            message="Загрузка чата..."
                        />
                    )}

                    {!chatsLoading
                        && !activeChat
                        && (
                            <EmptyState
                                message={
                                    'Создайте чат, чтобы начать общение.'
                                }
                            />
                        )}

                    {activeChat && (
                        <>
                            <div className="chat-panel__header">
                                <div>
                                    <span className="chat-panel__eyebrow">Текущий чат</span>
                                    <h2>{activeChat.title}</h2>
                                </div>
                                <button
                                    type="button"
                                    className="chat-panel__status"
                                    onClick={() => setSelectedTrustItem(
                                        CHAT_TRUST_ITEMS[0],
                                    )}
                                >
                                    <span aria-hidden="true">●</span>
                                    Защищённый режим
                                </button>
                            </div>

                            {chatError && (
                                <div
                                    className="error"
                                    role="alert"
                                    aria-live="assertive"
                                >
                                    {chatError}
                                </div>
                            )}

                            {sendError && (
                                <div
                                    className="error"
                                    role="alert"
                                    aria-live="assertive"
                                >
                                    {sendError}
                                </div>
                            )}

                            {activePendingTurn && (
                                <PendingTurnState
                                    pending={activePendingTurn}
                                    now={clock}
                                    retryAfterSeconds={
                                        retryAfterSeconds
                                    }
                                    onRetry={() =>
                                        void retryPendingTurn()
                                    }
                                    onCheck={() =>
                                        void reconcilePendingTurn(
                                            activePendingTurn,
                                        )
                                    }
                                    onPrepareNew={
                                        prepareNewRequestFromPending
                                    }
                                    onCopy={() =>
                                        void copyPendingText()
                                    }
                                    onDismissUnsafe={
                                        dismissUnsafePending
                                    }
                                />
                            )}

                            {historyHasNext && (
                                <button
                                    type="button"
                                    className="secondary-button"
                                    disabled={historyLoading}
                                    onClick={() =>
                                        void loadEarlierHistory()
                                    }
                                >
                                    {historyLoading
                                        ? 'Загрузка истории...'
                                        : 'Загрузить более ранние сообщения'}
                                </button>
                            )}

                            <div
                                ref={messagesContainerRef}
                                className="messages"
                                aria-busy={activeChatBusy}
                            >
                                {displayMessages.length
                                    === 0
                                    && !activeChatBusy
                                    && (
                                        <p>
                                            Сообщений пока нет.
                                        </p>
                                    )}

                                {displayMessages.map(
                                    (item) => (
                                        <MessageView
                                            key={item.id}
                                            message={item}
                                            answerPassport={
                                                answerPassports[item.id]
                                            }
                                        />
                                    ),
                                )}

                                {activePendingTurn
                                    && (
                                        activePendingTurn.status
                                            === 'SENDING'
                                        || activePendingTurn.status
                                            === 'PROCESSING'
                                    )
                                    && (
                                        <div
                                            className="message assistant pending"
                                            role="status"
                                            aria-live="polite"
                                        >
                                            <strong>
                                                ASSISTANT
                                            </strong>
                                            <p>
                                                {activePendingTurn.status
                                                    === 'SENDING'
                                                    ? 'Запрос отправляется...'
                                                    : 'Формируется ответ...'}
                                            </p>
                                        </div>
                                    )}

                                <div ref={messagesEndRef} />
                            </div>

                            <div className="message-form">
                                <div className="message-form__controls">
                                    <label htmlFor="knowledge-mode">
                                        Режим ответа
                                    </label>
                                    <select
                                        id="knowledge-mode"
                                        value={knowledgeMode}
                                        disabled={activeChatBusy || activeChatHasPending}
                                        onChange={(event) => {
                                            const mode = event.target.value as KnowledgeMode
                                            setKnowledgeMode(mode)
                                            if (mode === 'GENERAL') {
                                                setKnowledgeBaseId('')
                                            }
                                        }}
                                    >
                                        <option value="GENERAL">Обычный AI</option>
                                        <option value="KNOWLEDGE_ASSISTED">AI + корпоративные знания</option>
                                        <option value="KNOWLEDGE_ONLY">Только корпоративные знания</option>
                                    </select>

                                    {knowledgeMode !== 'GENERAL' && (
                                        <>
                                            <label htmlFor="knowledge-base">
                                                База знаний
                                            </label>
                                            <select
                                                id="knowledge-base"
                                                value={knowledgeBaseId}
                                                disabled={activeChatBusy || activeChatHasPending}
                                                onChange={(event) =>
                                                    setKnowledgeBaseId(event.target.value)
                                                }
                                            >
                                                <option value="">Выберите базу знаний</option>
                                                {knowledgeBases.map((base) => (
                                                    <option key={base.id} value={base.id}>
                                                        {base.name}
                                                    </option>
                                                ))}
                                            </select>
                                        </>
                                    )}
                                </div>
                                <div className="message-form__composer">
                                    <label htmlFor="chat-message">
                                        Сообщение
                                    </label>

                                    <textarea
                                        id="chat-message"
                                        rows={3}
                                        value={activeDraft}
                                        onChange={(event) =>
                                            setDraft(
                                                activeChat.id,
                                                event.target.value,
                                            )
                                        }
                                        onKeyDown={
                                            handleTextareaKeyDown
                                        }
                                        placeholder={
                                            'Введите сообщение. Ctrl+Enter — отправить'
                                        }
                                        maxLength={
                                            capabilities.maxMessageChars
                                        }
                                        disabled={
                                            activeChatBusy
                                            || activeChatHasPending
                                        }
                                        aria-describedby="chat-message-counter"
                                    />

                                    <small
                                        id="chat-message-counter"
                                        className="muted"
                                    >
                                        {activeDraft.length}
                                        {' / '}
                                        {capabilities.maxMessageChars}
                                    </small>
                                </div>

                                <button
                                    type="button"
                                    className="message-send-button"
                                    onClick={() =>
                                        void handleSendMessage()
                                    }
                                    disabled={
                                        activeChatHasPending
                                        || !hasMeaningfulContent(
                                            activeDraft,
                                        )
                                        || activeDraft.length
                                            > capabilities.maxMessageChars
                                        || (
                                            knowledgeMode !== 'GENERAL'
                                            && !knowledgeBaseId
                                        )
                                    }
                                >
                                    {activeChatBusy
                                        ? 'Ожидание...'
                                        : 'Отправить'}
                                </button>
                            </div>

                            <div
                                className="sr-only"
                                role="status"
                                aria-live="polite"
                            >
                                {activePendingTurn
                                    ? getPendingLabel(
                                        activePendingTurn,
                                        clock,
                                    )
                                    : ''}
                            </div>
                        </>
                    )}
                </section>
            </div>

            {chatToArchive && (
                <ConfirmDialog
                    title="Убрать чат из списка?"
                    message={
                        `Чат «${chatToArchive.title}» исчезнет из рабочего списка. `
                        + 'История, стоимость и доказательная цепочка сохранятся для аудита.'
                    }
                    confirmText="Убрать чат"
                    danger
                    loading={archivingChatId === chatToArchive.id}
                    onCancel={() => setChatToArchive(null)}
                    onConfirm={() => handleArchiveChat(chatToArchive)}
                />
            )}

            {selectedTrustItem && (
                <Modal
                    title={selectedTrustItem.title}
                    size="sm"
                    descriptionId="chat-trust-description"
                    onClose={() => setSelectedTrustItem(null)}
                >
                    <div className="chat-trust-dialog">
                        <div className="chat-trust-dialog__icon" aria-hidden="true">
                            {selectedTrustItem.icon}
                        </div>
                        <p id="chat-trust-description">
                            {selectedTrustItem.description}
                        </p>
                        <ul>
                            {selectedTrustItem.checks.map((check) => (
                                <li key={check}>{check}</li>
                            ))}
                        </ul>
                        <button
                            type="button"
                            onClick={() => setSelectedTrustItem(null)}
                        >
                            Понятно
                        </button>
                    </div>
                </Modal>
            )}
        </div>
    )
}

type MessageViewProps = {
    message: ReturnType<
        typeof buildDisplayMessages
    >[number]
    answerPassport?: AnswerPassport
}

function MessageView({
    message,
    answerPassport,
}: MessageViewProps) {
    const aiResponseLabel =
        getAiResponseLabel(message)
    const mockModel =
        isMockModel(message.model)

    return (
        <article
            className={
                `message ${
                    message.role.toLowerCase()
                } ${
                    message.status.toLowerCase()
                }`
            }
        >
            <div className="message__header">
                <span className="message__avatar" aria-hidden="true">
                    {message.role === 'ASSISTANT'
                        ? 'AI'
                        : message.role === 'USER'
                            ? 'ВЫ'
                            : 'SYS'}
                </span>
                <strong>
                    {message.role === 'ASSISTANT'
                        ? 'SafeAI'
                        : message.role === 'USER'
                            ? 'Вы'
                            : 'Система'}
                </strong>
                {message.role === 'ASSISTANT' && mockModel && (
                    <span className="message__demo-badge">
                        Демо-режим
                    </span>
                )}
            </div>

            {message.status === 'FAILED' && (
                <span className="status-badge status-disabled">
                    Ошибка
                </span>
            )}

            {message.uiStatus && (
                <span className="status-badge status-disabled">
                    {getPendingShortLabel({
                        status: message.uiStatus,
                    })}
                </span>
            )}

            {aiResponseLabel && (
                <span className="status-badge">
                    {aiResponseLabel}
                </span>
            )}

            <p>{getVisibleMessageContent(message)}</p>

            {message.role === 'ASSISTANT' && (
                <>
                    <details className="answer-details">
                        <summary>
                            <span className="answer-details__icon" aria-hidden="true">
                                i
                            </span>
                            <span className="answer-details__heading">
                                <strong>Как сформирован ответ</strong>
                                <small>Модель, объём и стоимость запроса</small>
                            </span>
                            <span className="answer-details__action">
                                Подробнее
                            </span>
                        </summary>
                        <dl className="answer-details__grid">
                            <div>
                                <dt>Модель AI</dt>
                                <dd>{getModelDisplayName(message.model)}</dd>
                                {message.model && (
                                    <code>{message.model}</code>
                                )}
                                <small>
                                    {mockModel
                                        ? 'Тестовый провайдер для демонстрации интерфейса. Это не ответ внешней LLM.'
                                        : 'Модель, которая фактически сформировала этот ответ.'}
                                </small>
                            </div>
                            <div>
                                <dt>Объём запроса</dt>
                                <dd>{formatUsageValue(message)}</dd>
                                <small>
                                    Вход — запрос и контекст, выход — текст ответа модели.
                                </small>
                            </div>
                            <div>
                                <dt>Стоимость</dt>
                                <dd>{formatPricingValue(message)}</dd>
                                <small>
                                    Расчёт хранится вместе с операцией и доступен в отчёте использования.
                                </small>
                            </div>
                        </dl>
                    </details>
                    {answerPassport && (
                        <details className="answer-passport">
                            <summary>
                                Паспорт ответа · источников: {answerPassport.citations.length}
                            </summary>
                            <p>
                                Режим: {getKnowledgeModeLabel(answerPassport.knowledgeMode)}
                                {' · '}Ссылки: {answerPassport.citationsValid ? 'проверены' : 'требуют проверки'}
                                {' · '}Доказательств: {answerPassport.evidenceSufficient ? 'достаточно' : 'недостаточно'}
                            </p>
                            <ul>
                                {answerPassport.citations.map((citation) => (
                                    <li key={citation.chunkId}>
                                        <strong>[{citation.label}]</strong>
                                        {' '}{citation.documentName}
                                        {' · v'}{citation.versionNumber}
                                        {citation.pageFrom != null
                                            ? ` · стр. ${citation.pageFrom}`
                                            : ''}
                                        {' · chunk '}{citation.chunkOrdinal}
                                    </li>
                                ))}
                            </ul>
                            <small>
                                Идентификатор поиска: {answerPassport.retrievalRunId}
                            </small>
                        </details>
                    )}
                </>
            )}
        </article>
    )
}

function getKnowledgeModeLabel(mode: KnowledgeMode): string {
    switch (mode) {
        case 'KNOWLEDGE_ASSISTED':
            return 'AI + корпоративные знания'
        case 'KNOWLEDGE_ONLY':
            return 'Только корпоративные знания'
        default:
            return 'Обычный AI'
    }
}

type PendingTurnStateProps = {
    pending: PendingTurn
    now: number
    retryAfterSeconds: number
    onRetry: () => void
    onCheck: () => void
    onPrepareNew: () => void
    onCopy: () => void
    onDismissUnsafe: () => void
}

function PendingTurnState({
    pending,
    now,
    retryAfterSeconds,
    onRetry,
    onCheck,
    onPrepareNew,
    onCopy,
    onDismissUnsafe,
}: PendingTurnStateProps) {
    const canRetrySameId =
        pending.status === 'SEND_UNKNOWN'
        || pending.status === 'RATE_LIMITED'

    const canCheck =
        pending.status === 'PROCESSING'
        || pending.status === 'SEND_UNKNOWN'
        || pending.status === 'AMBIGUOUS'

    const canPrepareNew =
        isSafeToPrepareNewRequest(
            pending.status,
        )
        && pending.status !== 'RATE_LIMITED'

    const unsafeTerminal =
        pending.status === 'AMBIGUOUS'
        || pending.status === 'IDEMPOTENCY_CONFLICT'

    return (
        <div
            className="card"
            role={
                pending.status === 'FAILED'
                || pending.status === 'AMBIGUOUS'
                || pending.status === 'ACCESS_REVOKED'
                || pending.status === 'IDEMPOTENCY_CONFLICT'
                    ? 'alert'
                    : 'status'
            }
            aria-live={
                pending.status === 'FAILED'
                || pending.status === 'AMBIGUOUS'
                    ? 'assertive'
                    : 'polite'
            }
        >
            <strong>
                {getPendingLabel(
                    pending,
                    now,
                )}
            </strong>

            {pending.error && (
                <p>{pending.error}</p>
            )}

            <div className="modal-actions">
                {canRetrySameId && (
                    <button
                        type="button"
                        disabled={retryAfterSeconds > 0}
                        onClick={onRetry}
                    >
                        Повторить с тем же ID
                    </button>
                )}

                {canCheck && (
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={onCheck}
                    >
                        Проверить статус
                    </button>
                )}

                {canPrepareNew && (
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={onPrepareNew}
                    >
                        Вернуть текст как новый запрос
                    </button>
                )}

                {unsafeTerminal && (
                    <>
                        <button
                            type="button"
                            className="secondary-button"
                            onClick={onCopy}
                        >
                            Скопировать текст
                        </button>
                        <button
                            type="button"
                            className="secondary-button"
                            onClick={onDismissUnsafe}
                        >
                            Закрыть без повтора
                        </button>
                    </>
                )}
            </div>
        </div>
    )
}

function getPendingLabel(
    pending: PendingTurn,
    now: number,
): string {
    switch (pending.status) {
        case 'SENDING':
            return 'Сообщение отправляется.'

        case 'PROCESSING':
            return 'Запрос ещё обрабатывается.'

        case 'SEND_UNKNOWN':
            return (
                'Статус отправки неизвестен. '
                + 'Новый clientRequestId не создаётся.'
            )

        case 'FAILED':
            return 'AI-запрос завершился ошибкой.'

        case 'AMBIGUOUS':
            return (
                'Результат AI-вызова неоднозначен. '
                + 'Автоматический повтор запрещён.'
            )

        case 'RATE_LIMITED': {
            const seconds = getRetryAfterSeconds(
                pending,
                now,
            )

            return seconds > 0
                ? `Повтор доступен через ${seconds} сек.`
                : (
                    'Повтор разрешён с тем же '
                    + 'clientRequestId.'
                )
        }

        case 'QUOTA_BLOCKED':
            return 'Квота AI не позволяет выполнить запрос.'

        case 'ACCESS_REVOKED':
            return 'Доступ к чату был отозван.'

        case 'IDEMPOTENCY_CONFLICT':
            return 'Обнаружен конфликт ключа идемпотентности.'
    }
}

function getPendingShortLabel(
    pending: Pick<PendingTurn, 'status'>,
): string {
    switch (pending.status) {
        case 'SENDING':
            return 'отправка'
        case 'PROCESSING':
            return 'обработка'
        case 'SEND_UNKNOWN':
            return 'статус неизвестен'
        case 'FAILED':
            return 'ошибка'
        case 'AMBIGUOUS':
            return 'неоднозначно'
        case 'RATE_LIMITED':
            return 'лимит'
        case 'QUOTA_BLOCKED':
            return 'квота'
        case 'ACCESS_REVOKED':
            return 'доступ отозван'
        case 'IDEMPOTENCY_CONFLICT':
            return 'конфликт ID'
    }
}

function getRetryAfterSeconds(
    pending: PendingTurn,
    now: number,
): number {
    if (!pending.retryAfterUntil) {
        return 0
    }

    return Math.max(
        0,
        Math.ceil(
            (
                pending.retryAfterUntil - now
            ) / 1_000,
        ),
    )
}

function failureMessage(
    turn: ChatTurnStatus,
): string {
    if (turn.failureCode) {
        return `AI-запрос завершился ошибкой (${turn.failureCode}).`
    }

    return 'AI-запрос завершился ошибкой.'
}

function isRequestAborted(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && error.errorCode === 'REQUEST_ABORTED'
}

async function delay(
    milliseconds: number,
    signal: AbortSignal,
): Promise<void> {
    if (signal.aborted) {
        return
    }

    await new Promise<void>((resolve) => {
        const abort = () => {
            window.clearTimeout(timeoutId)
            resolve()
        }

        const timeoutId = window.setTimeout(
            () => {
                signal.removeEventListener(
                    'abort',
                    abort,
                )
                resolve()
            },
            milliseconds,
        )

        signal.addEventListener(
            'abort',
            abort,
            { once: true },
        )
    })
}

export default ChatPage
