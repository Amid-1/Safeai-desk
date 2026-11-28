// frontend/src/features/chat/controller/useChatControllerState.ts
// Единая композиция состояния страницы чата. Все хуки вызываются без условий.
import { useChatConversationState } from './state/useChatConversationState'
import { useChatDirectoryState } from './state/useChatDirectoryState'
import { useChatKnowledgeBases } from './state/useChatKnowledgeBases'
import { useChatRequestRefs } from './state/useChatRequestRefs'

export function useChatControllerState() {
    const conversation = useChatConversationState()
    const directory = useChatDirectoryState()
    const knowledge = useChatKnowledgeBases()
    const refs = useChatRequestRefs(
        conversation.activeChat,
        conversation.pendingTurns,
    )

    // Все прежние имена и setter'ы доступны без изменения контрактов.
    return { ...conversation, ...directory, ...knowledge, ...refs }
}

export type ChatControllerState = ReturnType<typeof useChatControllerState>
