// Только композиция чистых фабрик действий. Хуки React здесь не вызываются.
// Каждая фабрика получает тот же snapshot состояния, что и прежний контроллер.
import { createDirectoryActions } from './chatDirectoryActions'
import { createTurnUpdates } from './chatTurnUpdates'
import { createTurnReconciliation } from './chatTurnReconciliation'
import { createTurnFailureHandler } from './chatTurnFailures'
import { createTurnSubmission } from './chatTurnSubmission'
import { createHistoryActions } from './chatHistoryActions'
import type { ChatControllerState } from '../useChatControllerState'
import type { ChatControllerView } from '../useChatControllerView'
import type { useChatSessionLifecycle } from './useChatSessionLifecycle'

type ChatOperationContext = ChatControllerState
    & ChatControllerView
    & ReturnType<typeof useChatSessionLifecycle>

export function createChatPageOperations(context: ChatOperationContext) {
    const directory = createDirectoryActions(context)
    const updates = createTurnUpdates(context)
    const reconciliation = createTurnReconciliation({
        ...context, ...directory, ...updates,
    })
    const failures = createTurnFailureHandler({
        ...context, ...reconciliation, ...updates,
    })
    const submission = createTurnSubmission({
        ...context, ...directory, ...updates, ...failures,
    })
    const history = createHistoryActions(context)

    return {
        setDraft: updates.setDraft,
        loadMoreChats: directory.loadMoreChats,
        handleRenameChat: directory.handleRenameChat,
        handleRenameFromHistory: directory.handleRenameFromHistory,
        handleCreateChat: directory.handleCreateChat,
        handleArchiveChat: directory.handleArchiveChat,
        handleSendMessage: submission.handleSendMessage,
        retryPendingTurn: submission.retryPendingTurn,
        reconcilePendingTurn: reconciliation.reconcilePendingTurn,
        loadEarlierHistory: history.loadEarlierHistory,
        prepareNewRequestFromPending: submission.prepareNewRequestFromPending,
        dismissUnsafePending: submission.dismissUnsafePending,
        copyPendingText: submission.copyPendingText,
        handleTextareaKeyDown: submission.handleTextareaKeyDown,
    }
}
