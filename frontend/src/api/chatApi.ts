// frontend/src/api/chatApi.ts
// Совместимая публичная точка входа. Внешние импорты оставлены без изменений.

export type {
    Chat, ChatMessage, ChatDetails, ChatSliceResponse, ChatCapabilities,
    KnowledgeMode, AnswerCitation, AnswerPassport, SendMessageRequest,
    SendMessageResponse, ChatTurnStatus,
} from './chat/chatTypes'
export type { ChatTurnState } from './chat/chatEnums'

export {
    getChatCapabilities, getChats, getChatById, getChatMessages, createChat,
    updateChatTitle, archiveChat, sendMessage, getChatTurnStatus, getAnswerPassport,
} from './chat/chatClient'

export {
    parseChat, parseChatDetails, parseChatSliceResponse, parseChatCapabilities,
} from './chat/chatCoreParsers'
export { parseChatMessage } from './chat/chatMessageParser'
export { parseSendMessageResponse, parseChatTurnStatus } from './chat/chatTurnParsers'
export { parseAnswerPassport } from './chat/chatPassportParser'
