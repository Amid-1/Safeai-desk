// frontend/src/features/usage/ui/UsageTableViewport.tsx
import { useLayoutEffect, useRef, type PropsWithChildren } from 'react'

export function UsageTableViewport({ table, hasRows, loading, children }: PropsWithChildren<{
    table: boolean
    hasRows: boolean
    loading: boolean
}>) {
    const viewport = useRef<HTMLDivElement>(null)

    // The sticky organization card must have its own bounded scroll area when
    // the report's visible viewport is shorter than the card.
    useLayoutEffect(() => {
        const node = viewport.current
        if (table || !hasRows || !node) return
        const measure = () => {
            if (node.clientHeight > 0) {
                node.style.setProperty('--usage-chart-viewport-height', `${node.clientHeight}px`)
            } else {
                node.style.removeProperty('--usage-chart-viewport-height')
            }
        }
        measure()
        const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
        observer?.observe(node)
        window.addEventListener('resize', measure)
        return () => {
            observer?.disconnect()
            window.removeEventListener('resize', measure)
            node.style.removeProperty('--usage-chart-viewport-height')
        }
    }, [table, hasRows])

    return <div ref={viewport} className="usage-data-viewport" tabIndex={0}
        role="region" aria-label={table ? 'Таблица результатов' : 'График результатов'} aria-busy={loading}>
        {children}
    </div>
}
