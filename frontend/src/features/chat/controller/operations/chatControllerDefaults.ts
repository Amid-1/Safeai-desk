// frontend/src/features/chat/controller/operations/chatControllerDefaults.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatControllerDefaults.ts
// Согласованные безопасные значения по умолчанию для чата.
// ============================================================
import type { ChatCapabilities } from '../../api/chatApi'

export const DEFAULT_CHAT_PAGE_SIZE = 50
export const DEFAULT_MESSAGE_PAGE_SIZE = 50
export const DEFAULT_MESSAGE_MAX_LENGTH = 16_000

export const DEFAULT_CAPABILITIES: ChatCapabilities = {
    maxMessageChars: DEFAULT_MESSAGE_MAX_LENGTH,
    maxChatPageSize: 100,
    maxMessagePageSize: 100,
    detailsMessageLimit: 50,
}
