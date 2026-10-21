// frontend/src/shared/api/authCoordinator.ts
export type AuthChannelEventType =
    | 'REFRESH_SUCCEEDED'
    | 'LOGOUT'
    | 'SESSION_REJECTED'
    | 'AUTH_USER_CHANGED'

export type AuthChannelEvent = {
    type: AuthChannelEventType
    sourceTabId: string
    occurredAt: number
}

import {
    createSecureUuid,
} from '../lib/secureUuid'

type LockManagerLike = {
    request<T>(
        name: string,
        options: {
            mode: 'exclusive'
            signal?: AbortSignal
        },
        callback: () => Promise<T>,
    ): Promise<T>
}

const AUTH_CHANNEL_NAME = 'safeai-auth-events'
const AUTH_STORAGE_EVENT_KEY = 'safeai:auth-event'
const AUTH_REFRESH_LOCK_NAME = 'safeai-auth-refresh'

const TAB_ID = createSecureUuid()

const authChannel =
    typeof window === 'undefined'
    || typeof window.BroadcastChannel === 'undefined'
        ? null
        : new window.BroadcastChannel(AUTH_CHANNEL_NAME)

if (import.meta.hot) {
    import.meta.hot.dispose(() => {
        authChannel?.close()
    })
}

export class AuthCoordinationError extends Error {
    readonly code:
        | 'AUTH_COORDINATION_TIMEOUT'
        | 'AUTH_COORDINATION_UNAVAILABLE'

    constructor(
        code:
            | 'AUTH_COORDINATION_TIMEOUT'
            | 'AUTH_COORDINATION_UNAVAILABLE',
        message: string,
    ) {
        super(message)

        this.name = 'AuthCoordinationError'
        this.code = code
    }
}

export function publishAuthEvent(
    type: AuthChannelEventType,
): void {
    const event: AuthChannelEvent = {
        type,
        sourceTabId: TAB_ID,
        occurredAt: Date.now(),
    }

    authChannel?.postMessage(event)

    try {
        localStorage.setItem(
            AUTH_STORAGE_EVENT_KEY,
            JSON.stringify(event),
        )
        localStorage.removeItem(AUTH_STORAGE_EVENT_KEY)
    } catch {
        // BroadcastChannel остаётся основным механизмом.
        // Недоступность localStorage не должна ломать текущую вкладку.
    }
}

export function subscribeAuthEvents(
    handler: (event: AuthChannelEvent) => void,
): () => void {
    const handleEvent = (value: unknown) => {
        const event = parseAuthChannelEvent(value)

        if (!event || event.sourceTabId === TAB_ID) {
            return
        }

        handler(event)
    }

    const channelListener = (event: MessageEvent<unknown>) => {
        handleEvent(event.data)
    }

    const storageListener = (event: StorageEvent) => {
        if (
            event.key !== AUTH_STORAGE_EVENT_KEY
            || !event.newValue
        ) {
            return
        }

        try {
            handleEvent(JSON.parse(event.newValue))
        } catch {
            // Игнорируем повреждённое межвкладочное событие.
        }
    }

    authChannel?.addEventListener('message', channelListener)
    window.addEventListener('storage', storageListener)

    return () => {
        authChannel?.removeEventListener(
            'message',
            channelListener,
        )
        window.removeEventListener('storage', storageListener)
    }
}

export async function runWithAuthRefreshLock<T>(
    deadline: number | null,
    action: () => Promise<T>,
): Promise<T> {
    const lockManager = getLockManager()

    if (lockManager) {
        return runWithWebLock(
            lockManager,
            deadline,
            action,
        )
    }

    // Запись и проверка localStorage lease не являются атомарным mutex.
    // Без Web Locks нельзя гарантировать единственного владельца refresh-токена:
    // запрещаем refresh вместо небезопасной конкурентной ротации токена.
    assertDeadlineAvailable(deadline)
    throw new AuthCoordinationError(
        'AUTH_COORDINATION_UNAVAILABLE',
        'Без Web Locks API невозможно безопасно координировать обновление сессии между вкладками',
    )
}

function getLockManager(): LockManagerLike | null {
    if (typeof navigator === 'undefined') {
        return null
    }

    const candidate = (
        navigator as Navigator & {
            locks?: LockManagerLike
        }
    ).locks

    return candidate ?? null
}

async function runWithWebLock<T>(
    lockManager: LockManagerLike,
    deadline: number | null,
    action: () => Promise<T>,
): Promise<T> {
    const signalContext = createDeadlineSignal(deadline)

    try {
        return await lockManager.request(
            AUTH_REFRESH_LOCK_NAME,
            {
                mode: 'exclusive',
                signal: signalContext.signal,
            },
            action,
        )
    } catch (error) {
        if (signalContext.timedOut()) {
            throw new AuthCoordinationError(
                'AUTH_COORDINATION_TIMEOUT',
                'Превышено время ожидания межвкладочного auth lock',
            )
        }

        throw error
    } finally {
        signalContext.cleanup()
    }
}

function parseAuthChannelEvent(
    value: unknown,
): AuthChannelEvent | null {
    if (
        typeof value !== 'object'
        || value === null
        || Array.isArray(value)
    ) {
        return null
    }

    const candidate = value as Record<string, unknown>

    if (
        !isAuthChannelEventType(candidate.type)
        || typeof candidate.sourceTabId !== 'string'
        || typeof candidate.occurredAt !== 'number'
        || !Number.isFinite(candidate.occurredAt)
    ) {
        return null
    }

    return {
        type: candidate.type,
        sourceTabId: candidate.sourceTabId,
        occurredAt: candidate.occurredAt,
    }
}

function isAuthChannelEventType(
    value: unknown,
): value is AuthChannelEventType {
    return value === 'REFRESH_SUCCEEDED'
        || value === 'LOGOUT'
        || value === 'SESSION_REJECTED'
        || value === 'AUTH_USER_CHANGED'
}

function createDeadlineSignal(
    deadline: number | null,
): {
    signal?: AbortSignal
    timedOut: () => boolean
    cleanup: () => void
} {
    if (deadline === null) {
        return {
            signal: undefined,
            timedOut: () => false,
            cleanup: () => undefined,
        }
    }

    const remaining = deadline - Date.now()

    if (remaining <= 0) {
        throw new AuthCoordinationError(
            'AUTH_COORDINATION_TIMEOUT',
            'Общий deadline auth-запроса истёк',
        )
    }

    const controller = new AbortController()
    let didTimeout = false

    const timeoutId = window.setTimeout(() => {
        didTimeout = true
        controller.abort(
            new DOMException(
                'Auth coordination timed out',
                'TimeoutError',
            ),
        )
    }, remaining)

    return {
        signal: controller.signal,
        timedOut: () => didTimeout,
        cleanup: () => {
            window.clearTimeout(timeoutId)
        },
    }
}

function assertDeadlineAvailable(
    deadline: number | null,
): void {
    if (deadline !== null && deadline <= Date.now()) {
        throw new AuthCoordinationError(
            'AUTH_COORDINATION_TIMEOUT',
            'Общий deadline auth-запроса истёк',
        )
    }
}
