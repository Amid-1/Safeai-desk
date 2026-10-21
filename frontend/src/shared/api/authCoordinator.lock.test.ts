// frontend/src/shared/api/authCoordinator.lock.test.ts
// Детерминированные проверки Web Locks и безопасного отказа без атомарной блокировки.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AuthCoordinationError, runWithAuthRefreshLock } from './authCoordinator'

const originalLocks = Object.getOwnPropertyDescriptor(navigator, 'locks')

function installLocks(value: unknown): void {
    Object.defineProperty(navigator, 'locks', {
        configurable: true,
        value,
    })
}

afterEach(() => {
    if (originalLocks) {
        Object.defineProperty(navigator, 'locks', originalLocks)
    } else {
        Reflect.deleteProperty(navigator, 'locks')
    }
    vi.restoreAllMocks()
})

describe('auth refresh — строгое межвкладочное владение', () => {
    it('при отсутствии Web Locks не делает refresh даже при доступном localStorage', async () => {
        installLocks(undefined)
        const action = vi.fn().mockResolvedValue(undefined)
        await expect(runWithAuthRefreshLock(null, action)).rejects.toMatchObject({
            name: 'AuthCoordinationError',
            code: 'AUTH_COORDINATION_UNAVAILABLE',
        } satisfies Partial<AuthCoordinationError>)
        expect(action).not.toHaveBeenCalled()
    })

    it('при истёкшем deadline не вызывает защищённое действие', async () => {
        installLocks(undefined)
        const action = vi.fn().mockResolvedValue(undefined)
        await expect(runWithAuthRefreshLock(Date.now() - 1, action)).rejects.toMatchObject({
            code: 'AUTH_COORDINATION_TIMEOUT',
        })
        expect(action).not.toHaveBeenCalled()
    })

    it('делегирует взаимное исключение Web Locks и сохраняет результат', async () => {
        let releaseFirst!: () => void
        const firstGate = new Promise<void>((resolve) => { releaseFirst = resolve })
        let releaseQueue: Promise<void> = Promise.resolve()
        let active = 0
        let maximum = 0
        const lockNames: string[] = []

        installLocks({
            async request<T>(
                name: string,
                options: { mode: 'exclusive'; signal?: AbortSignal },
                callback: () => Promise<T>,
            ): Promise<T> {
                lockNames.push(name)
                expect(options.mode).toBe('exclusive')
                const previous = releaseQueue
                let releaseCurrent!: () => void
                releaseQueue = new Promise<void>((resolve) => { releaseCurrent = resolve })
                await previous
                try {
                    active += 1
                    maximum = Math.max(maximum, active)
                    return await callback()
                } finally {
                    active -= 1
                    releaseCurrent()
                }
            },
        })

        const first = runWithAuthRefreshLock(null, async () => {
            await firstGate
            return 'first'
        })
        const second = runWithAuthRefreshLock(null, async () => 'second')
        releaseFirst()
        expect(await Promise.all([first, second])).toEqual(['first', 'second'])
        expect(maximum).toBe(1)
        expect(lockNames).toEqual(['safeai-auth-refresh', 'safeai-auth-refresh'])
    })
})
