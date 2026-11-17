// frontend/src/features/models/ui/catalogVersion/catalogRubEquivalent.test.ts
import { describe, expect, it } from 'vitest'
import { catalogRubEquivalent } from './catalogRubEquivalent'

describe('catalogRubEquivalent — справочное отображение', () => {
    it('переводит USD в RUB без потери точности и округляет только отображение', () => {
        expect(catalogRubEquivalent('2.5', '82.5143')).toBe('206,29 ₽')
        expect(catalogRubEquivalent('0.25', '82.5143')).toBe('20,63 ₽')
        expect(catalogRubEquivalent('3.125', '82.5143')).toBe('257,86 ₽')
        expect(catalogRubEquivalent('10', '82.5143')).toBe('825,14 ₽')
        expect(catalogRubEquivalent('9007199254740993', '1')).toMatch(/9\s007\s199\s254\s740\s993,00 ₽/)
    })
    it('отличает неизвестную стоимость, ноль и сумму меньше копейки', () => {
        expect(catalogRubEquivalent('', '82.5143')).toBeNull()
        expect(catalogRubEquivalent('0', '82.5143')).toBe('0,00 ₽')
        expect(catalogRubEquivalent('0.000000000001', '82.5143')).toBe('< 0,01 ₽')
        expect(catalogRubEquivalent('2e3', '82.5143')).toBeNull()
        expect(catalogRubEquivalent('1', '0')).toBeNull()
    })
})
