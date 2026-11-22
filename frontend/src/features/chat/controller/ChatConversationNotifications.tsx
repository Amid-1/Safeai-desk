// frontend/src/features/chat/controller/ChatConversationNotifications.tsx
import type { ChatPageController } from './useChatPageController'
import { useState } from 'react'
import { PendingTurnState } from '../ui/ChatMessageViews'

type Props = { controller: ChatPageController }

function ErrorNotice({ message }: { message: string }) {
    return <div className="error" role="alert" aria-live="assertive">{message}</div>
}

export default function ChatConversationNotifications({ controller }: Props) {
    const {
        chatError,
        sendError,
        activePendingTurn,
        clock,
        retryAfterSeconds,
        retryPendingTurn,
        reconcilePendingTurn,
        prepareNewRequestFromPending,
        copyPendingText,
        dismissUnsafePending,
        handleCreateChat,
        chatCreating,
    } = controller
    const [createError, setCreateError] = useState('')

    return (
        <>
            {chatError && <ErrorNotice message={chatError} />}
            {sendError && !activePendingTurn && (
                <ErrorNotice message={sendError} />
            )}
            {activePendingTurn && (
                <PendingTurnState
                    key={activePendingTurn.clientRequestId}
                    pending={activePendingTurn}
                    now={clock}
                    retryAfterSeconds={retryAfterSeconds}
                    onRetry={() => void retryPendingTurn()}
                    onCheck={() => void reconcilePendingTurn(activePendingTurn)}
                    onPrepareNew={prepareNewRequestFromPending}
                    onCopy={() => void copyPendingText()}
                    onDismissUnsafe={dismissUnsafePending}
                    newChatBusy={chatCreating}
                    onNewChat={() => {
                        setCreateError('')
                        void handleCreateChat().then(error => setCreateError(error ?? ''))
                    }}
                />
            )}
            {createError && <ErrorNotice message={createError} />}
        </>
    )
}
