// frontend/src/shared/api/httpErrors.contract.test.ts
import { describe, expect, it } from 'vitest'
import { ApiError, getApiErrorMessage, parseRetryAfter, rateLimitRetrySeconds } from './httpErrors'

describe('Публичный HTTP-контракт production', () => {
    it('не показывает серверные детали даже под видом ошибки поля', () => {
        const error = new ApiError('database secret', {
            code: 'VALIDATION_ERROR', fieldErrors: { secret: ['internal details'] },
            requestId: 'safe-id',
        }, 503)
        expect(getApiErrorMessage(error, 'Недоступно')).toBe('Недоступно Номер обращения: safe-id')
    })

    it('не отображает неподдерживаемый идентификатор обращения', () => {
        const error = new ApiError('secret', { requestId: 'private\nsecret' }, 500)
        expect(getApiErrorMessage(error, 'Ошибка')).toBe('Ошибка')
    })

    it.each(['-1', 'tomorrow', 'Infinity', '999999999999999999999', '2e3'])('отклоняет %s', value => {
        expect(parseRetryAfter(value)).toBeUndefined()
    })

    it('применяет безопасную задержку без корректного Retry-After', () => {
        for (const retryAfterSeconds of [undefined, Infinity, -1, NaN]) {
            expect(rateLimitRetrySeconds(new ApiError('secret', { retryAfterSeconds }, 429))).toBe(30)
        }
        expect(rateLimitRetrySeconds(new ApiError('secret', { retryAfterSeconds: 65 }, 429))).toBe(65)
    })

    it('различает превышение лимита сервиса и недоступность защиты', () => {
        const limited = new ApiError('private', { code: 'RATE_LIMIT_EXCEEDED' }, 429)
        const unavailable = new ApiError('private', { code: 'RATE_LIMIT_UNAVAILABLE' }, 503)
        expect(getApiErrorMessage(limited, 'Ошибка')).toContain('Слишком много запросов')
        expect(getApiErrorMessage(unavailable, 'Ошибка')).toContain('Сервис временно недоступен')
    })
})
