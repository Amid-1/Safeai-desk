// frontend/src/pages/adminUsage.helpers.tests.ts
import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    readUsageUrlState,
    toUsageFilter,
} from './adminUsage.helpers'

describe('admin usage filters', () => {
    it('по умолчанию показывает явный 30-дневный UTC диапазон', () => {
        const state =
            readUsageUrlState(
                '',
                true,
                new Date(
                    '2026-08-04T12:00:00Z',
                ),
            )

        expect(state.draft.dateFrom)
            .toBe('2026-07-06')
        expect(state.draft.dateTo)
            .toBe('2026-08-04')
    })

    it('dateTo преобразуется в exclusive UTC boundary', () => {
        const result =
            toUsageFilter(
                {
                    dateFrom:
                        '2026-08-01',
                    dateTo:
                        '2026-08-04',
                    model:
                        'mock-safeai',
                    organizationId: '',
                },
                true,
            )

        expect(result.filter.dateFrom)
            .toBe(
                '2026-08-01T00:00:00.000Z',
            )
        expect(result.filter.dateTo)
            .toBe(
                '2026-08-05T00:00:00.000Z',
            )
    })

    it('отклоняет невозможную дату и диапазон больше 366 дней', () => {
        expect(() =>
            toUsageFilter(
                {
                    dateFrom:
                        '2025-01-01',
                    dateTo:
                        '2026-01-02',
                    model: '',
                    organizationId: '',
                },
                true,
            ),
        ).toThrow(
            'не должен превышать 366',
        )
    })

    it('ADMIN не может восстановить organizationId из URL', () => {
        const state =
            readUsageUrlState(
                '?organizationId=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                false,
                new Date(
                    '2026-08-04T12:00:00Z',
                ),
            )

        expect(
            state.draft.organizationId,
        ).toBe('')
    })
})
