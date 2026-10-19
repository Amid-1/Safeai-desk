import { describe, expect, it } from 'vitest'
import { getAuditEventGroup, groupAuditEventTypes } from './auditEventGroups'

describe('audit event search grouping', () => {
    it('groups by presentation without rewriting the server event code', () => {
        expect(getAuditEventGroup('USER_LOGIN_FAILED')).toBe('Авторизация и безопасность')
        expect(getAuditEventGroup('USER_ROLES_CHANGED')).toBe('Пользователи и организации')
        expect(getAuditEventGroup('KNOWLEDGE_DOCUMENT_DOWNLOADED')).toBe('Базы знаний и документы')
        expect(getAuditEventGroup('MODEL_ROUTE_DENIED')).toBe('AI, чаты и модели')
        expect(getAuditEventGroup('AUDIT_OUTBOX_DEAD_LETTER_RECOVERED')).toBe('Системные события')
    })

    it('searches Russian labels and technical identifiers', () => {
        const all = ['USER_LOGIN_FAILED', 'KNOWLEDGE_DOCUMENT_DOWNLOADED', 'USER_UPDATED']
        expect(groupAuditEventTypes(all, 'Скачан документ', '').flatMap(x => x.types))
            .toEqual(['KNOWLEDGE_DOCUMENT_DOWNLOADED'])
        expect(groupAuditEventTypes(all, 'USER_LOGIN', '').flatMap(x => x.types))
            .toEqual(['USER_LOGIN_FAILED'])
    })

    it('keeps an already selected code when the searchable directory changes', () => {
        const groups = groupAuditEventTypes(['USER_CREATED'], 'created', 'MODEL_ROUTE_DENIED')
        expect(groups.flatMap(x => x.types)).toContain('MODEL_ROUTE_DENIED')
        expect(groups.flatMap(x => x.types)).toContain('USER_CREATED')
    })

    it('deduplicates source types and preserves unknown events', () => {
        const grouped = groupAuditEventTypes(['EXTERNAL_X', 'EXTERNAL_X'], '', '')
        expect(grouped.flatMap(x => x.types)).toEqual(['EXTERNAL_X'])
    })
})
