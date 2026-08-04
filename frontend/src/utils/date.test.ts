import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    addCalendarDays,
    assertDateRange,
    createRecentDateRange,
    getInclusiveDayCount,
    toLocalExclusiveEndOfDayIso,
    toLocalStartOfDayIso,
    toUtcExclusiveEndOfDayIso,
    toUtcStartOfDayIso,
} from './date'

describe('date utilities', () => {
    it('создаёт UTC start и exclusive end', () => {
        expect(
            toUtcStartOfDayIso(
                '2026-08-04',
            ),
        ).toBe(
            '2026-08-04T00:00:00.000Z',
        )

        expect(
            toUtcExclusiveEndOfDayIso(
                '2026-08-04',
            ),
        ).toBe(
            '2026-08-05T00:00:00.000Z',
        )
    })

    it('локальные границы строятся через локальные полуночи', () => {
        expect(
            toLocalStartOfDayIso(
                '2026-08-04',
            ),
        ).toBe(
            new Date(
                2026,
                7,
                4,
            ).toISOString(),
        )

        expect(
            toLocalExclusiveEndOfDayIso(
                '2026-08-04',
            ),
        ).toBe(
            new Date(
                2026,
                7,
                5,
            ).toISOString(),
        )
    })

    it('валидирует 29 февраля', () => {
        expect(
            addCalendarDays(
                '2024-02-29',
                1,
            ),
        ).toBe('2024-03-01')

        expect(() =>
            addCalendarDays(
                '2025-02-29',
                1,
            ),
        ).toThrow(
            'Некорректная календарная дата',
        )
    })

    it('dateFrom=dateTo включает полный один день', () => {
        expect(
            getInclusiveDayCount(
                '2026-08-04',
                '2026-08-04',
            ),
        ).toBe(1)
    })

    it('отклоняет диапазон более 366 дней', () => {
        expect(() =>
            assertDateRange(
                '2025-01-01',
                '2026-01-02',
                366,
            ),
        ).toThrow(
            'не должен превышать 366',
        )
    })

    it('создаёт явный 30-дневный UTC период', () => {
        expect(
            createRecentDateRange(
                30,
                'UTC',
                new Date(
                    '2026-08-04T12:00:00Z',
                ),
            ),
        ).toEqual({
            dateFrom: '2026-07-06',
            dateTo: '2026-08-04',
        })
    })
})
