// frontend/src/features/chat/controller/operations/chatTurnReconciliation.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatTurnReconciliation.ts
// Операции контроллера чата. Исходные сценарии сохранены.
// ============================================================
import { getChatById, getChatTurnStatus } from '../../api/chatApi'
import type { PendingTurn } from '../../lib/chatPage.helpers'
import { getApiErrorMessage, ApiError } from '../../../../shared/api/http'
import { delay, failureMessage, isRequestAborted } from '../../lib/chatPageSupport'
const RECONCILIATION_ATTEMPTS = 12
const RECONCILIATION_DELAY_MS = 2_500
import type { ChatControllerDependencies } from './chatControllerTypes'
import { withPendingState } from './chatPendingState'

export function createTurnReconciliation(deps: Pick<ChatControllerDependencies,
    | 'reconciliationControllersRef'
    | 'setSendError'
    | 'refreshChatTitle'
    | 'applyTurnSuccess'
    | 'applyReloadedSuccess'
    | 'applyTurnMessages'
    | 'putPendingTurn'
>) {
    const { reconciliationControllersRef, setSendError, refreshChatTitle, applyTurnSuccess, applyReloadedSuccess, applyTurnMessages, putPendingTurn } = deps

    async function reconcilePendingTurn(
        pending: PendingTurn,
    ) {
        reconciliationControllersRef.current
            .get(pending.chatId)
            ?.abort()

        const controller =
            new AbortController()

        reconciliationControllersRef.current.set(
            pending.chatId,
            controller,
        )

        try {
            for (
                let attempt = 0;
                attempt < RECONCILIATION_ATTEMPTS;
                attempt += 1
            ) {
                if (controller.signal.aborted) {
                    return
                }

                try {
                    const turn = await getChatTurnStatus(
                        pending.chatId,
                        pending.clientRequestId,
                        {
                            signal:
                                controller.signal,
                        },
                    )

                    if (controller.signal.aborted
                        || reconciliationControllersRef.current.get(pending.chatId) !== controller) {
                        return
                    }

                    // Ответ должен принадлежать именно проверяемой операции.
                    // При несовпадении нельзя показывать чужие сообщения.
                    if (turn.chatId !== pending.chatId
                        || turn.clientRequestId !== pending.clientRequestId) {
                        putPendingTurn(withPendingState(pending, 'AMBIGUOUS', 'Backend вернул состояние другого chat turn.'))
                        setSendError(
                            'Идентификаторы операции не совпали. '
                            + 'Автоматический повтор запрещён.',
                        )
                        return
                    }

                    applyTurnMessages(
                        pending.chatId,
                        turn,
                    )

                    if (
                        turn.state === 'NEW'
                        || turn.state === 'PROCESSING'
                    ) {
                        putPendingTurn(withPendingState(pending, 'PROCESSING'))

                        await delay(
                            RECONCILIATION_DELAY_MS,
                            controller.signal,
                        )
                        continue
                    }

                    if (turn.state === 'SUCCEEDED') {
                        if (
                            turn.userMessage
                            && turn.assistantMessage
                        ) {
                            applyTurnSuccess(
                                pending,
                                turn,
                            )
                            void refreshChatTitle(pending.chatId)
                            return
                        }

                        const details = await getChatById(
                            pending.chatId,
                            {
                                signal:
                                    controller.signal,
                            },
                        )

                        if (controller.signal.aborted
                            || reconciliationControllersRef.current.get(pending.chatId) !== controller) {
                            return
                        }
                        applyReloadedSuccess(
                            pending,
                            details,
                        )
                        return
                    }

                    if (turn.state === 'FAILED') {
                        const message =
                            failureMessage(turn)

                        putPendingTurn(withPendingState(pending, 'FAILED', message))
                        setSendError(message)
                        return
                    }

                    putPendingTurn(withPendingState(
                        pending, 'AMBIGUOUS',
                        'Результат операции неоднозначен. Нельзя создавать автоматический повтор с новым ID.',
                    ))
                    setSendError(
                        'Проверьте исходную операцию или закройте предупреждение '
                        + 'без её повторной отправки.',
                    )
                    return
                } catch (error) {
                    if (isRequestAborted(error)) {
                        return
                    }

                    if (
                        error instanceof ApiError
                        && error.status === 404
                    ) {
                        putPendingTurn(withPendingState(
                            pending, 'SEND_UNKNOWN',
                            pending.error ?? 'Сервер пока не подтвердил получение сообщения.',
                        ))
                        return
                    }

                    if (
                        attempt
                        === RECONCILIATION_ATTEMPTS - 1
                    ) {
                        putPendingTurn(withPendingState(
                            pending, 'SEND_UNKNOWN',
                            pending.error ?? getApiErrorMessage(error, 'Не удалось определить состояние turn.'),
                        ))
                        return
                    }

                    await delay(
                        RECONCILIATION_DELAY_MS,
                        controller.signal,
                    )
                }
            }
        } finally {
            if (
                reconciliationControllersRef.current
                    .get(pending.chatId)
                === controller
            ) {
                reconciliationControllersRef.current.delete(
                    pending.chatId,
                )
            }
        }
    }

    return { reconcilePendingTurn }
}
