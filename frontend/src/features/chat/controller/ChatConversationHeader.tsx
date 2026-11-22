// frontend/src/features/chat/controller/ChatConversationHeader.tsx
import type { ChatPageController } from './useChatPageController'
import ChatTitleEditor from '../ui/ChatTitleEditor'
import { CHAT_TRUST_ITEMS } from './chatTrustInfo'
import { isMockModel } from '../lib/chatPage.helpers'

type Props = {
    controller: ChatPageController
}

export default function ChatConversationHeader({ controller }: Props) {
    const { activeChat, lastAssistantModel, handleRenameChat, setSelectedTrustItem } = controller
    if (!activeChat) return null

    const modelIsMock = lastAssistantModel != null && isMockModel(lastAssistantModel)

    return (
        <div className="chat-panel__header">
            <div>
                <ChatTitleEditor
                    key={activeChat.id}
                    title={activeChat.title}
                    onSave={handleRenameChat}
                />
            </div>
            <div className="chat-panel__indicators">
                {lastAssistantModel && (
                    <span
                        className={modelIsMock
                            ? 'chat-panel__model-badge chat-panel__model-badge--mock'
                            : 'chat-panel__model-badge'}
                        title={`Модель последнего ответа: ${lastAssistantModel}`}
                    >
                        {modelIsMock ? 'Тестовый ответ' : 'Ответ AI-модели'}
                    </span>
                )}
                <button
                    type="button"
                    className="chat-panel__status"
                    onClick={() => setSelectedTrustItem(CHAT_TRUST_ITEMS[0])}
                    title="Информация об изоляции и защите данных"
                >
                    О защите данных
                </button>
            </div>
        </div>
    )
}
