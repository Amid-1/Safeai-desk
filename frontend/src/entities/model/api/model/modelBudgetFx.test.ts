// frontend/src/entities/model/api/model/modelBudgetFx.test.ts
import {describe, expect, it} from 'vitest'
import {budgetMoneyEqual, moscowBudgetDate, parseRubBudgetQuote, parseRubBudgetSnapshot, rubToUsd, usdToRub} from './modelBudgetFx'

const quote = {rubPerUsd: '90.0000', quoteDate: '2026-11-17', effectiveDate: '2026-11-14',
    fetchedAt: '2026-11-17T00:00:00Z', source: 'CBR'}

describe('RUB budget arithmetic and wire evidence', () => {
    it('uses exact floor at 12 digits; distinguishes zero and absence', () => {
        expect(rubToUsd('5', '90')).toBe('0.055555555555')
        expect(rubToUsd('10000', '90')).toBe('111.111111111111')
        expect(rubToUsd('0', '90')).toBe('0')
        expect(rubToUsd(null, '90')).toBeNull()
        expect(rubToUsd('0.000000000001', '90')).toBe('0')
        expect(budgetMoneyEqual('0', null)).toBe(false)
        expect(budgetMoneyEqual('1.00', '1')).toBe(true)
    })
    it('preserves exact USD across switching and rejects overflow', () => {
        expect(usdToRub('0.000000000001', '90')).toBe('0.00000000009')
        expect(rubToUsd(usdToRub('0.123456789012', '90.1234'), '90.1234')).toBe('0.123456789012')
        expect(() => usdToRub('999999999999999999', '90')).toThrow()
        expect(() => rubToUsd('1', '0')).toThrow()
    })
    it('checks date, source, precision and both canonical limits', () => {
        expect(moscowBudgetDate(new Date('2026-11-16T21:00:00Z'))).toBe('2026-11-17')
        expect(parseRubBudgetQuote(quote).rubPerUsd).toBe('90.0000')
        expect(() => parseRubBudgetQuote({...quote, source: 'unknown'})).toThrow()
        expect(() => parseRubBudgetQuote({...quote, effectiveDate: '2026-11-18'})).toThrow()
        expect(() => parseRubBudgetQuote({...quote, rubPerUsd: '0'})).toThrow()
        const snapshot = {...quote, maxRequestCostRub: '5', monthlyBudgetRub: null}
        expect(parseRubBudgetSnapshot(snapshot, '0.055555555555', null).maxRequestCostRub).toBe('5')
        expect(() => parseRubBudgetSnapshot(snapshot, '0.055555555556', null)).toThrow()
        expect(() => parseRubBudgetSnapshot(snapshot, '0.055555555555', '0')).toThrow()
    })
})
