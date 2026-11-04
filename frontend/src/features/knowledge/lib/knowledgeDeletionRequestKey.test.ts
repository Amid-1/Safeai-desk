import {beforeEach, describe, expect, it} from 'vitest'
import {getKnowledgeDeletionRequestKey, clearKnowledgeDeletionRequestKey}
    from './knowledgeDeletionRequestKey'

const BASE = '11111111-1111-4111-8111-111111111111'
const DOCUMENT = '22222222-2222-4222-8222-222222222222'

beforeEach(() => {
    window.sessionStorage.clear()
    // Сбрасываем также память модуля: тесты не зависят от порядка запуска.
    clearKnowledgeDeletionRequestKey('document', BASE, DOCUMENT, 3)
    clearKnowledgeDeletionRequestKey('document', BASE, DOCUMENT, 4)
    clearKnowledgeDeletionRequestKey('base', BASE, BASE, 3)
})

describe('ключи идемпотентности удаления', () => {
    it('повтор с теми же объектом и версией сохраняет ключ', () => {
        const a = getKnowledgeDeletionRequestKey('document', BASE, DOCUMENT, 3)
        expect(getKnowledgeDeletionRequestKey('document', BASE, DOCUMENT, 3)).toBe(a)
        expect(window.sessionStorage.length).toBe(1)
        clearKnowledgeDeletionRequestKey('document', BASE, DOCUMENT, 3)
        expect(window.sessionStorage.length).toBe(0)
    })
    it('разделяет базы, документы и версии для защиты от ошибочного replay', () => {
        const first = getKnowledgeDeletionRequestKey('document', BASE, DOCUMENT, 3)
        expect(getKnowledgeDeletionRequestKey('document', BASE, DOCUMENT, 4)).not.toBe(first)
        expect(getKnowledgeDeletionRequestKey('base', BASE, BASE, 3)).not.toBe(first)
    })
    it('восстанавливает существующий ключ из sessionStorage при повторном открытии', () => {
        const persisted = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
        window.sessionStorage.setItem(
            `safeai:knowledge-erasure:v1:document:${BASE}:${DOCUMENT}:3`, persisted)
        expect(getKnowledgeDeletionRequestKey('document', BASE, DOCUMENT, 3)).toBe(persisted)
    })
    it('отклоняет некорректные идентификаторы вместо небезопасного ключа', () => {
        expect(() => getKnowledgeDeletionRequestKey('document', 'fake', DOCUMENT, 3)).toThrow()
        expect(() => getKnowledgeDeletionRequestKey('document', BASE, DOCUMENT, -1)).toThrow()
    })
})
