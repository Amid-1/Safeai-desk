// frontend/src/features/usage/controller/useDashboardResource.ts
import { useEffect, useState } from 'react'

type Resource<T> = { key: string; data?: T; failed?: boolean }

/** Never show data from a previous filter, or commit a superseded response. */
export function useDashboardResource<T>(key: string, load: (signal: AbortSignal) => Promise<T>) {
    const [attempt, setAttempt] = useState(0)
    const requestKey = key + ':' + attempt
    const [result, setResult] = useState<Resource<T>>()
    useEffect(() => {
        const controller = new AbortController()
        void load(controller.signal).then(
            data => { if (!controller.signal.aborted) setResult({key: requestKey, data}) },
            () => { if (!controller.signal.aborted) setResult({key: requestKey, failed: true}) },
        )
        return () => controller.abort()
    }, [load, requestKey])
    const current = result?.key === requestKey ? result : undefined
    return {data: current?.data, failed: current?.failed === true, loading: !current,
        retry: () => setAttempt(value => value + 1)}
}
