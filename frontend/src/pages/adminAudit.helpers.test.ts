import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    applyAuditDatePreset,
    readAuditUrlState,
    toAuditEventFilter,
} from './adminAudit.helpers'
import {
    createDefaultAuditDraftFilter,
} from '../components/admin/audit/types'

describe('admin audit filters', () => {
    it('по умолчанию использует последние 30 локальных дней', () => {
        const filter =
            createDefaultAuditDraftFilter(
                new Date(
                    2026,
                    7,
                    4,
                    12,
                ),
            )

        expect(filter).toEqual({
            eventType: '',
            actorUserId: '',
            actorEmail: '',
            dateFrom: '2026-07-06',
            dateTo: '2026-08-04',
            targetOrganizationId: '',
        })
    })

    it('actor organization не сравнивается с target organization', () => {
        const result =
            toAuditEventFilter(
                {
                    eventType:
                        'ORGANIZATION_ENABLED_CHANGED',
                    actorUserId:
                        '11111111-1111-1111-1111-111111111111',
                    actorEmail:
                        'platform-admin@safeai.test',
                    dateFrom:
                        '2026-08-01',
                    dateTo:
                        '2026-08-04',
                    targetOrganizationId:
                        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                },
                true,
            )

        expect(
            result.filter.actorUserId,
        ).toBe(
            '11111111-1111-1111-1111-111111111111',
        )
        expect(
            result.filter
                .targetOrganizationId,
        ).toBe(
            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        )
    })

    it('invalid URL не ломает страницу и возвращает bounded default', () => {
        const result =
            readAuditUrlState(
                '?actorUserId=../bad'
                + '&dateFrom=wrong'
                + '&dateTo=also-wrong',
                true,
                new Date(
                    2026,
                    7,
                    4,
                ),
            )

        expect(
            result.draftFilter
                .actorUserId,
        ).toBe('')
        expect(
            result.draftFilter
                .dateFrom,
        ).toBe('2026-07-06')
    })

    it('preset yesterday использует один полный локальный день', () => {
        const current =
            createDefaultAuditDraftFilter(
                new Date(
                    2026,
                    7,
                    4,
                ),
            )

        const result =
            applyAuditDatePreset(
                current,
                'yesterday',
                new Date(
                    2026,
                    7,
                    4,
                ),
            )

        expect(result.dateFrom)
            .toBe('2026-08-03')
        expect(result.dateTo)
            .toBe('2026-08-03')
    })
})
