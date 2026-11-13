// Публичный контракт контроллера страницы чата.
// Состояние, визуальные эффекты и операции живут отдельно.
import { useChatSessionLifecycle } from './operations/useChatSessionLifecycle'
import { createChatPageOperations } from './operations/createChatPageOperations'
import { useChatControllerState } from './useChatControllerState'
import { useChatControllerView } from './useChatControllerView'

export function useChatPageController() {
    const state = useChatControllerState()
    const view = useChatControllerView(state)
    const lifecycle = useChatSessionLifecycle({ ...state, ...view })
    const operations = createChatPageOperations({ ...state, ...view, ...lifecycle })

    // Публичные поля остаются точь-в-точь прежними. Не раскрываем наружу
    // внутренние AbortController, mutex и refs, которыми владеет hook.
    return {
        capabilities: state.capabilities,
        chats: state.chats,
        activeChat: state.activeChat,
        knowledgeBases: state.knowledgeBases,
        knowledgeMode: state.knowledgeMode,
        knowledgeBaseId: state.knowledgeBaseId,
        answerPassports: state.answerPassports,
        pendingTurns: state.pendingTurns,
        listError: state.listError,
        chatError: state.chatError,
        sendError: state.sendError,
        chatsLoading: state.chatsLoading,
        moreChatsLoading: state.moreChatsLoading,
        chatCreating: state.chatCreating,
        chatToArchive: state.chatToArchive,
        chatToRename: state.chatToRename,
        renamingChatId: state.renamingChatId,
        archivingChatId: state.archivingChatId,
        openingChatId: state.openingChatId,
        historyLoading: state.historyLoading,
        chatHasNext: state.chatHasNext,
        historyHasNext: state.historyHasNext,
        clock: state.clock,
        selectedTrustItem: state.selectedTrustItem,
        messagesEndRef: state.messagesEndRef,
        messagesContainerRef: state.messagesContainerRef,
        activePendingTurn: view.activePendingTurn,
        activeDraft: view.activeDraft,
        displayMessages: view.displayMessages,
        lastAssistantModel: view.lastAssistantModel,
        activeChatHasPending: view.activeChatHasPending,
        activeChatBusy: view.activeChatBusy,
        retryAfterSeconds: view.retryAfterSeconds,
        setSelectedTrustItem: state.setSelectedTrustItem,
        setReloadToken: state.setReloadToken,
        setChatToArchive: state.setChatToArchive,
        setChatToRename: state.setChatToRename,
        setKnowledgeMode: state.setKnowledgeMode,
        setKnowledgeBaseId: state.setKnowledgeBaseId,
        setDraft: operations.setDraft,
        openChat: lifecycle.openChat,
        loadMoreChats: operations.loadMoreChats,
        handleRenameChat: operations.handleRenameChat,
        handleRenameFromHistory: operations.handleRenameFromHistory,
        handleCreateChat: operations.handleCreateChat,
        handleArchiveChat: operations.handleArchiveChat,
        handleSendMessage: operations.handleSendMessage,
        retryPendingTurn: operations.retryPendingTurn,
        reconcilePendingTurn: operations.reconcilePendingTurn,
        loadEarlierHistory: operations.loadEarlierHistory,
        prepareNewRequestFromPending: operations.prepareNewRequestFromPending,
        dismissUnsafePending: operations.dismissUnsafePending,
        copyPendingText: operations.copyPendingText,
        handleTextareaKeyDown: operations.handleTextareaKeyDown,
    }
}

export type ChatPageController = ReturnType<typeof useChatPageController>
