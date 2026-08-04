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

type RefreshLease = {
    owner: string
    expiresAt: number
}

const AUTH_CHANNEL_NAME = 'safeai-auth-events'
const AUTH_STORAGE_EVENT_KEY = 'safeai:auth-event'
const AUTH_REFRESH_LOCK_NAME = 'safeai-auth-refresh'
const AUTH_REFRESH_LEASE_KEY = 'safeai:auth-refresh-lease'

const LEASE_TTL_MS = 30_000
const LEASE_RENEW_INTERVAL_MS = 10_000
const LEASE_POLL_INTERVAL_MS = 100

const TAB_ID = createUuid()

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

    return runWithStorageLease(deadline, action)
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

async function runWithStorageLease<T>(
    deadline: number | null,
    action: () => Promise<T>,
): Promise<T> {
    assertStorageAvailable()

    while (true) {
        assertDeadlineAvailable(deadline)

        const now = Date.now()
        const existingLease = readLease()

        if (!existingLease || existingLease.expiresAt <= now) {
            const candidate: RefreshLease = {
                owner: TAB_ID,
                expiresAt: now + LEASE_TTL_MS,
            }

            writeLease(candidate)
            await delayWithDeadline(25, deadline)

            const confirmedLease = readLease()

            if (confirmedLease?.owner === TAB_ID) {
                return executeWithOwnedLease(action)
            }
        }

        await delayWithDeadline(
            LEASE_POLL_INTERVAL_MS,
            deadline,
        )
    }
}

async function executeWithOwnedLease<T>(
    action: () => Promise<T>,
): Promise<T> {
    const renewalId = window.setInterval(() => {
        const currentLease = readLease()

        if (currentLease?.owner !== TAB_ID) {
            return
        }

        writeLease({
            owner: TAB_ID,
            expiresAt: Date.now() + LEASE_TTL_MS,
        })
    }, LEASE_RENEW_INTERVAL_MS)

    try {
        return await action()
    } finally {
        window.clearInterval(renewalId)

        const currentLease = readLease()

        if (currentLease?.owner === TAB_ID) {
            try {
                localStorage.removeItem(
                    AUTH_REFRESH_LEASE_KEY,
                )
            } catch {
                // Lease истечёт самостоятельно.
            }
        }
    }
}

function assertStorageAvailable(): void {
    try {
        const probeKey = 'safeai:storage-probe'

        localStorage.setItem(probeKey, '1')
        localStorage.removeItem(probeKey)
    } catch {
        throw new AuthCoordinationError(
            'AUTH_COORDINATION_UNAVAILABLE',
            'Браузер не поддерживает безопасную межвкладочную координацию refresh',
        )
    }
}

function readLease(): RefreshLease | null {
    try {
        const raw = localStorage.getItem(
            AUTH_REFRESH_LEASE_KEY,
        )

        if (!raw) {
            return null
        }

        const parsed: unknown = JSON.parse(raw)

        if (
            typeof parsed !== 'object'
            || parsed === null
            || Array.isArray(parsed)
        ) {
            return null
        }

        const candidate = parsed as Record<string, unknown>

        if (
            typeof candidate.owner !== 'string'
            || typeof candidate.expiresAt !== 'number'
            || !Number.isFinite(candidate.expiresAt)
        ) {
            return null
        }

        return {
            owner: candidate.owner,
            expiresAt: candidate.expiresAt,
        }
    } catch {
        return null
    }
}

function writeLease(lease: RefreshLease): void {
    try {
        localStorage.setItem(
            AUTH_REFRESH_LEASE_KEY,
            JSON.stringify(lease),
        )
    } catch {
        throw new AuthCoordinationError(
            'AUTH_COORDINATION_UNAVAILABLE',
            'Не удалось записать межвкладочный auth lease',
        )
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

async function delayWithDeadline(
    delayMs: number,
    deadline: number | null,
): Promise<void> {
    assertDeadlineAvailable(deadline)

    const effectiveDelay = deadline === null
        ? delayMs
        : Math.min(
            delayMs,
            Math.max(1, deadline - Date.now()),
        )

    await new Promise<void>((resolve) => {
        window.setTimeout(resolve, effectiveDelay)
    })

    assertDeadlineAvailable(deadline)
}

function createUuid(): string {
    if (
        typeof crypto !== 'undefined'
        && typeof crypto.randomUUID === 'function'
    ) {
        return crypto.randomUUID()
    }

    if (
        typeof crypto === 'undefined'
        || typeof crypto.getRandomValues !== 'function'
    ) {
        throw new Error(
            'Secure browser crypto API is required',
        )
    }

    const bytes = new Uint8Array(16)

    crypto.getRandomValues(bytes)

    bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40
    bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80

    const hex = Array.from(
        bytes,
        (byte) => byte.toString(16).padStart(2, '0'),
    ).join('')

    return [
        hex.slice(0, 8),
        hex.slice(8, 12),
        hex.slice(12, 16),
        hex.slice(16, 20),
        hex.slice(20),
    ].join('-')
}
