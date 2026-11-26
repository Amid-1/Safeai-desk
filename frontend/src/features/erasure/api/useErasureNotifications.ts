// frontend/src/features/erasure/api/useErasureNotifications.ts
import { useEffect, useState } from 'react'
import { loadNotifications, type NotificationFeed } from './notificationsApi'

export function useErasureNotifications(scope: string | null, locationKey: string) {
    const [result, setResult] = useState<{ scope: string; locationKey: string; feed: NotificationFeed | null } | null>(null)
    useEffect(() => {
        if (!scope) return
        const activeScope = scope
        const ac = new AbortController()
        let timer: number | undefined
        async function refresh() {
            try {
                const feed = await loadNotifications(ac.signal)
                if (!ac.signal.aborted) setResult({ scope: activeScope, locationKey, feed })
            } catch {
                if (!ac.signal.aborted) setResult({ scope: activeScope, locationKey, feed: null })
            } finally {
                if (!ac.signal.aborted) timer = window.setTimeout(() => void refresh(), 30_000)
            }
        }
        void refresh()
        return () => { ac.abort(); window.clearTimeout(timer) }
    }, [scope, locationKey])
    return scope && result?.scope === scope && result.locationKey === locationKey ? result.feed : null
}
