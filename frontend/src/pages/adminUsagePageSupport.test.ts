import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    formatIsoDate,
    parsePage,
    parseTab,
    validateFilters,
} from './adminUsagePageSupport'

describe('admin usage page support', () => {
    it('formats an ISO LocalDate for the Russian UI', () => {
        expect(formatIsoDate('2026-09-26'))
            .toBe('26.09.2026')
    })

    it('does not crash the page when a non-daily row reaches the date formatter during a transitional render', () => {
        expect(formatIsoDate(undefined))
            .toBe('—')
        expect(formatIsoDate(null))
            .toBe('—')
        expect(formatIsoDate('not-a-date'))
            .toBe('—')
    })

    it('normalizes unsupported report tabs and page parameters', () => {
        expect(parseTab('daily')).toBe('daily')
        expect(parseTab('unknown')).toBe('summary')
        expect(parsePage('4')).toBe(4)
        expect(parsePage('-1')).toBe(0)
    })

    it('rejects invalid ranges and malformed organization ids', () => {
        expect(validateFilters(
            '2026-09-27',
            '2026-09-26',
            '',
        )).toContain('позже')

        expect(validateFilters(
            '2026-09-01',
            '2026-09-26',
            'not-a-uuid',
        )).toContain('UUID')
    })
})
