// frontend/src/test/fixtures/chatApiFixtures.ts
// Типизированные DTO для проверки полного публичного контракта чатов.

import type {
    AnswerPassport,
    Chat,
    ChatCapabilities,
    ChatMessage,
    ChatTurnStatus,
    SendMessageResponse,
} from '../../features/chat/api/chatApi'

export const IDS = {
    chat: '33333333-3333-4333-8333-333333333333',
    turn: '44444444-4444-4444-8444-444444444444',
    request: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    operation: '55555555-5555-4555-8555-555555555555',
    userMessage: '11111111-1111-4111-8111-111111111111',
    assistantMessage: '22222222-2222-4222-8222-222222222222',
    passport: '66666666-6666-4666-8666-666666666666',
    retrieval: '77777777-7777-4777-8777-777777777777',
    knowledgeBase: '88888888-8888-4888-8888-888888888888',
    document: '99999999-9999-4999-8999-999999999999',
    documentVersion: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    chunk: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
} as const

export const AT = '2026-08-11T10:00:00Z'
export const LATER = '2026-08-11T10:00:01Z'

export function chat(overrides: Partial<Chat> = {}): Chat {
    return {
        id: IDS.chat,
        title: 'Новый чат',
        createdAt: AT,
        updatedAt: LATER,
        ...overrides,
    }
}

export function userMessage(overrides: Partial<ChatMessage> = {}): ChatMessage {
    return {
        id: IDS.userMessage,
        clientRequestId: IDS.request,
        replyToMessageId: null,
        role: 'USER',
        status: 'COMPLETED',
        content: 'hello',
        requestedModel: null,
        model: null,
        providerMessageId: null,
        providerRequestId: null,
        aiResponseStatus: null,
        finishReason: null,
        inputTokens: null,
        outputTokens: null,
        usageStatus: 'NOT_APPLICABLE',
        costUsd: null,
        pricingStatus: 'NOT_APPLICABLE',
        currency: null,
        pricingVersion: null,
        pricingCalculatedAt: null,
        createdAt: AT,
        ...overrides,
    }
}

export function assistantMessage(overrides: Partial<ChatMessage> = {}): ChatMessage {
    return {
        ...userMessage(),
        id: IDS.assistantMessage,
        clientRequestId: null,
        replyToMessageId: IDS.userMessage,
        role: 'ASSISTANT',
        content: 'answer',
        requestedModel: 'mock-safeai',
        model: 'mock-safeai',
        providerRequestId: 'provider-request',
        aiResponseStatus: 'COMPLETED',
        finishReason: 'stop',
        inputTokens: 10,
        outputTokens: 5,
        usageStatus: 'AVAILABLE',
        costUsd: '0',
        pricingStatus: 'FREE',
        currency: 'USD',
        pricingVersion: 'mock',
        pricingCalculatedAt: LATER,
        createdAt: LATER,
        ...overrides,
    }
}

export function passport(overrides: Partial<AnswerPassport> = {}): AnswerPassport {
    return {
        id: IDS.passport,
        chatTurnId: IDS.turn,
        retrievalRunId: IDS.retrieval,
        knowledgeBaseId: IDS.knowledgeBase,
        modelRouteDecisionId: null,
        knowledgeMode: 'KNOWLEDGE_ASSISTED',
        provider: 'mock',
        requestedModel: 'mock-safeai',
        resolvedModel: 'mock-safeai',
        embeddingModel: 'mock-embedding',
        contextSha256: 'a'.repeat(64),
        answerSha256: 'b'.repeat(64),
        evidenceSufficient: true,
        citationsValid: true,
        createdAt: LATER,
        citations: [{
            label: '[1]',
            chunkId: IDS.chunk,
            documentId: IDS.document,
            documentVersionId: IDS.documentVersion,
            documentName: 'Документ',
            versionNumber: 2,
            chunkOrdinal: 3,
            pageFrom: 1,
            pageTo: 2,
            heading: 'Раздел',
            contentSha256: 'c'.repeat(64),
        }],
        ...overrides,
    }
}

export function succeeded(overrides: Partial<SendMessageResponse> = {}): SendMessageResponse {
    return {
        chatId: IDS.chat,
        turnId: IDS.turn,
        clientRequestId: IDS.request,
        providerOperationId: IDS.operation,
        state: 'SUCCEEDED',
        replay: false,
        userMessage: userMessage(),
        assistantMessage: assistantMessage(),
        chatUpdatedAt: LATER,
        createdAt: AT,
        completedAt: LATER,
        answerPassport: null,
        ...overrides,
    }
}

export function turn(overrides: Partial<ChatTurnStatus> = {}): ChatTurnStatus {
    return {
        chatId: IDS.chat,
        turnId: IDS.turn,
        clientRequestId: IDS.request,
        providerOperationId: IDS.operation,
        state: 'PROCESSING',
        provider: 'mock',
        requestedModel: 'mock-safeai',
        resolvedModel: null,
        providerRequestId: null,
        providerErrorType: null,
        failureCode: null,
        outcomeAmbiguous: false,
        leaseUntil: LATER,
        providerCallStartedAt: null,
        createdAt: AT,
        updatedAt: LATER,
        completedAt: null,
        userMessage: userMessage(),
        assistantMessage: null,
        ...overrides,
    }
}

export function capabilities(
    overrides: Partial<ChatCapabilities> = {},
): ChatCapabilities {
    return {
        maxMessageChars: 16_000,
        maxChatPageSize: 100,
        maxMessagePageSize: 100,
        detailsMessageLimit: 50,
        ...overrides,
    }
}

export function slice<T>(items: T[], page = 0) {
    return {
        content: items,
        page,
        size: 50,
        first: page === 0,
        last: true,
        hasNext: false,
        hasPrevious: page > 0,
    }
}
