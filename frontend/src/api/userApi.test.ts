import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    parseUser,
    parseUserStatistics,
    updateUserRoles,
} from './userApi'
import { apiRequest } from './http'

vi.mock('./http', async (importOriginal) => {
    const actual =
        await importOriginal<
            typeof import('./http')
        >()

    return {
        ...actual,
        apiRequest: vi.fn(),
    }
})

const requestMock =
    vi.mocked(apiRequest)

const USER = {
    id: '11111111-1111-1111-1111-111111111111',
    organizationId:
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    email: 'user@safeai.test',
    fullName: 'User',
    enabled: true,
    roles: ['USER'],
    version: 5,
    createdAt:
        '2026-08-04T10:00:00Z',
    updatedAt:
        '2026-08-04T10:00:01Z',
    lastLoginAt: null,
}

describe('userApi', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('runtime parser отклоняет неизвестную роль', () => {
        expect(() =>
            parseUser({
                ...USER,
                roles: ['OWNER'],
            }),
        ).toThrow(
            'неизвестное значение',
        )
    })

    it('runtime parser отклоняет неканонический email', () => {
        expect(() =>
            parseUser({
                ...USER,
                email:
                    ' USER@SAFEAI.TEST ',
            }),
        ).toThrow(
            'не канонизирован',
        )
    })

    it('roles mutation передаёт expectedVersion', async () => {
        requestMock.mockResolvedValue({
            ...USER,
            roles: [
                'USER',
                'ADMIN',
            ],
            version: 6,
        })

        await updateUserRoles(
            USER.id,
            {
                roles: [
                    'USER',
                    'ADMIN',
                ],
                expectedVersion: 5,
            },
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                `/api/users/${USER.id}/roles`,
                expect.objectContaining({
                    method: 'PATCH',
                    json: {
                        roles: [
                            'USER',
                            'ADMIN',
                        ],
                        expectedVersion: 5,
                    },
                }),
            )
    })

    it('statistics отклоняет несогласованные totals', () => {
        expect(() =>
            parseUserStatistics({
                total: 1,
                administrators: 0,
                users: 1,
                enabled: 1,
                disabled: 1,
            }),
        ).toThrow(
            'несогласованные',
        )
    })
})
