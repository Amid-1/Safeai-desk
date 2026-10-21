// frontend/src/api/chat/chatPassportParser.ts
// Доказательства RAG-ответа, ссылки на документы и хэши.

import {
    contractError, expectEnum, expectInstant, expectNullableNonNegativeInteger,
    expectNullableString, expectNullableUuid, expectRecord, expectString, expectUuid,
} from '../runtime'
import type { AnswerPassport } from './chatTypes'
import { expectBoolean } from './chatValidation'

export function parseAnswerPassport(
    value: unknown,
    field = 'answerPassport',
): AnswerPassport {
    const record = expectRecord(value, field)
    const mode = expectEnum(
        record.knowledgeMode,
        `${field}.knowledgeMode`,
        ['KNOWLEDGE_ASSISTED', 'KNOWLEDGE_ONLY'] as const,
    )
    if (!Array.isArray(record.citations)) {
        throw contractError(`${field}.citations должен быть массивом`)
    }
    return {
        id: expectUuid(record.id, `${field}.id`),
        chatTurnId: expectUuid(record.chatTurnId, `${field}.chatTurnId`),
        retrievalRunId: expectUuid(
            record.retrievalRunId,
            `${field}.retrievalRunId`,
        ),
        knowledgeBaseId: expectUuid(
            record.knowledgeBaseId,
            `${field}.knowledgeBaseId`,
        ),
        modelRouteDecisionId: expectNullableUuid(
            record.modelRouteDecisionId ?? null,
            `${field}.modelRouteDecisionId`,
        ),
        knowledgeMode: mode,
        provider: expectString(record.provider, `${field}.provider`),
        requestedModel: expectString(
            record.requestedModel,
            `${field}.requestedModel`,
        ),
        resolvedModel: expectString(
            record.resolvedModel,
            `${field}.resolvedModel`,
        ),
        embeddingModel: expectString(
            record.embeddingModel,
            `${field}.embeddingModel`,
        ),
        contextSha256: expectString(
            record.contextSha256,
            `${field}.contextSha256`,
            { maxLength: 64 },
        ),
        answerSha256: expectString(
            record.answerSha256,
            `${field}.answerSha256`,
            { maxLength: 64 },
        ),
        evidenceSufficient: expectBoolean(
            record.evidenceSufficient,
            `${field}.evidenceSufficient`,
        ),
        citationsValid: expectBoolean(
            record.citationsValid,
            `${field}.citationsValid`,
        ),
        createdAt: expectInstant(record.createdAt, `${field}.createdAt`),
        citations: record.citations.map((citation, index) => {
            const item = expectRecord(
                citation,
                `${field}.citations[${index}]`,
            )
            return {
                label: expectString(item.label, `${field}.citations[${index}].label`),
                chunkId: expectUuid(item.chunkId, `${field}.citations[${index}].chunkId`),
                documentId: expectUuid(item.documentId, `${field}.citations[${index}].documentId`),
                documentVersionId: expectUuid(item.documentVersionId, `${field}.citations[${index}].documentVersionId`),
                documentName: expectString(item.documentName, `${field}.citations[${index}].documentName`),
                versionNumber: expectNullableNonNegativeInteger(item.versionNumber, `${field}.citations[${index}].versionNumber`) ?? 0,
                chunkOrdinal: expectNullableNonNegativeInteger(item.chunkOrdinal, `${field}.citations[${index}].chunkOrdinal`) ?? 0,
                pageFrom: expectNullableNonNegativeInteger(item.pageFrom ?? null, `${field}.citations[${index}].pageFrom`),
                pageTo: expectNullableNonNegativeInteger(item.pageTo ?? null, `${field}.citations[${index}].pageTo`),
                heading: expectNullableString(item.heading ?? null, `${field}.citations[${index}].heading`),
                contentSha256: expectString(item.contentSha256, `${field}.citations[${index}].contentSha256`, { maxLength: 64 }),
            }
        }),
    }
}
