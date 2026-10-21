// frontend/src/features/usage/lib/usagePanelHeight.test.ts
import { describe, expect, it } from 'vitest'
import { autoFitUsageHeight } from './usagePanelHeight'

describe('usage results auto-fit', () => {
    it.each(['summary', 'users', 'models'] as const)(
        'fits the measured table and KPI height on %s', tab => {
            expect(autoFitUsageHeight(456.1, tab)).toBe(457)
            expect(autoFitUsageHeight(null, tab)).toBe(450)
            expect(autoFitUsageHeight(100, tab)).toBe(370)
        },
    )

    it('reserves chart space for daily without demanding an oversized pane', () => {
        expect(autoFitUsageHeight(null, 'daily')).toBe(520)
        expect(autoFitUsageHeight(720, 'daily')).toBe(590)
        expect(autoFitUsageHeight(100, 'daily')).toBe(490)
    })

    it('rejects non-finite measurement and never exceeds the soft ceiling', () => {
        expect(autoFitUsageHeight(NaN, 'users')).toBe(450)
        expect(autoFitUsageHeight(Infinity, 'models')).toBe(450)
        expect(autoFitUsageHeight(9999, 'summary')).toBe(590)
    })
})
