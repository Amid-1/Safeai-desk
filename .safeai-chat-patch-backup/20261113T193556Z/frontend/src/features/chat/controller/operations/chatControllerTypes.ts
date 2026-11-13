// frontend/src/features/chat/controller/operations/chatControllerTypes.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatControllerTypes.ts
// Типизированные зависимости и намерения прокрутки контроллера чата.
// ============================================================
import type { Dispatch, RefObject, SetStateAction } from 'react'
import type {
    AnswerPassport, Chat, ChatCapabilities, ChatDetails,
    ChatMessage, ChatTurnStatus, KnowledgeMode, SendMessageResponse,
} from '../../api/chatApi'
import type { PendingTurn } from '../../lib/chatPage.helpers'

export type ScrollIntent =
    | { type: 'BOTTOM' }
    | { type: 'PRESERVE_TOP'; scrollHeight: number; scrollTop: number }
    | null

export type ChatControllerDependencies = {
    capabilities: ChatCapabilities
    chats: Chat[]
    activeChat: ChatDetails | null
    knowledgeMode: KnowledgeMode
    knowledgeBaseId: string
    drafts: Record<string, string>
    pendingTurns: Record<string, PendingTurn>
    moreChatsLoading: boolean
    chatToRename: Chat | null
    renamingChatId: string | null
    archivingChatId: string | null
    chatPage: number
    chatHasNext: boolean
    historyPage: number
    historyHasNext: boolean
    historyLoading: boolean
    clock: number
    reloadToken: number
    activePendingTurn: PendingTurn | undefined
    activeChatHasPending: boolean
    chatPageSize: number
    messagePageSize: number
    openRequestSequenceRef: RefObject<number>
    listRequestSequenceRef: RefObject<number>
    listControllerRef: RefObject<AbortController | null>
    historyRequestSequenceRef: RefObject<number>
    openControllerRef: RefObject<AbortController | null>
    historyControllerRef: RefObject<AbortController | null>
    reconciliationControllersRef: RefObject<Map<string, AbortController>>
    createInFlightRef: RefObject<boolean>
    sendInFlightRef: RefObject<Set<string>>
    pendingTurnsRef: RefObject<Record<string, PendingTurn>>
    activeChatRef: RefObject<ChatDetails | null>
    scrollIntentRef: RefObject<ScrollIntent>
    messagesContainerRef: RefObject<HTMLDivElement | null>
    setChats: Dispatch<SetStateAction<Chat[]>>
    setCapabilities: Dispatch<SetStateAction<ChatCapabilities>>
    setChatsLoading: Dispatch<SetStateAction<boolean>>
    setActiveChat: Dispatch<SetStateAction<ChatDetails | null>>
    setAnswerPassports: Dispatch<SetStateAction<Record<string, AnswerPassport>>>
    setDrafts: Dispatch<SetStateAction<Record<string, string>>>
    setPendingTurns: Dispatch<SetStateAction<Record<string, PendingTurn>>>
    setListError: Dispatch<SetStateAction<string>>
    setSendError: Dispatch<SetStateAction<string>>
    setChatError: Dispatch<SetStateAction<string>>
    setMoreChatsLoading: Dispatch<SetStateAction<boolean>>
    setChatCreating: Dispatch<SetStateAction<boolean>>
    setChatToArchive: Dispatch<SetStateAction<Chat | null>>
    setRenamingChatId: Dispatch<SetStateAction<string | null>>
    setArchivingChatId: Dispatch<SetStateAction<string | null>>
    setOpeningChatId: Dispatch<SetStateAction<string | null>>
    setHistoryLoading: Dispatch<SetStateAction<boolean>>
    setChatPage: Dispatch<SetStateAction<number>>
    setChatHasNext: Dispatch<SetStateAction<boolean>>
    setHistoryPage: Dispatch<SetStateAction<number>>
    setHistoryHasNext: Dispatch<SetStateAction<boolean>>
    setClock: Dispatch<SetStateAction<number>>
    openChat: (chatId: string) => Promise<void>
    restoreAnswerPassports: (
        chatId: string, messages: ChatMessage[], signal: AbortSignal,
        expectedOpenSequence?: number,
    ) => Promise<void>
    refreshChatTitle: (chatId: string) => Promise<void>
    applySendSuccess: (pending: PendingTurn, result: SendMessageResponse) => void
    applyTurnSuccess: (pending: PendingTurn, turn: ChatTurnStatus) => void
    applyReloadedSuccess: (pending: PendingTurn, details: ChatDetails) => void
    applyTurnMessages: (chatId: string, turn: ChatTurnStatus) => void
    putPendingTurn: (pending: PendingTurn) => void
    finishPendingTurn: (chatId: string) => void
    reconcilePendingTurn: (pending: PendingTurn) => Promise<void>
    handleSendFailure: (pending: PendingTurn, error: unknown) => Promise<void>
    setDraft: (chatId: string, value: string) => void
}
