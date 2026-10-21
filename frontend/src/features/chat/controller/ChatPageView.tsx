// frontend/src/features/chat/controller/ChatPageView.tsx
// ============================================================
// frontend/src/features/chat/controller/ChatPageView.tsx
// ============================================================
import type { ChatPageController } from './useChatPageController'
import { ErrorState } from '../../../shared/ui/StateBlock'
import ChatHero from './ChatHero'
import ChatSidebar from './ChatSidebar'
import ChatConversationPanel from './ChatConversationPanel'
import ChatPageDialogs from './ChatPageDialogs'

type ChatPageViewProps = {
    controller: ChatPageController
}

export default function ChatPageView({ controller }: ChatPageViewProps) {
    const { listError, setReloadToken, setSelectedTrustItem } = controller

    return (
        <div className="page chat-page">
            <ChatHero onSelectTrustItem={setSelectedTrustItem} />
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
                <ChatSidebar controller={controller} />
                <ChatConversationPanel controller={controller} />
            </div>

            <ChatPageDialogs controller={controller} />
        </div>
    )
}
