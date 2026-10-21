// frontend/src/pages/ChatPage.tsx
import PageErrorBoundary from '../shared/ui/PageErrorBoundary'
import ChatPageView from '../features/chat/controller/ChatPageView'
import { useChatPageController } from '../features/chat/controller/useChatPageController'

function ChatPageContent() {
    const controller = useChatPageController()
    return <ChatPageView controller={controller} />
}

export default function ChatPage() {
    return (
        <PageErrorBoundary>
            <ChatPageContent />
        </PageErrorBoundary>
    )
}
