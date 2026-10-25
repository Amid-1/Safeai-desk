// frontend/src/features/chat/controller/useChatPageController.ts
// ============================================================
// frontend/src/features/chat/controller/useChatPageController.ts
// ============================================================
import {
    useCallback,
    useEffect,
    useLayoutEffect,
    useMemo,
    useRef,
    useState,
} from 'react'
import type {
    AnswerPassport,
    Chat,
    ChatCapabilities,
    ChatDetails,
    KnowledgeMode,
} from '../api/chatApi'
import {
    getKnowledgeBases,
} from '../../../entities/knowledge/api/knowledgeApi'
import type {
    KnowledgeBase,
} from '../../../entities/knowledge/api/knowledgeApi'
import {
    buildDisplayMessages,
    isProcessingPendingStatus,
} from '../lib/chatPage.helpers'
import type {
    PendingTurn,
} from '../lib/chatPage.helpers'
import { getRetryAfterSeconds } from '../lib/chatPageSupport'

import type { ChatTrustItem } from './chatTrustInfo'
import { createDirectoryActions } from './operations/chatDirectoryActions'
import { createTurnUpdates } from './operations/chatTurnUpdates'
import { createTurnReconciliation } from './operations/chatTurnReconciliation'
import { createTurnFailureHandler } from './operations/chatTurnFailures'
import { createTurnSubmission } from './operations/chatTurnSubmission'
import { createHistoryActions } from './operations/chatHistoryActions'
import type { ScrollIntent } from './operations/chatControllerTypes'
import { DEFAULT_CAPABILITIES, DEFAULT_CHAT_PAGE_SIZE, DEFAULT_MESSAGE_PAGE_SIZE } from './operations/chatControllerDefaults'
import { useChatSessionLifecycle } from './operations/useChatSessionLifecycle'

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
    // Единая блокировка отправки между соседними рендерами React.
    const sendInFlightRef = useRef(new Set<string>())
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
            // При недоступности баз знаний чат продолжает работать в режиме GENERAL.
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

    // Сведения о провайдере относятся к последнему известному ответу AI,
    // а не к защищённости чата или ещё не полученному ответу.
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
     * Ответ отправки содержит паспорт текущей операции. После перезагрузки
     * доступны только сохранённые сообщения. По неизменяемому clientRequestId
     * восстанавливаем запись turn и связанное свидетельство (answer passport).
     * Для режима GENERAL отсутствие паспорта (404) допустимо и не должно
     * делать всю историю чата недоступной.
     */
    const { openChat, restoreAnswerPassports } = useChatSessionLifecycle({
        messagePageSize,
        reloadToken,
        openRequestSequenceRef,
        historyRequestSequenceRef,
        listRequestSequenceRef,
        openControllerRef,
        historyControllerRef,
        listControllerRef,
        scrollIntentRef,
        setOpeningChatId,
        setChatError,
        setSendError,
        setHistoryPage,
        setHistoryHasNext,
        setHistoryLoading,
        setActiveChat,
        setAnswerPassports,
        setCapabilities,
        setChats,
        setChatPage,
        setChatHasNext,
        setChatsLoading,
        setListError,
    })

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

    const abortActiveRequests = useCallback(() => {
        listControllerRef.current?.abort()
        openControllerRef.current?.abort()
        historyControllerRef.current?.abort()
    }, [])

    useEffect(() => {
        const controllers =
            reconciliationControllersRef.current

        return () => {
            abortActiveRequests()

            controllers.forEach(
                (controller) => {
                    controller.abort()
                },
            )

            controllers.clear()
        }
    }, [abortActiveRequests])

    function directoryActions() {
        return createDirectoryActions({
        chats,
        activeChat,
        pendingTurns,
        moreChatsLoading,
        chatToRename,
        renamingChatId,
        archivingChatId,
        chatPage,
        chatHasNext,
        openRequestSequenceRef,
        historyRequestSequenceRef,
        openControllerRef,
        historyControllerRef,
        createInFlightRef,
        pendingTurnsRef,
        activeChatRef,
        scrollIntentRef,
        setChats,
        setActiveChat,
        setDrafts,
        setListError,
        setMoreChatsLoading,
        setChatCreating,
        setChatToArchive,
        setRenamingChatId,
        setArchivingChatId,
        setOpeningChatId,
        setHistoryLoading,
        setChatPage,
        setChatHasNext,
        setHistoryPage,
        setHistoryHasNext,
        chatPageSize,
        openChat,
        })
    }

    const loadMoreChats: ReturnType<typeof createDirectoryActions>['loadMoreChats'] =
        (...args) => directoryActions().loadMoreChats(...args)
    const handleRenameChat: ReturnType<typeof createDirectoryActions>['handleRenameChat'] =
        (...args) => directoryActions().handleRenameChat(...args)
    const handleRenameFromHistory: ReturnType<typeof createDirectoryActions>['handleRenameFromHistory'] =
        (...args) => directoryActions().handleRenameFromHistory(...args)
    const refreshChatTitle: ReturnType<typeof createDirectoryActions>['refreshChatTitle'] =
        (...args) => directoryActions().refreshChatTitle(...args)
    const handleCreateChat: ReturnType<typeof createDirectoryActions>['handleCreateChat'] =
        (...args) => directoryActions().handleCreateChat(...args)
    const handleArchiveChat: ReturnType<typeof createDirectoryActions>['handleArchiveChat'] =
        (...args) => directoryActions().handleArchiveChat(...args)

    function turnUpdates() {
        return createTurnUpdates({
        reconciliationControllersRef,
        scrollIntentRef,
        pendingTurnsRef,
        setChats,
        setActiveChat,
        setAnswerPassports,
        setDrafts,
        setPendingTurns,
        setSendError,
        })
    }

    const applySendSuccess: ReturnType<typeof createTurnUpdates>['applySendSuccess'] =
        (...args) => turnUpdates().applySendSuccess(...args)
    const applyTurnSuccess: ReturnType<typeof createTurnUpdates>['applyTurnSuccess'] =
        (...args) => turnUpdates().applyTurnSuccess(...args)
    const applyReloadedSuccess: ReturnType<typeof createTurnUpdates>['applyReloadedSuccess'] =
        (...args) => turnUpdates().applyReloadedSuccess(...args)
    const applyTurnMessages: ReturnType<typeof createTurnUpdates>['applyTurnMessages'] =
        (...args) => turnUpdates().applyTurnMessages(...args)
    const finishPendingTurn: ReturnType<typeof createTurnUpdates>['finishPendingTurn'] =
        (...args) => turnUpdates().finishPendingTurn(...args)
    const setDraft: ReturnType<typeof createTurnUpdates>['setDraft'] =
        (...args) => turnUpdates().setDraft(...args)
    const putPendingTurn: ReturnType<typeof createTurnUpdates>['putPendingTurn'] =
        (...args) => turnUpdates().putPendingTurn(...args)

    function turnReconciliation() {
        return createTurnReconciliation({
        reconciliationControllersRef,
        setSendError,
        refreshChatTitle,
        applyTurnSuccess,
        applyReloadedSuccess,
        applyTurnMessages,
        putPendingTurn,
        })
    }

    const reconcilePendingTurn: ReturnType<typeof createTurnReconciliation>['reconcilePendingTurn'] =
        (...args) => turnReconciliation().reconcilePendingTurn(...args)

    // Обработчик ошибок создаётся при выполнении операции,
    // а не во время React render.
    function turnFailureHandler() {
        return createTurnFailureHandler({
            setSendError,
            setClock,
            reconcilePendingTurn,
            putPendingTurn,
        })
    }

    const handleSendFailure: ReturnType<typeof createTurnFailureHandler>['handleSendFailure'] =
        (...args) => turnFailureHandler().handleSendFailure(...args)

    function turnSubmission() {
        return createTurnSubmission({
        capabilities,
        activeChat,
        knowledgeMode,
        knowledgeBaseId,
        drafts,
        clock,
        pendingTurnsRef,
        activeChatRef,
        scrollIntentRef,
        setSendError,
        activePendingTurn,
        activeChatHasPending,
        refreshChatTitle,
        handleSendFailure,
        applySendSuccess,
        finishPendingTurn,
        setDraft,
        putPendingTurn,
        sendInFlightRef,
        })
    }

    const handleSendMessage: ReturnType<typeof createTurnSubmission>['handleSendMessage'] =
        (...args) => turnSubmission().handleSendMessage(...args)
    const retryPendingTurn: ReturnType<typeof createTurnSubmission>['retryPendingTurn'] =
        (...args) => turnSubmission().retryPendingTurn(...args)
    const prepareNewRequestFromPending: ReturnType<typeof createTurnSubmission>['prepareNewRequestFromPending'] =
        (...args) => turnSubmission().prepareNewRequestFromPending(...args)
    const dismissUnsafePending: ReturnType<typeof createTurnSubmission>['dismissUnsafePending'] =
        (...args) => turnSubmission().dismissUnsafePending(...args)
    const copyPendingText: ReturnType<typeof createTurnSubmission>['copyPendingText'] =
        (...args) => turnSubmission().copyPendingText(...args)
    const handleTextareaKeyDown: ReturnType<typeof createTurnSubmission>['handleTextareaKeyDown'] =
        (...args) => turnSubmission().handleTextareaKeyDown(...args)

    function historyActions() {
        return createHistoryActions({
        historyLoading,
        historyPage,
        historyHasNext,
        messagesContainerRef,
        historyRequestSequenceRef,
        historyControllerRef,
        activeChatRef,
        scrollIntentRef,
        setActiveChat,
        setChatError,
        setHistoryLoading,
        setHistoryPage,
        setHistoryHasNext,
        messagePageSize,
        restoreAnswerPassports,
        })
    }

    const loadEarlierHistory: ReturnType<typeof createHistoryActions>['loadEarlierHistory'] =
        (...args) => historyActions().loadEarlierHistory(...args)

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
