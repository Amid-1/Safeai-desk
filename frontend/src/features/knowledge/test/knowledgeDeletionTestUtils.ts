// frontend/src/features/knowledge/test/knowledgeDeletionTestUtils.ts
/* frontend/src/features/knowledge/test/knowledgeDeletionTestUtils.ts */
import {act} from '@testing-library/react'

/**
 * Creates a promise whose completion is controlled by the test.
 * Shared by knowledge document/base deletion tests to avoid divergent helpers.
 */
export function deferred<T>() {
    let resolve!: (value: T) => void
    let reject!: (reason?: unknown) => void

    const promise = new Promise<T>((resolvePromise, rejectPromise) => {
        resolve = resolvePromise
        reject = rejectPromise
    })

    return {promise, resolve, reject}
}

/**
 * Flushes the queued promise continuations inside React's act boundary.
 */
export async function flushMicrotasks() {
    await act(async () => {
        await Promise.resolve()
        await Promise.resolve()
        await Promise.resolve()
    })
}
