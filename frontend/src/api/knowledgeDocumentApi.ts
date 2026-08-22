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

const STATUSES = ['PENDING', 'VALIDATING', 'EXTRACTING', 'READY', 'FAILED'] as const
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

export async function uploadKnowledgeDocument(kb: string, file: File, name: string): Promise<KnowledgeDocument> {
    const form = new FormData();
    form.append('file', file);
    if (name.trim()) form.append('name', name.trim())
    return parseKnowledgeDocument(await apiRequest<unknown>(path(kb), {
        method: 'POST',
        body: form,
        timeoutMs: API_TIMEOUTS.default
    }), 'document')
}

export async function uploadKnowledgeDocumentVersion(kb: string, documentId: string, file: File): Promise<KnowledgeDocument> {
    const form = new FormData();
    form.append('file', file)
    return parseKnowledgeDocument(await apiRequest<unknown>(`${path(kb)}/${uuidPathSegment(documentId)}/versions`, {
        method: 'POST',
        body: form,
        timeoutMs: API_TIMEOUTS.default
    }), 'document')
}

export function knowledgeDocumentDownloadUrl(kb: string, documentId: string): string {
    return `${path(kb)}/${uuidPathSegment(documentId)}/download`
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

export function parseKnowledgeDocument(value: unknown, field = 'document'): KnowledgeDocument {
    const r = expectRecord(value, field);
    const nullableUuid = (v: unknown, n: string) => v == null ? null : expectUuid(v, n);
    const nullableInt = (v: unknown, n: string) => v == null ? null : expectNonNegativeInteger(v, n);
    const nullableStatus = (v: unknown, n: string) => v == null ? null : expectEnum(v, n, STATUSES)
    return {
        id: expectUuid(r.id, `${field}.id`),
        knowledgeBaseId: expectUuid(r.knowledgeBaseId, `${field}.knowledgeBaseId`),
        name: expectString(r.name, `${field}.name`, {maxLength: 255}),
        enabled: expectBoolean(r.enabled, `${field}.enabled`),
        version: expectNonNegativeInteger(r.version, `${field}.version`),
        currentVersionId: nullableUuid(r.currentVersionId, `${field}.currentVersionId`),
        versionNumber: nullableInt(r.versionNumber, `${field}.versionNumber`),
        originalFilename: expectNullableString(r.originalFilename, `${field}.originalFilename`, {maxLength: 255}),
        mediaType: expectNullableString(r.mediaType, `${field}.mediaType`, {maxLength: 127}),
        sizeBytes: expectNonNegativeInteger(r.sizeBytes, `${field}.sizeBytes`),
        status: nullableStatus(r.status, `${field}.status`),
        createdAt: expectInstant(r.createdAt, `${field}.createdAt`),
        updatedAt: expectInstant(r.updatedAt, `${field}.updatedAt`)
    }
}
