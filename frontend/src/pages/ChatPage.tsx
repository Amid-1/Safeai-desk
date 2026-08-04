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
    createChat,
    getChatById,
    getChatMessages,
    getChats,
    getChatTurnStatus,
    sendMessage,
} from '../api/chatApi'
import type {
    Chat,
    ChatDetails,
} from '../api/chatApi'
import {
    ApiError,
    getApiErrorMessage,
} from '../api/http'
import {
    normalizePageResponse,
} from '../utils/page'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import PageErrorBoundary
    from '../components/PageErrorBoundary'
import {
    buildDisplayMessages,
    createPendingTurn,
    formatPricing,
    formatUsage,
    getAiResponseLabel,
    hasMeaningfulContent,
    mergeChatDetails,
    mergeChats,
    mergeMessages,
    moveChatToTop,
    normalizeMessageContent,
} from './chatPage.helpers'
import type {
    PendingTurn,
    PendingTurnStatus,
} from './chatPage.helpers'

const CHAT_PAGE_SIZE = 50
const MESSAGE_PAGE_SIZE = 50
const MESSAGE_MAX_LENGTH = 10_000

const RECONCILIATION_ATTEMPTS = 12
const RECONCILIATION_DELAY_MS = 2_500

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
    const [chats, setChats] =
        useState<Chat[]>([])
    const [activeChat, setActiveChat] =
        useState<ChatDetails | null>(null)

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
    const [
        moreChatsLoading,
        setMoreChatsLoading,
    ] = useState(false)
    const [chatCreating, setChatCreating] =
        useState(false)
    const [
        openingChatId,
        setOpeningChatId,
    ] = useState<string | null>(null)
    const [
        historyLoading,
        setHistoryLoading,
    ] = useState(false)

    const [chatPage, setChatPage] =
        useState(0)
    const [
        chatTotalPages,
        setChatTotalPages,
    ] = useState(0)
    const [
        historyPage,
        setHistoryPage,
    ] = useState(1)
    const [
        historyTotalPages,
        setHistoryTotalPages,
    ] = useState<number | null>(null)
    const [reloadToken, setReloadToken] =
        useState(0)
    const [clock, setClock] =
        useState(Date.now())

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
        pendingTurnsRef.current =
            pendingTurns
    }, [pendingTurns])

    useEffect(() => {
        activeChatRef.current = activeChat
    }, [activeChat])

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
        [
            activeChat,
            activePendingTurn,
        ],
    )

    const activeChatHasPending =
        Boolean(activePendingTurn)

    const activeChatBusy = Boolean(
        activePendingTurn
        && !isTerminalPendingStatus(
            activePendingTurn.status,
        ),
    )

    const canLoadEarlier =
        activeChat !== null
        && (
            historyTotalPages === null
            || historyPage < historyTotalPages
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
            setHistoryTotalPages(null)
            setHistoryLoading(false)

            historyRequestSequenceRef.current += 1

            try {
                const details =
                    await getChatById(
                        chatId,
                        {
                            signal:
                                controller.signal,
                        },
                    )

                if (
                    sequence
                    !== openRequestSequenceRef.current
                ) {
                    return
                }

                scrollIntentRef.current = {
                    type: 'BOTTOM',
                }

                setActiveChat(details)
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
        [],
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
                const response = await getChats(
                    0,
                    CHAT_PAGE_SIZE,
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

                const normalized =
                    normalizePageResponse(response)

                setChats(normalized.content)
                setChatPage(0)
                setChatTotalPages(
                    normalized.totalPages,
                )

                const firstChat =
                    normalized.content[0]

                if (firstChat) {
                    await openChat(
                        firstChat.id,
                    )
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
    ])

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
    }, [
        activePendingTurn?.retryAfterUntil,
    ])

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
            || chatPage + 1 >= chatTotalPages
        ) {
            return
        }

        const nextPage = chatPage + 1

        setMoreChatsLoading(true)
        setListError('')

        try {
            const response = await getChats(
                nextPage,
                CHAT_PAGE_SIZE,
            )

            const normalized =
                normalizePageResponse(response)

            setChats((current) =>
                mergeChats(
                    current,
                    normalized.content,
                ),
            )
            setChatPage(nextPage)
            setChatTotalPages(
                normalized.totalPages,
            )
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
            setHistoryTotalPages(1)
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

    async function handleSendMessage() {
        const chat = activeChatRef.current

        if (!chat) {
            return
        }

        const rawDraft =
            drafts[chat.id] ?? ''
        const content =
            normalizeMessageContent(rawDraft)

        if (
            !hasMeaningfulContent(content)
            || pendingTurnsRef.current[chat.id]
        ) {
            return
        }

        const clientRequestId =
            createSecureUuid()

        const pending = createPendingTurn(
            chat.id,
            content,
            clientRequestId,
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

        const remaining =
            getRetryAfterSeconds(
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
            const updated = await sendMessage(
                pending.chatId,
                {
                    content: pending.content,
                    clientRequestId:
                        pending.clientRequestId,
                },
            )

            applySuccessfulChatUpdate(
                pending,
                updated,
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
        if (
            error instanceof ApiError
            && error.status === 429
        ) {
            const retryAfterSeconds =
                error.retryAfterSeconds ?? 1

            putPendingTurn({
                ...pending,
                status: 'RATE_LIMITED',
                error:
                    'Лимит запросов временно исчерпан.',
                retryAfterUntil:
                    Date.now()
                    + retryAfterSeconds
                    * 1_000,
            })

            setClock(Date.now())
            setSendError(
                'Лимит запросов временно исчерпан. '
                + 'Повтор будет выполнен только с тем же clientRequestId.',
            )
            return
        }

        if (
            error instanceof ApiError
            && error.errorCode
                === 'AI_OUTCOME_AMBIGUOUS'
        ) {
            putPendingTurn({
                ...pending,
                status: 'AMBIGUOUS',
                error:
                    'Результат AI-вызова неоднозначен. '
                    + 'Автоматический повтор запрещён.',
                retryAfterUntil: null,
            })

            setSendError(
                'Результат AI-вызова неоднозначен. '
                + 'Проверьте состояние операции.',
            )
            return
        }

        if (
            error instanceof ApiError
            && (
                error.errorCode
                    === 'CHAT_TURN_IN_PROGRESS'
                || error.errorCode
                    === 'CHAT_BUSY'
            )
        ) {
            putPendingTurn({
                ...pending,
                status: 'PROCESSING',
                error: null,
                retryAfterUntil: null,
            })
        } else {
            putPendingTurn({
                ...pending,
                status: 'SEND_UNKNOWN',
                error: getApiErrorMessage(
                    error,
                    'Статус отправки неизвестен.',
                ),
                retryAfterUntil: null,
            })
        }

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
                    const turn =
                        await getChatTurnStatus(
                            pending.chatId,
                            pending.clientRequestId,
                            {
                                signal:
                                    controller.signal,
                            },
                        )

                    if (
                        turn.state === 'PROCESSING'
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

                    if (
                        turn.state === 'SUCCEEDED'
                    ) {
                        const details =
                            await getChatById(
                                pending.chatId,
                                {
                                    signal:
                                        controller.signal,
                                },
                            )

                        applySuccessfulChatUpdate(
                            pending,
                            details,
                        )
                        return
                    }

                    if (
                        turn.state === 'FAILED'
                    ) {
                        putPendingTurn({
                            ...pending,
                            status: 'FAILED',
                            error:
                                turn.errorMessage
                                ?? (
                                    'AI-запрос '
                                    + 'завершился '
                                    + 'ошибкой.'
                                ),
                            retryAfterUntil: null,
                        })

                        setSendError(
                            turn.errorMessage
                            ?? (
                                'AI-запрос '
                                + 'завершился '
                                + 'ошибкой.'
                            ),
                        )
                        return
                    }

                    putPendingTurn({
                        ...pending,
                        status: 'AMBIGUOUS',
                        error:
                            turn.errorMessage
                            ?? (
                                'Результат операции '
                                + 'остался '
                                + 'неоднозначным.'
                            ),
                        retryAfterUntil: null,
                    })

                    setSendError(
                        'Результат операции '
                        + 'неоднозначен. '
                        + 'Не отправляйте новый '
                        + 'запрос автоматически.',
                    )
                    return
                } catch (error) {
                    if (
                        isRequestAborted(error)
                    ) {
                        return
                    }

                    if (
                        error instanceof ApiError
                        && error.status === 404
                    ) {
                        putPendingTurn({
                            ...pending,
                            status:
                                'SEND_UNKNOWN',
                            error:
                                'Backend не нашёл '
                                + 'turn по '
                                + 'clientRequestId. '
                                + 'Допустим только '
                                + 'повтор с тем же ID.',
                            retryAfterUntil: null,
                        })
                        return
                    }

                    if (
                        attempt
                        === (
                            RECONCILIATION_ATTEMPTS
                            - 1
                        )
                    ) {
                        putPendingTurn({
                            ...pending,
                            status:
                                'SEND_UNKNOWN',
                            error:
                                getApiErrorMessage(
                                    error,
                                    'Не удалось '
                                    + 'определить '
                                    + 'состояние turn.',
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

    function applySuccessfulChatUpdate(
        pending: PendingTurn,
        updated: ChatDetails,
    ) {
        reconciliationControllersRef.current
            .get(pending.chatId)
            ?.abort()

        reconciliationControllersRef.current.delete(
            pending.chatId,
        )

        setPendingTurns((current) => {
            const next = {
                ...current,
            }

            delete next[pending.chatId]

            return next
        })

        setActiveChat((current) => {
            if (
                !current
                || current.id !== pending.chatId
            ) {
                return current
            }

            scrollIntentRef.current = {
                type: 'BOTTOM',
            }

            return mergeChatDetails(
                current,
                updated,
            )
        })

        setChats((current) =>
            moveChatToTop(
                current,
                updated,
            ),
        )
        setSendError('')
    }

    async function loadEarlierHistory() {
        const chat = activeChatRef.current

        if (
            !chat
            || historyLoading
            || (
                historyTotalPages !== null
                && historyPage
                    >= historyTotalPages
            )
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
            const response =
                await getChatMessages(
                    chatId,
                    pageToLoad,
                    MESSAGE_PAGE_SIZE,
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

            const normalized =
                normalizePageResponse(response)

            setHistoryPage(pageToLoad + 1)
            setHistoryTotalPages(
                normalized.totalPages,
            )

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
                        normalized.content,
                        current.messages,
                    ),
                }
            })
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

    function clearTerminalPendingTurn() {
        if (
            !activeChat
            || !activePendingTurn
            || !isTerminalPendingStatus(
                activePendingTurn.status,
            )
        ) {
            return
        }

        setDraft(
            activeChat.id,
            activePendingTurn.content,
        )

        removePendingTurn(activeChat.id)
        setSendError('')
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

    function removePendingTurn(
        chatId: string,
    ) {
        reconciliationControllersRef.current
            .get(chatId)
            ?.abort()

        reconciliationControllersRef.current.delete(
            chatId,
        )

        setPendingTurns((current) => {
            const next = {
                ...current,
            }

            delete next[chatId]

            return next
        })
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
        <div className="page">
            <h1>Чат</h1>

            {listError && (
                <ErrorState
                    title="Ошибка списка чатов"
                    message={listError}
                    action={
                        <button
                            type="button"
                            onClick={() =>
                                setReloadToken(
                                    (value) =>
                                        value + 1,
                                )
                            }
                        >
                            Повторить
                        </button>
                    }
                />
            )}

            <div className="chat-layout">
                <aside className="card sidebar">
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

                    <h2>Чаты</h2>

                    {chatsLoading && (
                        <p className="muted">
                            Загрузка чатов...
                        </p>
                    )}

                    {!chatsLoading
                        && chats.length === 0
                        && (
                            <p>
                                Чатов пока нет.
                            </p>
                        )}

                    {chats.map((chat) => {
                        const pending =
                            pendingTurns[chat.id]

                        return (
                            <button
                                key={chat.id}
                                type="button"
                                className={
                                    activeChat?.id
                                        === chat.id
                                        ? (
                                            'chat-item '
                                            + 'active'
                                        )
                                        : 'chat-item'
                                }
                                disabled={
                                    openingChatId
                                        === chat.id
                                }
                                onClick={() =>
                                    void openChat(
                                        chat.id,
                                    )
                                }
                            >
                                {openingChatId
                                    === chat.id
                                    ? 'Открытие...'
                                    : chat.title}

                                {pending && (
                                    <span
                                        className="muted"
                                        aria-label={
                                            getPendingLabel(
                                                pending,
                                                clock,
                                            )
                                        }
                                    >
                                        {' '}
                                        ·
                                        {' '}
                                        {getPendingShortLabel(
                                            pending,
                                        )}
                                    </span>
                                )}
                            </button>
                        )
                    })}

                    {chatPage + 1
                        < chatTotalPages
                        && (
                            <button
                                type="button"
                                className={
                                    'secondary-button'
                                }
                                disabled={
                                    moreChatsLoading
                                }
                                onClick={() =>
                                    void loadMoreChats()
                                }
                            >
                                {moreChatsLoading
                                    ? 'Загрузка...'
                                    : (
                                        'Показать ещё '
                                        + 'чаты'
                                    )}
                            </button>
                        )}
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
                                    'Создайте чат, '
                                    + 'чтобы начать общение.'
                                }
                            />
                        )}

                    {activeChat && (
                        <>
                            <h2>
                                {activeChat.title}
                            </h2>

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
                                    pending={
                                        activePendingTurn
                                    }
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
                                        clearTerminalPendingTurn
                                    }
                                />
                            )}

                            {canLoadEarlier && (
                                <button
                                    type="button"
                                    className={
                                        'secondary-button'
                                    }
                                    disabled={
                                        historyLoading
                                    }
                                    onClick={() =>
                                        void loadEarlierHistory()
                                    }
                                >
                                    {historyLoading
                                        ? (
                                            'Загрузка '
                                            + 'истории...'
                                        )
                                        : (
                                            'Загрузить '
                                            + 'более ранние '
                                            + 'сообщения'
                                        )}
                                </button>
                            )}

                            <div
                                ref={
                                    messagesContainerRef
                                }
                                className="messages"
                                aria-busy={
                                    activeChatBusy
                                }
                            >
                                {displayMessages.length
                                    === 0
                                    && !activeChatBusy
                                    && (
                                        <p>
                                            Сообщений
                                            пока нет.
                                        </p>
                                    )}

                                {displayMessages.map(
                                    (item) => (
                                        <MessageView
                                            key={item.id}
                                            message={item}
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
                                            className={
                                                'message '
                                                + 'assistant '
                                                + 'pending'
                                            }
                                            role="status"
                                            aria-live="polite"
                                        >
                                            <strong>
                                                ASSISTANT
                                            </strong>
                                            <p>
                                                {activePendingTurn.status
                                                    === 'SENDING'
                                                    ? (
                                                        'Запрос '
                                                        + 'отправляется...'
                                                    )
                                                    : (
                                                        'Формируется '
                                                        + 'ответ...'
                                                    )}
                                            </p>
                                        </div>
                                    )}

                                <div
                                    ref={messagesEndRef}
                                />
                            </div>

                            <div className="message-form">
                                <div
                                    style={{
                                        width: '100%',
                                    }}
                                >
                                    <label
                                        htmlFor={
                                            'chat-message'
                                        }
                                    >
                                        Сообщение
                                    </label>

                                    <textarea
                                        id="chat-message"
                                        rows={3}
                                        value={
                                            activeDraft
                                        }
                                        onChange={(
                                            event,
                                        ) =>
                                            setDraft(
                                                activeChat.id,
                                                event.target
                                                    .value,
                                            )
                                        }
                                        onKeyDown={
                                            handleTextareaKeyDown
                                        }
                                        placeholder={
                                            'Введите сообщение. '
                                            + 'Ctrl+Enter — '
                                            + 'отправить'
                                        }
                                        maxLength={
                                            MESSAGE_MAX_LENGTH
                                        }
                                        disabled={
                                            activeChatBusy
                                        }
                                        aria-describedby={
                                            'chat-message-counter'
                                        }
                                    />

                                    <small
                                        id={
                                            'chat-message-counter'
                                        }
                                        className="muted"
                                    >
                                        {activeDraft.length}
                                        {' '}
                                        /
                                        {' '}
                                        {
                                            MESSAGE_MAX_LENGTH
                                        }
                                    </small>
                                </div>

                                <button
                                    type="button"
                                    onClick={() =>
                                        void handleSendMessage()
                                    }
                                    disabled={
                                        activeChatHasPending
                                        || !hasMeaningfulContent(
                                            activeDraft,
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
        </div>
    )
}

type MessageViewProps = {
    message: ReturnType<
        typeof buildDisplayMessages
    >[number]
}

function MessageView({
    message,
}: MessageViewProps) {
    const aiResponseLabel =
        getAiResponseLabel(message)

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
            <strong>{message.role}</strong>

            {message.status === 'FAILED' && (
                <span
                    className={
                        'status-badge '
                        + 'status-disabled'
                    }
                >
                    Ошибка
                </span>
            )}

            {message.uiStatus && (
                <span
                    className={
                        'status-badge '
                        + 'status-disabled'
                    }
                >
                    {getPendingShortLabel({
                        status:
                            message.uiStatus,
                    })}
                </span>
            )}

            {aiResponseLabel && (
                <span
                    className="status-badge"
                >
                    {aiResponseLabel}
                </span>
            )}

            <p>{message.content}</p>

            {message.role === 'ASSISTANT' && (
                <small>
                    модель:
                    {' '}
                    {message.model ?? '—'}
                    {' '}
                    |
                    {' '}
                    {formatUsage(message)}
                    {' '}
                    |
                    {' '}
                    {formatPricing(message)}
                </small>
            )}
        </article>
    )
}

type PendingTurnStateProps = {
    pending: PendingTurn
    retryAfterSeconds: number
    onRetry: () => void
    onCheck: () => void
    onPrepareNew: () => void
}

function PendingTurnState({
    pending,
    retryAfterSeconds,
    onRetry,
    onCheck,
    onPrepareNew,
}: PendingTurnStateProps) {
    const terminal =
        isTerminalPendingStatus(
            pending.status,
        )

    return (
        <div
            className="card"
            role={
                pending.status === 'FAILED'
                || pending.status === 'AMBIGUOUS'
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
                    Date.now(),
                )}
            </strong>

            {pending.error && (
                <p>{pending.error}</p>
            )}

            <div className="modal-actions">
                {(
                    pending.status
                        === 'SEND_UNKNOWN'
                    || pending.status
                        === 'RATE_LIMITED'
                ) && (
                    <button
                        type="button"
                        disabled={
                            retryAfterSeconds > 0
                        }
                        onClick={onRetry}
                    >
                        Повторить с тем же ID
                    </button>
                )}

                {(
                    pending.status
                        === 'PROCESSING'
                    || pending.status
                        === 'SEND_UNKNOWN'
                    || pending.status
                        === 'AMBIGUOUS'
                ) && (
                    <button
                        type="button"
                        className={
                            'secondary-button'
                        }
                        onClick={onCheck}
                    >
                        Проверить статус
                    </button>
                )}

                {terminal && (
                    <button
                        type="button"
                        className={
                            'secondary-button'
                        }
                        onClick={onPrepareNew}
                    >
                        Вернуть текст в поле
                    </button>
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
            const seconds =
                getRetryAfterSeconds(
                    pending,
                    now,
                )

            return seconds > 0
                ? (
                    'Повтор доступен через '
                    + `${seconds} сек.`
                )
                : (
                    'Повтор разрешён с тем же '
                    + 'clientRequestId.'
                )
        }
    }
}

function getPendingShortLabel(
    pending: Pick<
        PendingTurn,
        'status'
    >,
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
                pending.retryAfterUntil
                - now
            ) / 1_000,
        ),
    )
}

function isTerminalPendingStatus(
    status: PendingTurnStatus,
): boolean {
    return status === 'FAILED'
        || status === 'AMBIGUOUS'
}

function isRequestAborted(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && error.errorCode
            === 'REQUEST_ABORTED'
}

function createSecureUuid(): string {
    if (
        typeof crypto === 'undefined'
        || typeof crypto.randomUUID
            !== 'function'
    ) {
        throw new Error(
            'Браузер не поддерживает crypto.randomUUID()',
        )
    }

    return crypto.randomUUID()
}

async function delay(
    milliseconds: number,
    signal: AbortSignal,
): Promise<void> {
    if (signal.aborted) {
        return
    }

    await new Promise<void>((resolve) => {
        const timeoutId =
            window.setTimeout(
                () => {
                    signal.removeEventListener(
                        'abort',
                        abort,
                    )
                    resolve()
                },
                milliseconds,
            )

        const abort = () => {
            window.clearTimeout(timeoutId)
            resolve()
        }

        signal.addEventListener(
            'abort',
            abort,
            {
                once: true,
            },
        )
    })
}

export default ChatPage
