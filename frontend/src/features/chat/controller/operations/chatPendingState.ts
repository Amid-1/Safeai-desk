import type { PendingTurn, PendingTurnStatus } from '../../lib/chatPage.helpers'

/** Меняются только UI-поля; идентичность исходной операции сохраняется. */
export function withPendingState(
    pending: PendingTurn,
    status: PendingTurnStatus,
    error: string | null = null,
    retryAfterUntil: number | null = null,
): PendingTurn {
    return { ...pending, status, error, retryAfterUntil }
}
