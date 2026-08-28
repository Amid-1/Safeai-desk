import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    parseKnowledgeDocument,
    parseKnowledgeDocumentVersion,
} from './knowledgeDocumentApi'

const DOCUMENT = {
    id: '0d3df3e5-83ec-4d4f-a0d8-e04da9a3d72c',
    knowledgeBaseId: '346859da-4182-4625-933f-fae9714f07f9',
    name: 'runbook.txt',
    enabled: true,
    version: 0,
    currentVersionId: '38b67b2f-5f44-44a2-bb53-d5545da08dd2',
    versionNumber: 1,
    originalFilename: 'runbook.txt',
    mediaType: 'text/plain',
    sizeBytes: 128,
    status: 'CHUNKING',
    createdAt: '2026-08-22T18:00:00Z',
    updatedAt: '2026-08-22T18:01:00Z',
}

describe('knowledgeDocumentApi contract', () => {
    it('принимает durable-ingestion статус CHUNKING от backend', () => {
        expect(
            parseKnowledgeDocument(DOCUMENT).status,
        ).toBe('CHUNKING')
    })

    it('принимает immutable evidence исторической версии', () => {
        expect(parseKnowledgeDocumentVersion({
            id: DOCUMENT.currentVersionId,
            documentId: DOCUMENT.id,
            versionNumber: 1,
            originalFilename: 'runbook.txt',
            mediaType: 'text/plain',
            sizeBytes: 128,
            sha256: 'not-a-real-hash',
            createdByUserId: '3ca0cd4f-e6eb-40a5-aefc-2803773a0b5c',
            createdAt: DOCUMENT.createdAt,
        }).sha256).toBe('not-a-real-hash')
    })
})
