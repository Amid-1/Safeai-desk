// ============================================================
// frontend/src/api/authApi.test.ts
// ============================================================
import {
    describe,
    expect,
    it,
} from 'vitest'

import {
    parseAuthUser,
} from './authApi'

const BASE_USER = {
    id:
        '0e2c7bbb-4f75-49e6-9d1f-4c427913b9ca',
    organizationId:
        'e06d4947-2bc7-4bd8-9271-2821f9d9509b',
    email:
        'vladskol@mail.ru',
    enabled: true,
    roles: ['ADMIN'],
}

describe('parseAuthUser', () => {
    it('принимает отсутствующий fullName как null', () => {
        const user = parseAuthUser(
            BASE_USER,
        )

        expect(user.fullName)
            .toBeNull()

        expect(user.roles)
            .toEqual(['ADMIN'])
    })

    it('принимает явный fullName null', () => {
        const user = parseAuthUser({
            ...BASE_USER,
            fullName: null,
        })

        expect(user.fullName)
            .toBeNull()
    })

    it('сохраняет заполненный fullName', () => {
        const user = parseAuthUser({
            ...BASE_USER,
            fullName: 'Vlad Admin',
        })

        expect(user.fullName)
            .toBe('Vlad Admin')
    })

    it('отклоняет несколько системных ролей', () => {
        expect(() =>
            parseAuthUser({
                ...BASE_USER,
                roles: [
                    'USER',
                    'ADMIN',
                ],
            }),
        ).toThrow(
            'Сервер вернул некорректные данные пользователя',
        )
    })

    it('отклоняет неизвестную роль', () => {
        expect(() =>
            parseAuthUser({
                ...BASE_USER,
                roles: ['OWNER'],
            }),
        ).toThrow(
            'Сервер вернул некорректные данные пользователя',
        )
    })

    it('отклоняет disabled user в authenticated contract', () => {
        expect(() =>
            parseAuthUser({
                ...BASE_USER,
                enabled: false,
            }),
        ).toThrow(
            'Сервер вернул некорректные данные пользователя',
        )
    })
})
