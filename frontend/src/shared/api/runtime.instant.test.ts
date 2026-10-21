// frontend/src/shared/api/runtime.instant.test.ts
// Контракт ISO-8601 Instant: обязательный offset, реальные календарные даты, наносекунды.
import { describe, expect, it } from 'vitest'
import { expectInstant, expectNullableInstant } from './runtime'

describe('expectInstant — строгий ответ backend', () => {
    it.each([
        '2026-10-21T19:00:00Z',
        '2026-10-21T19:00:00.1Z',
        '2026-10-21T19:00:00.123456789Z',
        '2026-10-21T19:00:00+03:00',
        '2026-10-21T19:00:00.987654321-04:30',
        '2024-02-29T00:00:00Z',
        '0001-01-01T00:00:00Z',
        '0099-12-31T23:59:59Z',
    ])('принимает корректное значение %s без изменения байтов', (value) => {
        expect(expectInstant(value, 'createdAt')).toBe(value)
    })

    it.each([
        '2026-10-21',
        '2026-10-21T19:00:00',
        '2026-10-21T19:00Z',
        '2026-02-30T00:00:00Z',
        '2025-02-29T00:00:00Z',
        '2026-13-01T00:00:00Z',
        '2026-10-21T24:00:00Z',
        '2026-10-21T10:60:00Z',
        '2026-10-21T10:00:60Z',
        '2026-10-21T19:00:00.1234567890Z',
        '2026-10-21T19:00:00+18:30',
        '2026-10-21T19:00:00+19:00',
        '2026-10-21T19:00:00+03:60',
        '2026-10-21 19:00:00Z',
        '2026-10-21T19:00:00z',
    ])('отклоняет не-ISO или некорректный instant %s', (value) => {
        expect(() => expectInstant(value, 'createdAt')).toThrow('ISO-8601 Instant')
    })

    it('сохраняет nullable-контракт и запрещает неправильный тип', () => {
        expect(expectNullableInstant(null, 'updatedAt')).toBeNull()
        expect(() => expectInstant(42, 'createdAt')).toThrow()
    })
})
