// frontend/src/features/chat/controller/operations/chatTurnFailures.ts
import type { PendingTurn, PendingTurnStatus } from '../../lib/chatPage.helpers'
import { ApiError, getApiErrorMessage, rateLimitRetrySeconds } from '../../../../shared/api/http'
import type { ChatControllerDependencies } from './chatControllerTypes'
import { withPendingState } from './chatPendingState'
import { parsePublicModelRouteDenial } from '../../../../shared/lib/modelRouteDenial'

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
    const denied = parsePublicModelRouteDenial(error.status, code, error.message)
    if (denied) {
        const message = denied.message + ' Обратитесь к администратору. Номер решения: ' + denied.decisionId
        return { status: 'FAILED', pendingMessage: message, visibleMessage: message }
    }
    if (QUOTA_CODES.has(code)) {
        const message = 'Лимит использования AI исчерпан. Обратитесь к администратору.'
        return { status: 'QUOTA_BLOCKED', pendingMessage: message, visibleMessage: message }
    }
    if (code === 'AI_EXECUTION_RECONCILIATION_REQUIRED') {
        return {
            status: 'RECONCILIATION_REQUIRED',
            pendingMessage: 'Не удалось подтвердить результат. Проверьте состояние отправленного запроса.',
            reconcile: true,
        }
    }
    if (AMBIGUOUS_CODES.has(code)) {
        return {
            status: 'AMBIGUOUS',
            pendingMessage: 'Не удалось подтвердить результат отправленного запроса.',
            visibleMessage: 'Проверьте состояние запроса перед новой отправкой.',
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
            pendingMessage: 'Не удалось повторить отправку: запрос связан с другим сообщением.',
            visibleMessage: 'Скопируйте текст или закройте предупреждение без повторной отправки.',
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
            pendingMessage: code === 'AI_PROVIDER_RATE_LIMITED'
                ? 'AI временно перегружен.'
                : 'Слишком много запросов.',
            retryAfterUntil: now + rateLimitRetrySeconds(error) * 1_000,
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
            const updated = withPendingState(
                pending, decision.status, decision.pendingMessage,
                decision.retryAfterUntil ?? null,
            )
            putPendingTurn(updated)
            if (decision.visibleMessage) setSendError(decision.visibleMessage)
            if (decision.status === 'RATE_LIMITED') setClock(now)
            if (decision.reconcile) {
                await reconcilePendingTurn(
                    decision.status === 'RECONCILIATION_REQUIRED' ? updated : pending,
                )
            }
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
