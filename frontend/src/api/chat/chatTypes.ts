// frontend/src/api/chat/chatTypes.ts
// Типы ответов чата, сообщения, операции и паспорта ответа.

import type {
    AiResponseStatus,
    ChatMessageRole,
    ChatMessageStatus,
    PricingStatus,
    UsageStatus,
} from '../types'
import type { ChatTurnState } from './chatEnums'

export type Chat = {
    id: string
    title: string
    createdAt: string
    updatedAt: string
}

export type ChatMessage = {
    id: string
    clientRequestId: string | null
    replyToMessageId: string | null

    role: ChatMessageRole
    content: string
    status: ChatMessageStatus

    requestedModel: string | null
    model: string | null
    providerMessageId: string | null
    providerRequestId: string | null

    aiResponseStatus: AiResponseStatus | null
    finishReason: string | null

    inputTokens: number | null
    outputTokens: number | null
    usageStatus: UsageStatus

    costUsd: string | null
    pricingStatus: PricingStatus
    currency: string | null
    pricingVersion: string | null
    pricingCalculatedAt: string | null

    createdAt: string
}

export type ChatDetails = Chat & {
    messages: ChatMessage[]
}

export type ChatSliceResponse<T> = {
    content: T[]
    page: number
    size: number
    first: boolean
    last: boolean
    hasNext: boolean
    hasPrevious: boolean
}

export type ChatCapabilities = {
    maxMessageChars: number
    maxChatPageSize: number
    maxMessagePageSize: number
    detailsMessageLimit: number
}

export type KnowledgeMode =
    | 'GENERAL'
    | 'KNOWLEDGE_ASSISTED'
    | 'KNOWLEDGE_ONLY'

export type AnswerCitation = {
    label: string
    chunkId: string
    documentId: string
    documentVersionId: string
    documentName: string
    versionNumber: number
    chunkOrdinal: number
    pageFrom: number | null
    pageTo: number | null
    heading: string | null
    contentSha256: string
}

export type AnswerPassport = {
    id: string
    chatTurnId: string
    retrievalRunId: string
    knowledgeBaseId: string
    modelRouteDecisionId: string | null
    knowledgeMode: Exclude<KnowledgeMode, 'GENERAL'>
    provider: string
    requestedModel: string
    resolvedModel: string
    embeddingModel: string
    contextSha256: string
    answerSha256: string
    evidenceSufficient: boolean
    citationsValid: boolean
    createdAt: string
    citations: AnswerCitation[]
}

export type SendMessageRequest = {
    content: string
    clientRequestId: string
    knowledgeBaseId?: string | null
    knowledgeMode?: KnowledgeMode
}

export type SendMessageResponse = {
    chatId: string
    turnId: string
    clientRequestId: string
    providerOperationId: string
    state: 'SUCCEEDED'
    replay: boolean
    userMessage: ChatMessage
    assistantMessage: ChatMessage
    chatUpdatedAt: string | null
    createdAt: string
    completedAt: string
    answerPassport: AnswerPassport | null
}

export type ChatTurnStatus = {
    chatId: string
    turnId: string
    clientRequestId: string
    providerOperationId: string
    state: ChatTurnState
    provider: string | null
    requestedModel: string | null
    resolvedModel: string | null
    providerRequestId: string | null
    providerErrorType: string | null
    failureCode: string | null
    outcomeAmbiguous: boolean
    leaseUntil: string | null
    providerCallStartedAt: string | null
    createdAt: string
    updatedAt: string
    completedAt: string | null
    userMessage: ChatMessage | null
    assistantMessage: ChatMessage | null
}
