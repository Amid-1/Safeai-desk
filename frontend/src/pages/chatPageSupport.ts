import { ApiError } from '../api/http'
import type { ChatTurnStatus } from '../api/chatApi'
import type { PendingTurn } from './chatPage.helpers'

export function getPendingLabel(
    pending: PendingTurn,
    now: number,
): string {
    switch (pending.status) {
        case 'SENDING':
            return 'Сообщение отправляется.'

        case 'PROCESSING':
            return 'Запрос ещё обрабатывается.'

        case 'SEND_UNKNOWN':
            return (
                'Статус отправки неизвестен. '
                + 'Новый clientRequestId не создаётся.'
            )

        case 'FAILED':
            return 'AI-запрос завершился ошибкой.'

        case 'AMBIGUOUS':
            return (
                'Результат AI-вызова неоднозначен. '
                + 'Автоматический повтор запрещён.'
            )

        case 'RATE_LIMITED': {
            const seconds = getRetryAfterSeconds(
                pending,
                now,
            )

            return seconds > 0
                ? `Повтор доступен через ${seconds} сек.`
                : (
                    'Повтор разрешён с тем же '
                    + 'clientRequestId.'
                )
        }

        case 'QUOTA_BLOCKED':
            return 'Квота AI не позволяет выполнить запрос.'

        case 'ACCESS_REVOKED':
            return 'Доступ к чату был отозван.'

        case 'IDEMPOTENCY_CONFLICT':
            return 'Обнаружен конфликт ключа идемпотентности.'
    }
}

export function getPendingShortLabel(
    pending: Pick<PendingTurn, 'status'>,
): string {
    switch (pending.status) {
        case 'SENDING':
            return 'отправка'
        case 'PROCESSING':
            return 'обработка'
        case 'SEND_UNKNOWN':
            return 'статус неизвестен'
        case 'FAILED':
            return 'ошибка'
        case 'AMBIGUOUS':
            return 'неоднозначно'
        case 'RATE_LIMITED':
            return 'лимит'
        case 'QUOTA_BLOCKED':
            return 'квота'
        case 'ACCESS_REVOKED':
            return 'доступ отозван'
        case 'IDEMPOTENCY_CONFLICT':
            return 'конфликт ID'
    }
}

export function getRetryAfterSeconds(
    pending: PendingTurn,
    now: number,
): number {
    if (!pending.retryAfterUntil) {
        return 0
    }

    return Math.max(
        0,
        Math.ceil(
            (
                pending.retryAfterUntil - now
            ) / 1_000,
        ),
    )
}

export function failureMessage(
    turn: ChatTurnStatus,
): string {
    if (turn.failureCode) {
        return `AI-запрос завершился ошибкой (${turn.failureCode}).`
    }

    return 'AI-запрос завершился ошибкой.'
}

export function isRequestAborted(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && error.errorCode === 'REQUEST_ABORTED'
}

export async function delay(
    milliseconds: number,
    signal: AbortSignal,
): Promise<void> {
    if (signal.aborted) {
        return
    }

    await new Promise<void>((resolve) => {
        const abort = () => {
            window.clearTimeout(timeoutId)
            resolve()
        }

        const timeoutId = window.setTimeout(
            () => {
                signal.removeEventListener(
                    'abort',
                    abort,
                )
                resolve()
            },
            milliseconds,
        )

        signal.addEventListener(
            'abort',
            abort,
            { once: true },
        )
    })
}

