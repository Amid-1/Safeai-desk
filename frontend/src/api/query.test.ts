import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    buildQueryString,
    normalizePage,
    normalizePageSize,
    pathSegment,
    uuidPathSegment,
} from './query'

describe('query utilities', () => {
    it('пропускает undefined, null и пустую строку', () => {
        expect(
            buildQueryString({
                a: undefined,
                b: null,
                c: '',
            }),
        ).toBe('')
    })

    it('сохраняет 0 и false', () => {
        expect(
            buildQueryString({
                enabled: false,
                page: 0,
            }),
        ).toBe('?enabled=false&page=0')
    })

    it('кодирует пробелы, &, = и Unicode', () => {
        expect(
            buildQueryString({
                search: 'Иван & user=1',
            }),
        ).toBe(
            '?search=%D0%98%D0%B2%D0%B0%D0%BD+%26+user%3D1',
        )
    })

    it.each([
        Number.NaN,
        Number.POSITIVE_INFINITY,
        Number.NEGATIVE_INFINITY,
    ])('отклоняет неконечное число %s', (value) => {
        expect(() =>
            buildQueryString({
                value,
            }),
        ).toThrow(
            'должен быть конечным числом',
        )
    })

    it('нормализует отрицательную page в 0', () => {
        expect(normalizePage(-10)).toBe(0)
    })

    it('округляет дробную page вниз', () => {
        expect(normalizePage(7.9)).toBe(7)
    })

    it('ограничивает size диапазоном', () => {
        expect(normalizePageSize(0)).toBe(1)
        expect(normalizePageSize(500)).toBe(200)
    })

    it('безопасно нормализует defaultSize и maxSize', () => {
        expect(
            normalizePageSize(
                Number.NaN,
                -10,
                0,
            ),
        ).toBeGreaterThanOrEqual(1)
    })

    it('кодирует обычный path segment', () => {
        expect(
            pathSegment('Иван Иванов'),
        ).toBe(
            '%D0%98%D0%B2%D0%B0%D0%BD%20%D0%98%D0%B2%D0%B0%D0%BD%D0%BE%D0%B2',
        )
    })

    it.each([
        '.',
        '..',
        '/',
        '\\',
        '',
    ])('отклоняет небезопасный segment %s', (value) => {
        expect(() =>
            pathSegment(value),
        ).toThrow()
    })

    it('принимает валидный UUID', () => {
        expect(
            uuidPathSegment(
                'AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA',
            ),
        ).toBe(
            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        )
    })

    it('отклоняет некорректный UUID', () => {
        expect(() =>
            uuidPathSegment('not-a-uuid'),
        ).toThrow(
            'Некорректный UUID',
        )
    })
})
