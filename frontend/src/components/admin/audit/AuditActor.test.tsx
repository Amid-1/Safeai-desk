
import {
    render,
    screen,
} from '@testing-library/react'
import {
    describe,
    expect,
    it,
} from 'vitest'
import type {
    AuditEvent,
} from '../../../api/adminApi'
import AuditActor from './AuditActor'

const BASE_EVENT: AuditEvent = {
    id:
        '22222222-2222-2222-2222-222222222222',

    targetOrganizationId:
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    targetOrganizationName:
        'Tenant snapshot',

    actorUserId: null,
    actorOrganizationId: null,
    actorEmail: null,
    actorDisplayName: null,

    eventType:
        'USER_UPDATED',
    details: {},
    detailsTruncated: false,
    detailsInvalid: false,

    createdAt:
        '2026-08-04T10:00:00Z',
}

describe('AuditActor', () => {
    it('полностью пустой actor отображается как система', () => {
        render(
            <AuditActor
                event={BASE_EVENT}
            />,
        )

        expect(
            screen.getByText('Система'),
        ).toBeInTheDocument()
    })

    it('SYSTEM snapshot отображается как система', () => {
        render(
            <AuditActor
                event={{
                    ...BASE_EVENT,
                    actorDisplayName:
                        'SYSTEM',
                }}
            />,
        )

        expect(
            screen.getByText('Система'),
        ).toBeInTheDocument()
    })

    it('actor ID без email и имени не называется системой', () => {
        render(
            <AuditActor
                event={{
                    ...BASE_EVENT,
                    actorUserId:
                        '11111111-1111-1111-1111-111111111111',
                }}
            />,
        )

        expect(
            screen.queryByText(
                'Система',
            ),
        ).not.toBeInTheDocument()

        expect(
            screen.getByText(
                /Исторический инициатор/,
            ),
        ).toBeInTheDocument()
    })

    it('email и имя выводятся раздельно', () => {
        render(
            <AuditActor
                event={{
                    ...BASE_EVENT,
                    actorUserId:
                        '11111111-1111-1111-1111-111111111111',
                    actorEmail:
                        'admin@test.com',
                    actorDisplayName:
                        'Admin Test',
                }}
            />,
        )

        expect(
            screen.getByText(
                'admin@test.com',
            ),
        ).toBeInTheDocument()

        expect(
            screen.getByText(
                'Admin Test',
            ),
        ).toBeInTheDocument()
    })
})
