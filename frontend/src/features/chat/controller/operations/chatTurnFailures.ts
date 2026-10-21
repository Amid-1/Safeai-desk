// frontend/src/features/chat/controller/operations/chatTurnFailures.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatTurnFailures.ts
// Операции контроллера чата. Исходные сценарии сохранены.
// ============================================================
import type { PendingTurn } from '../../lib/chatPage.helpers'
import { ApiError, getApiErrorMessage } from '../../../../shared/api/http'
import type { ChatControllerDependencies } from './chatControllerTypes'

export function createTurnFailureHandler(deps: Pick<ChatControllerDependencies,
    | 'setSendError'
    | 'setClock'
    | 'reconcilePendingTurn'
    | 'putPendingTurn'
>) {
    const { setSendError, setClock, reconcilePendingTurn, putPendingTurn } = deps

    async function handleSendFailure(
        pending: PendingTurn,
        error: unknown,
    ) {
        if (error instanceof ApiError) {
            switch (error.errorCode) {
                case 'AI_QUOTA_EXCEEDED':
                case 'CHAT_QUOTA_EXCEEDED':
                case 'QUOTA_EXCEEDED':
                    putPendingTurn({
                        ...pending,
                        status: 'QUOTA_BLOCKED',
                        error: error.message
                            || 'Квота AI исчерпана.',
                        retryAfterUntil: null,
                    })
                    setSendError(
                        error.message
                        || 'Квота AI исчерпана.',
                    )
                    return

                case 'AI_OUTCOME_AMBIGUOUS':
                case 'CHAT_PROCESSOR_FENCED':
                    putPendingTurn({
                        ...pending,
                        status: 'AMBIGUOUS',
                        error:
                            'Результат AI-вызова неоднозначен. '
                            + 'Повтор с новым clientRequestId запрещён.',
                        retryAfterUntil: null,
                    })
                    setSendError(
                        'Проверьте состояние исходной операции. '
                        + 'Новый запрос автоматически не создаётся.',
                    )
                    return

                case 'CHAT_ACCESS_REVOKED_DURING_PROCESSING':
                    putPendingTurn({
                        ...pending,
                        status: 'ACCESS_REVOKED',
                        error:
                            'Доступ был отозван во время обработки.',
                        retryAfterUntil: null,
                    })
                    setSendError(
                        'Доступ к чату или организации отозван.',
                    )
                    return

                case 'IDEMPOTENCY_KEY_REUSED':
                    putPendingTurn({
                        ...pending,
                        status: 'IDEMPOTENCY_CONFLICT',
                        error:
                            'Этот clientRequestId уже связан с другим содержимым. '
                            + 'Повторять запрос автоматически нельзя.',
                        retryAfterUntil: null,
                    })
                    setSendError(
                        'Обнаружен конфликт идемпотентности.',
                    )
                    return

                case 'CHAT_TURN_IN_PROGRESS':
                case 'CHAT_BUSY':
                    putPendingTurn({
                        ...pending,
                        status: 'PROCESSING',
                        error: null,
                        retryAfterUntil: null,
                    })
                    await reconcilePendingTurn(pending)
                    return

                default:
                    break
            }

            if (error.status === 429) {
                const retryAfterSeconds =
                    error.retryAfterSeconds ?? 1

                putPendingTurn({
                    ...pending,
                    status: 'RATE_LIMITED',
                    error:
                        'Лимит запросов временно исчерпан.',
                    retryAfterUntil:
                        Date.now()
                        + retryAfterSeconds * 1_000,
                })
                setClock(Date.now())
                setSendError(
                    'После Retry-After будет выполнен только повтор '
                    + 'с тем же clientRequestId.',
                )
                return
            }
        }

        putPendingTurn({
            ...pending,
            status: 'SEND_UNKNOWN',
            error: getApiErrorMessage(
                error,
                'Статус отправки неизвестен.',
            ),
            retryAfterUntil: null,
        })

        await reconcilePendingTurn(pending)
    }

    return { handleSendFailure }
}
