// frontend/src/features/chat/controller/state/useChatConversationState.ts
// Состояние открытого чата, черновика и пользовательских ошибок.
import { useState } from 'react'
import type {
    AnswerPassport,
    ChatCapabilities,
    ChatDetails,
    KnowledgeMode,
} from '../../api/chatApi'
import type { PendingTurn } from '../../lib/chatPage.helpers'
import type { ChatTrustItem } from '../chatTrustInfo'
import { DEFAULT_CAPABILITIES } from '../operations/chatControllerDefaults'

export function useChatConversationState() {
    const [capabilities, setCapabilities] =
        useState<ChatCapabilities>(DEFAULT_CAPABILITIES)
    const [activeChat, setActiveChat] =
        useState<ChatDetails | null>(null)
    const [knowledgeMode, setKnowledgeMode] =
        useState<KnowledgeMode>('GENERAL')
    const [knowledgeBaseId, setKnowledgeBaseId] = useState('')
    const [answerPassports, setAnswerPassports] =
        useState<Record<string, AnswerPassport>>({})
    const [drafts, setDrafts] = useState<Record<string, string>>({})
    const [pendingTurns, setPendingTurns] =
        useState<Record<string, PendingTurn>>({})
    const [chatError, setChatError] = useState('')
    const [sendError, setSendError] = useState('')
    const [clock, setClock] = useState(Date.now())
    const [selectedTrustItem, setSelectedTrustItem] =
        useState<ChatTrustItem | null>(null)

    return {
        capabilities, setCapabilities,
        activeChat, setActiveChat,
        knowledgeMode, setKnowledgeMode,
        knowledgeBaseId, setKnowledgeBaseId,
        answerPassports, setAnswerPassports,
        drafts, setDrafts,
        pendingTurns, setPendingTurns,
        chatError, setChatError,
        sendError, setSendError,
        clock, setClock,
        selectedTrustItem, setSelectedTrustItem,
    }
}
