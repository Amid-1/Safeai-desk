// frontend/src/features/audit/ui/auditEventPresentation.contract.test.ts

import { describe, expect, it } from 'vitest'
import { AUDIT_EVENT_TYPES, getAuditEventTypeLabel } from '../constants/auditEvents'
import { auditEventResult } from './auditEventPresentation'
import { groupAuditEventTypes } from './auditEventGroups'

describe('Контракт событий журнала', () => {
    it('локализует каждый известный тип и не скрывает неизвестный', () => {
        expect(new Set(AUDIT_EVENT_TYPES).size).toBe(AUDIT_EVENT_TYPES.length)
        for (const type of AUDIT_EVENT_TYPES) {
            expect(getAuditEventTypeLabel(type)).not.toBe(type)
            expect(getAuditEventTypeLabel(type)).toMatch(/[А-Яа-яЁё]/)
        }
        expect(getAuditEventTypeLabel('FUTURE_SERVER_EVENT')).toBe('FUTURE_SERVER_EVENT')
    })

    it('показывает успешное восстановление outbox отдельно от ошибок', () => {
        expect(auditEventResult('AUDIT_OUTBOX_DEAD_LETTER_RECOVERED'))
            .toEqual({ label: 'Восстановлено', tone: 'success' })
        expect(auditEventResult('AI_RESPONSE_FAILED').tone).toBe('danger')
        expect(auditEventResult('MODEL_ROUTE_DENIED').tone).toBe('danger')
    })

    it.each(['CHAT_CONTENT_ERASURE', 'KNOWLEDGE_BASE_DELETION', 'KNOWLEDGE_DOCUMENT_DELETION'])(
        'различает запрос, ограничение хранения, проверку и завершение: %s',
        prefix => {
            expect(auditEventResult(prefix + '_REQUESTED').tone).toBe('neutral')
            expect(auditEventResult(prefix + '_RETENTION_BLOCKED').tone).toBe('warning')
            expect(auditEventResult(prefix + '_REVIEW_REQUIRED').tone).toBe('warning')
            expect(auditEventResult(prefix + '_COMPLETED').tone).toBe('success')
        },
    )

    it('поиск по русскому названию сохраняет точный серверный код', () => {
        expect(groupAuditEventTypes(AUDIT_EVENT_TYPES, 'удаление базы знаний', '')
            .flatMap(group => group.types)).toContain('KNOWLEDGE_BASE_DELETION_REQUESTED')
    })
})
