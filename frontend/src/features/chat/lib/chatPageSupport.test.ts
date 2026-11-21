// frontend/src/features/chat/lib/chatPageSupport.test.ts
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PendingTurn, PendingTurnStatus } from './chatPage.helpers'
import type { ChatTurnStatus } from '../api/chatApi'
import {
    delay,
    failureMessage,
    getPendingLabel,
    getPendingShortLabel,
    getRetryAfterSeconds,
    isRequestAborted,
} from './chatPageSupport'
import { ApiError } from '../../../shared/api/http'

const pending = (
    status: PendingTurnStatus,
    retryAfterUntil: number | null = null,
): PendingTurn => ({
    chatId: 'chat',
    clientRequestId: 'request',
    optimisticMessageId: 'pending-request',
    content: 'hello',
    status,
    error: null,
    retryAfterUntil,
    knowledgeBaseId: null,
    knowledgeMode: 'GENERAL',
})

describe('chatPageSupport', () => {
    it.each([
        ['SENDING', 'Сообщение отправляется.', 'отправка'],
        ['PROCESSING', 'Запрос ещё обрабатывается.', 'обработка'],
        ['SEND_UNKNOWN', 'Сервер не подтвердил отправку.', 'статус неизвестен'],
        ['FAILED', 'AI-запрос завершился ошибкой.', 'ошибка'],
        ['AMBIGUOUS', 'Результат AI-вызова неоднозначен. Автоматический повтор запрещён.', 'неоднозначно'],
        ['RATE_LIMITED', 'Повтор разрешён с тем же clientRequestId.', 'лимит'],
        ['QUOTA_BLOCKED', 'Квота AI не позволяет выполнить запрос.', 'квота'],
        ['ACCESS_REVOKED', 'Доступ к чату был отозван.', 'доступ отозван'],
        ['IDEMPOTENCY_CONFLICT', 'Обнаружен конфликт ключа идемпотентности.', 'конфликт ID'],
    ] as const)('maps %s to consistent labels', (status, full, short) => {
        expect(getPendingLabel(pending(status), 1_000)).toBe(full)
        expect(getPendingShortLabel(pending(status))).toBe(short)
    })

    it('rounds the backoff up and never returns negative seconds', () => {
        const item = pending('RATE_LIMITED', 2_501)
        expect(getRetryAfterSeconds(item, 1_000)).toBe(2)
        expect(getPendingLabel(item, 1_000)).toBe('Повтор доступен через 2 сек.')
        expect(getRetryAfterSeconds(item, 4_000)).toBe(0)
        expect(getPendingLabel(item, 4_000)).toBe('Повтор разрешён с тем же clientRequestId.')
    })

    it('keeps errors explicit instead of silently treating them as success', () => {
        expect(failureMessage({ failureCode: 'MODEL_DENIED' } as ChatTurnStatus))
            .toBe('AI-запрос завершился ошибкой (MODEL_DENIED).')
        expect(failureMessage({ failureCode: null } as ChatTurnStatus))
            .toBe('AI-запрос завершился ошибкой.')
        expect(isRequestAborted(new Error('REQUEST_ABORTED'))).toBe(false)
        expect(isRequestAborted(
            new ApiError('Отменён', { code: 'REQUEST_ABORTED' }, 0),
        )).toBe(true)
        expect(isRequestAborted(
            new ApiError('Лимит', { code: 'RATE_LIMIT_EXCEEDED' }, 429),
        )).toBe(false)
    })
})

describe('delay', () => {
    afterEach(() => vi.useRealTimers())

    it('resolves on the timer', async () => {
        vi.useFakeTimers()
        const wait = delay(900, new AbortController().signal)
        await vi.advanceTimersByTimeAsync(900)
        await expect(wait).resolves.toBeUndefined()
        expect(vi.getTimerCount()).toBe(0)
    })

    it('resolves immediately if already aborted', async () => {
        const controller = new AbortController()
        controller.abort()
        await expect(delay(600_000, controller.signal)).resolves.toBeUndefined()
    })

    it('cancels its timer when aborted while waiting', async () => {
        vi.useFakeTimers()
        const controller = new AbortController()
        const wait = delay(60_000, controller.signal)
        expect(vi.getTimerCount()).toBe(1)
        controller.abort()
        await expect(wait).resolves.toBeUndefined()
        expect(vi.getTimerCount()).toBe(0)
    })
})
