// ============================================================
// frontend/src/entities/user/api/userApi.test.ts
// Проверки контрактов чтения, фильтрации и изменения пользователей.
// ============================================================
import {
    beforeEach,
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import {
    getUsers,
    getUserDetails,
    parseUser,
    parseUserDetails,
    parseUserStatistics,
    updateUserRoles,
    searchUsers,
} from './userApi'
import { apiRequest } from '../../../shared/api/http'

vi.mock('../../../shared/api/http', async (importOriginal) => {
    const actual =
        await importOriginal<
            typeof import('../../../shared/api/http')
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

    it('сохраняет публичный getUsers: endpoint, роль, лимит и AbortSignal', async () => {
        const controller = new AbortController()
        requestMock.mockResolvedValue({
            content: [{ ...USER, roles: ['ADMIN'] }],
            page: 2,
            size: 200,
            totalElements: 401,
            totalPages: 3,
        })

        const result = await getUsers(
            2,
            999,
            'ADMIN',
            { signal: controller.signal },
        )

        expect(result.content).toHaveLength(1)
        expect(result.totalPages).toBe(3)
        expect(requestMock).toHaveBeenCalledOnce()
        expect(requestMock).toHaveBeenCalledWith(
            '/api/users?page=2&role=ADMIN&size=200',
            expect.objectContaining({
                method: 'GET',
                signal: controller.signal,
            }),
        )
    })

    it('getUsers не добавляет необязательную роль по умолчанию', async () => {
        requestMock.mockResolvedValue({
            content: [],
            page: 0,
            size: 50,
            totalElements: 0,
            totalPages: 0,
        })

        const result = await getUsers()

        expect(result.content).toEqual([])
        expect(requestMock).toHaveBeenCalledWith(
            '/api/users?page=0&size=50',
            expect.objectContaining({ method: 'GET' }),
        )
    })

    it('getUsers отклоняет некорректные роли в ответе сервера', async () => {
        requestMock.mockResolvedValue({
            content: [{ ...USER, roles: ['OWNER'] }],
            page: 0,
            size: 50,
            totalElements: 1,
            totalPages: 1,
        })

        await expect(getUsers()).rejects.toThrow('неизвестное значение')
    })

    it('getUserDetails возвращает расширенный контракт с organizationName', async () => {
        requestMock.mockResolvedValue({
            ...USER,
            organizationName: 'Арендатор',
        })

        const details = await getUserDetails(USER.id)

        expect(details.organizationName).toBe('Арендатор')
        expect(details.id).toBe(USER.id)
        expect(requestMock).toHaveBeenCalledWith(
            `/api/users/${USER.id}`,
            expect.objectContaining({ method: 'GET' }),
        )
    })

    it('searches server-side before pagination with status and role filters', async () => {
        requestMock.mockResolvedValue({
            content: [USER], page: 0, size: 50,
            totalElements: 101, totalPages: 3,
        })
        const result = await searchUsers(0, 50, {
            query: 'user', role: 'USER', enabled: false,
        })
        expect(result.totalElements).toBe(101)
        expect(requestMock).toHaveBeenCalledWith(
            '/api/users/search?enabled=false&page=0&query=user&role=USER&size=50',
            expect.objectContaining({method: 'GET'}),
        )
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

    it('runtime parser отклоняет несколько ролей', () => {
        expect(() =>
            parseUser({
                ...USER,
                roles: [
                    'USER',
                    'ADMIN',
                ],
            }),
        ).toThrow(
            'ровно одну системную роль',
        )
    })

    it('runtime parser отклоняет пустой набор ролей', () => {
        expect(() =>
            parseUser({
                ...USER,
                roles: [],
            }),
        ).toThrow(
            'ровно одну системную роль',
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

    it('details parser принимает название организации', () => {
        const details =
            parseUserDetails({
                ...USER,
                organizationName:
                    'ZIL Production',
            })

        expect(
            details.organizationName,
        ).toBe(
            'ZIL Production',
        )
    })

    it('details parser допускает старый ответ без названия организации', () => {
        const details =
            parseUserDetails(USER)

        expect(
            details.organizationName,
        ).toBeNull()
    })

    it('details parser отклоняет ненормализованное название организации', () => {
        expect(() =>
            parseUserDetails({
                ...USER,
                organizationName:
                    ' ZIL Production ',
            }),
        ).toThrow(
            'organizationName не нормализован',
        )
    })

    it('roles mutation передаёт одну роль и expectedVersion', async () => {
        requestMock.mockResolvedValue({
            ...USER,
            roles: ['ADMIN'],
            version: 6,
        })

        await updateUserRoles(
            USER.id,
            {
                roles: ['ADMIN'],
                expectedVersion: 5,
            },
        )

        expect(requestMock)
            .toHaveBeenCalledWith(
                `/api/users/${USER.id}/roles`,
                expect.objectContaining({
                    method: 'PATCH',
                    json: {
                        roles: ['ADMIN'],
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
