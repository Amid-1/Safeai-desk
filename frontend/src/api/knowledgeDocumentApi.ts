import {API_TIMEOUTS, apiRequest} from './http'
import {uuidPathSegment, buildQueryString, normalizePage, normalizePageSize} from './query'
import {
    expectBoolean,
    expectEnum,
    expectInstant,
    expectNonNegativeInteger,
    expectNullableString,
    expectRecord,
    expectString,
    expectUuid,
    parsePageResponse
} from './runtime'
import type {PageResponse} from '../utils/page'

const STATUSES = ['PENDING', 'VALIDATING', 'EXTRACTING', 'CHUNKING', 'READY', 'FAILED'] as const
const HEALTH_STATES = ['EMPTY', 'HEALTHY', 'INDEXING', 'DEGRADED'] as const
export type KnowledgeIngestionStatus = typeof STATUSES[number]
export type KnowledgeHealthState = typeof HEALTH_STATES[number]
export type KnowledgeDocument = {
    id: string;
    knowledgeBaseId: string;
    name: string;
    enabled: boolean;
    version: number;
    currentVersionId: string | null;
    versionNumber: number | null;
    originalFilename: string | null;
    mediaType: string | null;
    sizeBytes: number;
    status: KnowledgeIngestionStatus | null;
    createdAt: string;
    updatedAt: string
}

export type KnowledgeDocumentMutationResult =
    Omit<
        KnowledgeDocument,
        'createdAt' | 'updatedAt'
    >

export type KnowledgeHealth = {
    knowledgeBaseId: string
    state: KnowledgeHealthState
    activeEmbeddingModel: string
    totalDocuments: number
    enabledDocuments: number
    searchableDocuments: number
    pendingDocuments: number
    processingDocuments: number
    failedDocuments: number
    staleEmbeddingDocuments: number
    activeChunks: number
    checkedAt: string
}

export type KnowledgeReindexResult = {
    knowledgeBaseId: string
    documentId: string
    documentVersionId: string
    ingestionJobId: string
    status: string
    requestedAt: string
}

const path = (kb: string) => `/api/knowledge-bases/${uuidPathSegment(kb)}/documents`

export async function getKnowledgeDocuments(kb: string, page = 0, size = 50, signal?: AbortSignal): Promise<PageResponse<KnowledgeDocument>> {
    const value = await apiRequest<unknown>(path(kb) + buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(size, 50, 100)
    }), {method: 'GET', signal, timeoutMs: API_TIMEOUTS.default})
    return parsePageResponse(value, parseKnowledgeDocument)
}

export async function uploadKnowledgeDocument(
    kb: string,
    file: File,
    name: string,
): Promise<KnowledgeDocumentMutationResult> {
    const form = new FormData()

    form.append('file', file)

    if (name.trim()) {
        form.append(
            'name',
            name.trim(),
        )
    }

    const value = await apiRequest<unknown>(
        path(kb),
        {
            method: 'POST',
            body: form,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    /*
     * POST — command response. Backend может вернуть документ сразу после
     * транзакции, когда DB-generated timestamps ещё не вошли в JSON.
     * Для подтверждения успешной mutation timestamps не нужны: canonical
     * read-model сразу перечитывается через getKnowledgeDocuments().
     */
    return parseKnowledgeDocumentMutationResult(
        value,
        'document',
    )
}

export async function uploadKnowledgeDocumentVersion(
    kb: string,
    documentId: string,
    file: File,
): Promise<KnowledgeDocumentMutationResult> {
    const form = new FormData()

    form.append('file', file)

    const value = await apiRequest<unknown>(
        `${path(kb)}/${uuidPathSegment(documentId)}/versions`,
        {
            method: 'POST',
            body: form,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseKnowledgeDocumentMutationResult(
        value,
        'document',
    )
}

export function knowledgeDocumentDownloadUrl(kb: string, documentId: string): string {
    return `${path(kb)}/${uuidPathSegment(documentId)}/download`
}

export async function fetchKnowledgeDocumentBlob(
    kb: string,
    documentId: string,
    signal?: AbortSignal,
): Promise<Blob> {
    return apiRequest<Blob>(
        knowledgeDocumentDownloadUrl(
            kb,
            documentId,
        ),
        {
            method: 'GET',
            signal,
            timeoutMs: API_TIMEOUTS.download,
            responseType: 'blob',
        },
    )
}

export async function getKnowledgeHealth(
    kb: string,
    signal?: AbortSignal,
): Promise<KnowledgeHealth> {
    const value = await apiRequest<unknown>(`${path(kb)}/health`, {
        method: 'GET',
        signal,
        timeoutMs: API_TIMEOUTS.default,
    })
    const record = expectRecord(value, 'knowledgeHealth')
    return {
        knowledgeBaseId: expectUuid(
            record.knowledgeBaseId,
            'knowledgeHealth.knowledgeBaseId',
        ),
        state: expectEnum(
            record.state,
            'knowledgeHealth.state',
            HEALTH_STATES,
        ),
        activeEmbeddingModel: expectString(
            record.activeEmbeddingModel,
            'knowledgeHealth.activeEmbeddingModel',
            {maxLength: 255},
        ),
        totalDocuments: expectNonNegativeInteger(
            record.totalDocuments,
            'knowledgeHealth.totalDocuments',
        ),
        enabledDocuments: expectNonNegativeInteger(
            record.enabledDocuments,
            'knowledgeHealth.enabledDocuments',
        ),
        searchableDocuments: expectNonNegativeInteger(
            record.searchableDocuments,
            'knowledgeHealth.searchableDocuments',
        ),
        pendingDocuments: expectNonNegativeInteger(
            record.pendingDocuments,
            'knowledgeHealth.pendingDocuments',
        ),
        processingDocuments: expectNonNegativeInteger(
            record.processingDocuments,
            'knowledgeHealth.processingDocuments',
        ),
        failedDocuments: expectNonNegativeInteger(
            record.failedDocuments,
            'knowledgeHealth.failedDocuments',
        ),
        staleEmbeddingDocuments: expectNonNegativeInteger(
            record.staleEmbeddingDocuments,
            'knowledgeHealth.staleEmbeddingDocuments',
        ),
        activeChunks: expectNonNegativeInteger(
            record.activeChunks,
            'knowledgeHealth.activeChunks',
        ),
        checkedAt: expectInstant(
            record.checkedAt,
            'knowledgeHealth.checkedAt',
        ),
    }
}

export async function reindexKnowledgeDocument(
    kb: string,
    documentId: string,
): Promise<KnowledgeReindexResult> {
    const value = await apiRequest<unknown>(
        `${path(kb)}/${uuidPathSegment(documentId)}/reindex`,
        {method: 'POST', timeoutMs: API_TIMEOUTS.default},
    )
    const record = expectRecord(value, 'knowledgeReindex')
    return {
        knowledgeBaseId: expectUuid(
            record.knowledgeBaseId,
            'knowledgeReindex.knowledgeBaseId',
        ),
        documentId: expectUuid(
            record.documentId,
            'knowledgeReindex.documentId',
        ),
        documentVersionId: expectUuid(
            record.documentVersionId,
            'knowledgeReindex.documentVersionId',
        ),
        ingestionJobId: expectUuid(
            record.ingestionJobId,
            'knowledgeReindex.ingestionJobId',
        ),
        status: expectString(
            record.status,
            'knowledgeReindex.status',
            {maxLength: 32},
        ),
        requestedAt: expectInstant(
            record.requestedAt,
            'knowledgeReindex.requestedAt',
        ),
    }
}

function parseKnowledgeDocumentCore(
    value: unknown,
    field: string,
): KnowledgeDocumentMutationResult {
    const record = expectRecord(
        value,
        field,
    )

    const nullableUuid = (
        nestedValue: unknown,
        nestedField: string,
    ) => nestedValue == null
        ? null
        : expectUuid(
            nestedValue,
            nestedField,
        )

    const nullableInt = (
        nestedValue: unknown,
        nestedField: string,
    ) => nestedValue == null
        ? null
        : expectNonNegativeInteger(
            nestedValue,
            nestedField,
        )

    const nullableStatus = (
        nestedValue: unknown,
        nestedField: string,
    ) => nestedValue == null
        ? null
        : expectEnum(
            nestedValue,
            nestedField,
            STATUSES,
        )

    return {
        id: expectUuid(
            record.id,
            `${field}.id`,
        ),
        knowledgeBaseId: expectUuid(
            record.knowledgeBaseId,
            `${field}.knowledgeBaseId`,
        ),
        name: expectString(
            record.name,
            `${field}.name`,
            {
                maxLength: 255,
            },
        ),
        enabled: expectBoolean(
            record.enabled,
            `${field}.enabled`,
        ),
        version: expectNonNegativeInteger(
            record.version,
            `${field}.version`,
        ),
        currentVersionId: nullableUuid(
            record.currentVersionId,
            `${field}.currentVersionId`,
        ),
        versionNumber: nullableInt(
            record.versionNumber,
            `${field}.versionNumber`,
        ),
        originalFilename:
            expectNullableString(
                record.originalFilename,
                `${field}.originalFilename`,
                {
                    maxLength: 255,
                },
            ),
        mediaType:
            expectNullableString(
                record.mediaType,
                `${field}.mediaType`,
                {
                    maxLength: 127,
                },
            ),
        sizeBytes: expectNonNegativeInteger(
            record.sizeBytes,
            `${field}.sizeBytes`,
        ),
        status: nullableStatus(
            record.status,
            `${field}.status`,
        ),
    }
}

export function parseKnowledgeDocumentMutationResult(
    value: unknown,
    field = 'document',
): KnowledgeDocumentMutationResult {
    return parseKnowledgeDocumentCore(
        value,
        field,
    )
}

export function parseKnowledgeDocument(
    value: unknown,
    field = 'document',
): KnowledgeDocument {
    const core =
        parseKnowledgeDocumentCore(
            value,
            field,
        )

    const record = expectRecord(
        value,
        field,
    )

    return {
        ...core,
        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
        updatedAt: expectInstant(
            record.updatedAt,
            `${field}.updatedAt`,
        ),
    }
}