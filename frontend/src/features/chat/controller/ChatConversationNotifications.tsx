// frontend/src/features/chat/controller/ChatConversationNotifications.tsx
import type { ChatPageController } from './useChatPageController'
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
    } = controller

    return (
        <>
            {chatError && <ErrorNotice message={chatError} />}
            {sendError && sendError !== activePendingTurn?.error && (
                <ErrorNotice message={sendError} />
            )}
            {activePendingTurn && (
                <PendingTurnState
                    pending={activePendingTurn}
                    now={clock}
                    retryAfterSeconds={retryAfterSeconds}
                    onRetry={() => void retryPendingTurn()}
                    onCheck={() => void reconcilePendingTurn(activePendingTurn)}
                    onPrepareNew={prepareNewRequestFromPending}
                    onCopy={() => void copyPendingText()}
                    onDismissUnsafe={dismissUnsafePending}
                />
            )}
        </>
    )
}
