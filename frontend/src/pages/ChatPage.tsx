import PageErrorBoundary from '../components/PageErrorBoundary'
import ChatPageView from './chat/ChatPageView'
import { useChatPageController } from './chat/useChatPageController'

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
