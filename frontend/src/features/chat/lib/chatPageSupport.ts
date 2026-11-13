// frontend/src/features/chat/lib/chatPageSupport.ts
import { ApiError } from '../../../shared/api/http'
import type { ChatTurnStatus } from '../api/chatApi'
import type { PendingTurn, PendingTurnStatus } from './chatPage.helpers'

// Один источник текстов для статуса в панели и краткой метки истории.
// `satisfies` гарантирует, что новый статус не останется без подписи.
const PENDING_LABELS = {
    SENDING: ['Сообщение отправляется.', 'отправка'],
    PROCESSING: ['Запрос ещё обрабатывается.', 'обработка'],
    SEND_UNKNOWN: ['Сервер не подтвердил отправку.', 'статус неизвестен'],
    FAILED: ['AI-запрос завершился ошибкой.', 'ошибка'],
    AMBIGUOUS: [
        'Результат AI-вызова неоднозначен. Автоматический повтор запрещён.',
        'неоднозначно',
    ],
    RATE_LIMITED: ['Повтор разрешён с тем же clientRequestId.', 'лимит'],
    QUOTA_BLOCKED: ['Квота AI не позволяет выполнить запрос.', 'квота'],
    ACCESS_REVOKED: ['Доступ к чату был отозван.', 'доступ отозван'],
    IDEMPOTENCY_CONFLICT: [
        'Обнаружен конфликт ключа идемпотентности.',
        'конфликт ID',
    ],
} as const satisfies Record<PendingTurnStatus, readonly [string, string]>

export function getPendingLabel(pending: PendingTurn, now: number): string {
    if (pending.status === 'RATE_LIMITED') {
        const seconds = getRetryAfterSeconds(pending, now)
        if (seconds > 0) return `Повтор доступен через ${seconds} сек.`
    }
    return PENDING_LABELS[pending.status][0]
}

export function getPendingShortLabel(
    pending: Pick<PendingTurn, 'status'>,
): string {
    return PENDING_LABELS[pending.status][1]
}

export function getRetryAfterSeconds(pending: PendingTurn, now: number): number {
    if (!pending.retryAfterUntil) return 0
    return Math.max(0, Math.ceil((pending.retryAfterUntil - now) / 1_000))
}

export function failureMessage(turn: ChatTurnStatus): string {
    return turn.failureCode
        ? `AI-запрос завершился ошибкой (${turn.failureCode}).`
        : 'AI-запрос завершился ошибкой.'
}

export function isRequestAborted(error: unknown): boolean {
    return error instanceof ApiError && error.errorCode === 'REQUEST_ABORTED'
}

/** Ожидание с отменой. Отмена лишь прекращает polling, не повторяет AI-вызов. */
export function delay(milliseconds: number, signal: AbortSignal): Promise<void> {
    if (signal.aborted) return Promise.resolve()

    return new Promise<void>((resolve) => {
        let settled = false
        let timeoutId: ReturnType<typeof setTimeout> | undefined
        const finish = () => {
            if (settled) return
            settled = true
            signal.removeEventListener('abort', finish)
            if (timeoutId !== undefined) clearTimeout(timeoutId)
            resolve()
        }

        signal.addEventListener('abort', finish, { once: true })
        timeoutId = setTimeout(finish, milliseconds)
        // Защищает от отмены между первым `aborted` и регистрацией обработчика.
        if (signal.aborted) finish()
    })
}
