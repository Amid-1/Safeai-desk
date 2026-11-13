import type { PendingTurn, PendingTurnStatus } from '../../lib/chatPage.helpers'
import { ApiError, getApiErrorMessage } from '../../../../shared/api/http'
import type { ChatControllerDependencies } from './chatControllerTypes'
import { withPendingState } from './chatPendingState'

type FailureDeps = Pick<ChatControllerDependencies,
    'setSendError' | 'setClock' | 'reconcilePendingTurn' | 'putPendingTurn'
>

type FailureDecision = {
    status: PendingTurnStatus
    pendingMessage: string | null
    visibleMessage?: string
    retryAfterUntil?: number
    reconcile?: boolean
}

const QUOTA_CODES = new Set([
    'AI_QUOTA_EXCEEDED', 'CHAT_QUOTA_EXCEEDED', 'QUOTA_EXCEEDED',
])
const AMBIGUOUS_CODES = new Set(['AI_OUTCOME_AMBIGUOUS', 'CHAT_PROCESSOR_FENCED'])
const IN_PROGRESS_CODES = new Set(['CHAT_TURN_IN_PROGRESS', 'CHAT_BUSY'])

/** Преобразует известный HTTP-отказ в действие, не инициируя новый AI-запрос. */
function resolveFailure(error: ApiError, now: number): FailureDecision | null {
    const code = error.errorCode ?? ''
    if (QUOTA_CODES.has(code)) {
        const message = error.message || 'Квота AI исчерпана.'
        return { status: 'QUOTA_BLOCKED', pendingMessage: message, visibleMessage: message }
    }
    if (AMBIGUOUS_CODES.has(code)) {
        return {
            status: 'AMBIGUOUS',
            pendingMessage: 'Результат AI-вызова неоднозначен. Повтор с новым clientRequestId запрещён.',
            visibleMessage: 'Проверьте состояние исходной операции. Новый запрос автоматически не создаётся.',
        }
    }
    if (code === 'CHAT_ACCESS_REVOKED_DURING_PROCESSING') {
        return {
            status: 'ACCESS_REVOKED',
            pendingMessage: 'Доступ был отозван во время обработки.',
            visibleMessage: 'Доступ к чату или организации отозван.',
        }
    }
    if (code === 'IDEMPOTENCY_KEY_REUSED') {
        return {
            status: 'IDEMPOTENCY_CONFLICT',
            pendingMessage: 'Этот clientRequestId уже связан с другим содержимым. Повторять запрос автоматически нельзя.',
            visibleMessage: 'Обнаружен конфликт идемпотентности.',
        }
    }
    if (IN_PROGRESS_CODES.has(code)) {
        return { status: 'PROCESSING', pendingMessage: null, reconcile: true }
    }
    if ([400, 401, 403, 404].includes(error.status)) {
        const message = getApiErrorMessage(
            error, 'Сервер отклонил запрос. Проверьте настройки доступа и модели.',
        )
        return { status: 'FAILED', pendingMessage: message, visibleMessage: message }
    }
    if (error.status === 429) {
        return {
            status: 'RATE_LIMITED',
            pendingMessage: 'Лимит запросов временно исчерпан.',
            visibleMessage: 'После Retry-After будет выполнен только повтор с тем же clientRequestId.',
            retryAfterUntil: now + (error.retryAfterSeconds ?? 1) * 1_000,
        }
    }
    return null
}

export function createTurnFailureHandler(deps: FailureDeps) {
    const { putPendingTurn, setSendError, setClock, reconcilePendingTurn } = deps

    async function handleSendFailure(pending: PendingTurn, error: unknown): Promise<void> {
        const now = Date.now()
        const decision = error instanceof ApiError ? resolveFailure(error, now) : null
        if (decision) {
            putPendingTurn(withPendingState(
                pending, decision.status, decision.pendingMessage,
                decision.retryAfterUntil ?? null,
            ))
            if (decision.visibleMessage) setSendError(decision.visibleMessage)
            if (decision.status === 'RATE_LIMITED') setClock(now)
            if (decision.reconcile) await reconcilePendingTurn(pending)
            return
        }

        // Transport/unknown: не отправляем повторный POST, а сверяем исходный turn.
        const unknown = withPendingState(
            pending, 'SEND_UNKNOWN', getApiErrorMessage(error, 'Статус отправки неизвестен.'),
        )
        putPendingTurn(unknown)
        await reconcilePendingTurn(unknown)
    }

    return { handleSendFailure }
}
