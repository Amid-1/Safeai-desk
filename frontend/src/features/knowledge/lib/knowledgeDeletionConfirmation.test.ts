// frontend/src/features/knowledge/lib/knowledgeDeletionConfirmation.test.ts
import {describe, expect, it} from 'vitest'
import {matchesKnowledgeDeletionName} from './knowledgeDeletionConfirmation'

describe('Knowledge deletion typed confirmation', () => {
    it('accepts letter-case differences without changing the identity of the target', () => {
        expect(matchesKnowledgeDeletionName('  ЗАКРЫТАЯ БАЗА  ', 'Закрытая база')).toBe(true)
        expect(matchesKnowledgeDeletionName('ПРОЕКТ', 'Проект')).toBe(true)
    })

    it('never treats similar Latin/Cyrillic or numeral/Cyrillic glyphs as equal', () => {
        expect(matchesKnowledgeDeletionName('х3', 'X3')).toBe(false)
        expect(matchesKnowledgeDeletionName('ХЗ', 'X3')).toBe(false)
        expect(matchesKnowledgeDeletionName('X3', 'ХЗ')).toBe(false)
    })

    it('does not allow partial names, an empty expected name or a different name', () => {
        expect(matchesKnowledgeDeletionName('Закрытая', 'Закрытая база')).toBe(false)
        expect(matchesKnowledgeDeletionName('', '')).toBe(false)
        expect(matchesKnowledgeDeletionName('База 2', 'База 1')).toBe(false)
    })

    it('treats canonical Unicode composed/decomposed spellings as the same text', () => {
        expect(matchesKnowledgeDeletionName('Ёж', 'Ёж')).toBe(true)
    })
})
