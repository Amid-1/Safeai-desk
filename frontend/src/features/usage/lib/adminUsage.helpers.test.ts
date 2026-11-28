// frontend/src/features/usage/lib/adminUsage.helpers.test.ts

import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    buildUsageSearchParams,
    readUsageUrlState,
    toUsageFilter,
} from './adminUsage.helpers'

describe('admin usage helpers', () => {
    it('round trips budget status only for a platform organization report', () => {
        const state = readUsageUrlState('?report=organizations&budgetStatus=UNKNOWN', true)
        expect(state.budgetStatus).toBe('UNKNOWN')
        expect(buildUsageSearchParams({...state.draft, tab: state.tab, page: 2, budgetStatus: state.budgetStatus}).get('budgetStatus')).toBe('UNKNOWN')
        expect(readUsageUrlState('?report=organizations&budgetStatus=UNKNOWN', false).budgetStatus).toBeUndefined()
        expect(readUsageUrlState('?report=users&budgetStatus=UNKNOWN', true).budgetStatus).toBeUndefined()
        expect(readUsageUrlState('?report=organizations&budgetStatus=bogus', true).budgetStatus).toBeUndefined()
        expect(readUsageUrlState('?report=organizations&budgetStatus=UNKNOWN&organizationId=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', true).budgetStatus).toBeUndefined()
    })
    it('по умолчанию показывает явный 30-дневный UTC диапазон', () => {
        const state = readUsageUrlState(
            '',
            true,
            new Date('2026-08-04T12:00:00Z'),
        )

        expect(state.draft.dateFrom)
            .toBe('2026-07-06')
        expect(state.draft.dateTo)
            .toBe('2026-08-04')
    })

    it('dateTo преобразуется в exclusive UTC boundary', () => {
        const result = toUsageFilter(
            {
                dateFrom: '2026-08-01',
                dateTo: '2026-08-04',
                model: 'mock-safeai',
                organizationId: '',
            },
            true,
        )

        expect(result.filter.dateFrom)
            .toBe('2026-08-01T00:00:00.000Z')
        expect(result.filter.dateTo)
            .toBe('2026-08-05T00:00:00.000Z')
    })

    it('отклоняет невозможную дату и диапазон больше 366 дней', () => {
        expect(() =>
            toUsageFilter(
                {
                    dateFrom: '2025-01-01',
                    dateTo: '2026-01-02',
                    model: '',
                    organizationId: '',
                },
                true,
            ),
        ).toThrow('не должен превышать 366')

        expect(() =>
            toUsageFilter(
                {
                    dateFrom: '2026-02-30',
                    dateTo: '2026-03-02',
                    model: '',
                    organizationId: '',
                },
                true,
            ),
        ).toThrow('Некорректная')
    })

    it('ADMIN не может восстановить organizationId из URL', () => {
        const state = readUsageUrlState(
            '?organizationId=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            false,
            new Date('2026-08-04T12:00:00Z'),
        )

        expect(state.draft.organizationId)
            .toBe('')
    })

    it('битый URL не роняет страницу и сбрасывает опасные значения', () => {
        const state = readUsageUrlState(
            '?dateFrom=2026-02-30&dateTo=2026-03-01&model=%00bad&organizationId=not-a-uuid',
            true,
            new Date('2026-08-04T12:00:00Z'),
        )

        expect(state.draft.dateFrom)
            .toBe('2026-07-06')
        expect(state.draft.dateTo)
            .toBe('2026-08-04')
        expect(state.draft.model)
            .toBe('')
        expect(state.draft.organizationId)
            .toBe('')
    })

    it('сохраняет выбранную модель в URL при переключении вкладок', () => {
        const params = buildUsageSearchParams({
            tab: 'users',
            page: 0,
            dateFrom: '2026-08-01',
            dateTo: '2026-08-04',
            model: 'gpt-example',
            organizationId: '',
        })

        expect(params.get('model'))
            .toBe('gpt-example')
    })

    it('validates and restores the subject UUID without changing tenant scope', () => {
        const userId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
        const values = {
            tab: 'daily' as const, page: 0, dateFrom: '2026-11-01', dateTo: '2026-11-16',
            model: 'mock-safeai', organizationId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', userId,
        }
        const state = readUsageUrlState(buildUsageSearchParams(values).toString(), false)
        expect(state.draft.userId).toBe(userId)
        expect(state.draft.organizationId).toBe('')
        expect(toUsageFilter(state.draft, false).filter.userId).toBe(userId)
        expect(readUsageUrlState('userId=bad-id', false).draft.userId).toBeUndefined()
    })
})
