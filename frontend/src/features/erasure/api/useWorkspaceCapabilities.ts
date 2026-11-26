// frontend/src/features/erasure/api/useWorkspaceCapabilities.ts
import { useEffect, useState } from 'react'
import { loadCapabilities, type Capabilities } from './workspaceApi'
export function useWorkspaceCapabilities(context: string) {
    const [result, setResult] = useState<{ context: string; data: Capabilities | null; failed: boolean } | null>(null)
    const [revision, setRevision] = useState(0)
    useEffect(() => {
        const ac = new AbortController()
        setResult(null)
        void loadCapabilities(ac.signal).then(data => {
            if (!ac.signal.aborted) setResult({ context, data, failed: false })
        }).catch(() => { if (!ac.signal.aborted) setResult({ context, data: null, failed: true }) })
        return () => ac.abort()
    }, [context, revision])
    const current = result?.context === context ? result : null
    return { capabilities: current?.data ?? null, failed: current?.failed ?? false,
        loading: current === null, retry: () => { setResult(null); setRevision(v => v + 1) } }
}
