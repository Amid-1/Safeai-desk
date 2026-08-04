import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    utf8ByteLength,
    validatePassword,
} from './password'

describe('password policy', () => {
    it('считает UTF-8 bytes, а не JS length', () => {
        expect(
            utf8ByteLength('я'),
        ).toBe(2)
    })

    it('принимает валидный пароль', () => {
        expect(
            validatePassword(
                'SafeAI-Password1!',
            ),
        ).toBeNull()
    })

    it('отклоняет пароль больше 72 UTF-8 bytes', () => {
        expect(
            validatePassword(
                `${'я'.repeat(31)}Aa1!12345678`,
            ),
        ).toContain(
            '72 байт',
        )
    })

    it('кириллица не считается ASCII спецсимволом', () => {
        expect(
            validatePassword(
                'Password123я',
            ),
        ).toContain(
            'ASCII-спецсимвол',
        )
    })
})
