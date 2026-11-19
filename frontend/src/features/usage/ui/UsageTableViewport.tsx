// frontend/src/features/usage/ui/UsageTableViewport.tsx
import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type PropsWithChildren } from 'react'

const MIN_HEIGHT = 120

export function UsageTableViewport({ table, hasRows, loading, children }: PropsWithChildren<{
    table: boolean
    hasRows: boolean
    loading: boolean
}>) {
    const viewport = useRef<HTMLDivElement>(null)
    const drag = useRef<{ startY: number; height: number } | null>(null)
    const [height, setHeight] = useState<number>()
    const [overflow, setOverflow] = useState(false)
    const [actualHeight, setActualHeight] = useState(MIN_HEIGHT)
    const [maxHeight, setMaxHeight] = useState(MIN_HEIGHT)

    // The footer stays anchored. Only the space above the report is negotiable.
    function availableHeight(node: HTMLDivElement) {
        const page = node.closest<HTMLElement>('.usage-page')
        const panel = node.parentElement
        if (!page || !panel) return Math.max(MIN_HEIGHT, node.scrollHeight)
        const upper = page.querySelector<HTMLElement>('.usage-workspace-upper')
        const toolbar = panel.querySelector<HTMLElement>('.usage-results-toolbar')
        const footer = panel.querySelector<HTMLElement>('.usage-results-footer')
        const style = window.getComputedStyle(page)
        const gap = parseFloat(style.rowGap) || 0
        const padding = (parseFloat(style.paddingTop) || 0) + (parseFloat(style.paddingBottom) || 0)
        const upperReserve = Math.min(180, Math.floor(page.clientHeight * .30))
        const chrome = Array.from(panel.children).filter(child => child !== node)
            .reduce((sum, child) => sum + child.getBoundingClientRect().height, 0)
        // Before the handle is mounted, reserve its height as well.
        const handle = panel.querySelector('.usage-table-resizer')
        const panelBorders = panel.offsetHeight - panel.clientHeight
        if (!upper || !toolbar || !footer) return Math.max(MIN_HEIGHT, node.scrollHeight)
        return Math.max(MIN_HEIGHT, Math.floor(Math.min(node.scrollHeight,
            page.clientHeight - padding - gap - upperReserve - chrome - panelBorders - (handle ? 0 : 18))))
    }

    useEffect(() => {
        const node = viewport.current
        if (!table || !hasRows || !node) return
        const measure = () => {
            setOverflow(node.scrollHeight > node.clientHeight + 1)
            setActualHeight(node.clientHeight)
            setMaxHeight(availableHeight(node))
        }
        measure()
        if (typeof ResizeObserver === 'undefined') return
        const observer = new ResizeObserver(measure)
        observer.observe(node)
        if (node.firstElementChild) observer.observe(node.firstElementChild)
        const page = node.closest('.usage-page')
        if (page) observer.observe(page)
        return () => observer.disconnect()
    }, [table, hasRows])

    // The sticky organization card must have its own bounded scroll area when
    // the report's visible viewport is shorter than the card. The actual DOM
    // height is authoritative (viewport units do not account for toolbars).
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

    function resize(next: number) {
        const node = viewport.current
        if (!node) return
        setHeight(Math.round(Math.max(MIN_HEIGHT, Math.min(next, availableHeight(node)))))
    }

    return <>
        {table && hasRows && (overflow || height !== undefined) &&
            <div className="usage-table-resizer" role="separator" tabIndex={0}
                aria-orientation="horizontal" aria-label="Высота таблицы"
                aria-valuemin={MIN_HEIGHT} aria-valuenow={Math.max(MIN_HEIGHT, actualHeight)}
                aria-valuemax={Math.max(MIN_HEIGHT, actualHeight, maxHeight)}
                title="Потяните вверх или вниз. Двойной клик — восстановить размер."
                onDoubleClick={() => setHeight(undefined)}
                onPointerDown={event => {
                    if (event.button !== 0 || !viewport.current) return
                    event.preventDefault()
                    event.currentTarget.focus()
                    event.currentTarget.setPointerCapture(event.pointerId)
                    drag.current = { startY: event.clientY, height: viewport.current.clientHeight }
                }}
                onPointerMove={event => {
                    if (drag.current) resize(drag.current.height + drag.current.startY - event.clientY)
                }}
                onLostPointerCapture={() => { drag.current = null }}
                onPointerUp={() => { drag.current = null }}
                onPointerCancel={() => { drag.current = null }}
                onKeyDown={event => {
                    if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
                        event.preventDefault()
                        resize(actualHeight + (event.key === 'ArrowUp' ? 32 : -32))
                    } else if (event.key === 'Home' || event.key === 'End') {
                        event.preventDefault()
                        if (event.key === 'Home') resize(MIN_HEIGHT)
                        else resize(maxHeight)
                    }
                }}>
                <span aria-hidden="true" />
            </div>}
        <div ref={viewport} className={`usage-data-viewport${table && height !== undefined ? ' usage-data-viewport--manual' : ''}`} tabIndex={0}
            role="region" aria-label={table ? 'Таблица результатов' : 'График результатов'}
            aria-busy={loading}
            style={table && height !== undefined ? { '--usage-table-height': `${Math.min(height, maxHeight)}px` } as CSSProperties : undefined}>
            {children}
        </div>
    </>
}
