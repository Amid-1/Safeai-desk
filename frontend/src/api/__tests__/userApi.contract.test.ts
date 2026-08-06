// ============================================================
// frontend/src/api/_tests_/userApi.contracy.test.ts
// ============================================================
import {
    describe,
    expect,
    it,
} from 'vitest'
import { parseUser } from '../userApi'

const VALID_USER = {
    id: '11111111-1111-4111-8111-111111111111',
    organizationId:
        '22222222-2222-4222-8222-222222222222',
    email: 'user@example.com',
    fullName: 'Test User',
    enabled: true,
    roles: ['USER'],
    version: 3,
    createdAt: '2026-08-06T10:00:00Z',
    updatedAt: '2026-08-06T10:00:00Z',
    lastLoginAt: null,
}

describe('parseUser role/version contract', () => {
    it('accepts exactly one role and a required version', () => {
        const user = parseUser(VALID_USER)

        expect(user.roles).toEqual(['USER'])
        expect(user.version).toBe(3)
    })

    it('rejects multiple roles from backend', () => {
        expect(() =>
            parseUser({
                ...VALID_USER,
                roles: [
                    'USER',
                    'ADMIN',
                ],
            })
        ).toThrow(
            'должен содержать ровно одну системную роль',
        )
    })

    it('rejects a response without optimistic-lock version', () => {
        expect(() =>
            parseUser({
                ...VALID_USER,
                version: undefined,
            })
        ).toThrow('user.version обязателен')
    })
})
