import { useCallback, useEffect, useState } from 'react'

export function directoryUuid(value: string | null): string {
    return value && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
        ? value.toLowerCase() : ''
}

/** Browser history is the source of truth, including direct links and Back/Forward. */
export function useDirectoryUrl() {
    const [location, setLocation] = useState(() => window.location.search)
    useEffect(() => {
        const restore = () => setLocation(window.location.search)
        window.addEventListener('popstate', restore)
        return () => window.removeEventListener('popstate', restore)
    }, [])
    const params = new URLSearchParams(location)
    const change = useCallback((values: Record<string, string>, replace = false) => {
        const next = new URLSearchParams(window.location.search)
        for (const [key, value] of Object.entries(values)) {
            if (value) next.set(key, value)
            else next.delete(key)
        }
        const query = next.toString()
        const url = window.location.pathname + (query ? '?' + query : '') + window.location.hash
        if (url !== window.location.pathname + window.location.search + window.location.hash) {
            if (replace) window.history.replaceState(window.history.state, '', url)
            else window.history.pushState(window.history.state, '', url)
        }
        setLocation(window.location.search)
    }, [])
    return { params, change }
}

export function directoryPage(value: string | null): number {
    const page = Number(value ?? '0')
    return Number.isSafeInteger(page) && page >= 0 && page <= 1000000 ? page : 0
}
