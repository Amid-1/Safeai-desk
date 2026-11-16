import { useEffect, useRef, useState, type CSSProperties, type PropsWithChildren } from 'react'

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
    const [contentHeight, setContentHeight] = useState(MIN_HEIGHT)

    useEffect(() => {
        const node = viewport.current
        if (!table || !hasRows || !node) return
        const measure = () => {
            setOverflow(node.scrollHeight > node.clientHeight + 1)
            setActualHeight(node.clientHeight)
            setContentHeight(node.scrollHeight)
        }
        measure()
        if (typeof ResizeObserver === 'undefined') return
        const observer = new ResizeObserver(measure)
        observer.observe(node)
        if (node.firstElementChild) observer.observe(node.firstElementChild)
        return () => observer.disconnect()
    }, [table, hasRows])

    function resize(next: number) {
        const node = viewport.current
        if (!node) return
        const page = node.closest('.usage-page')
        const footer = node.parentElement?.querySelector('.usage-results-footer')
        const available = page
            ? page.getBoundingClientRect().bottom - node.getBoundingClientRect().top
                - (footer?.getBoundingClientRect().height ?? 0) - 22
            : node.scrollHeight
        setHeight(Math.round(Math.max(MIN_HEIGHT, Math.min(next, node.scrollHeight, available))))
    }

    return <>
        <div ref={viewport} className="usage-data-viewport" tabIndex={0}
            role="region" aria-label={table ? 'Таблица результатов' : 'График результатов'}
            aria-busy={loading}
            style={table && height !== undefined ? { '--usage-table-max-height': `${height}px` } as CSSProperties : undefined}>
            {children}
        </div>
        {table && hasRows && (overflow || height !== undefined) &&
            <div className="usage-table-resizer" role="separator" tabIndex={0}
                aria-orientation="horizontal" aria-label="Высота таблицы"
                aria-valuemin={MIN_HEIGHT} aria-valuenow={Math.max(MIN_HEIGHT, actualHeight)}
                aria-valuemax={Math.max(MIN_HEIGHT, contentHeight)}
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
                    if (drag.current) resize(drag.current.height + event.clientY - drag.current.startY)
                }}
                onLostPointerCapture={() => { drag.current = null }}
                onPointerUp={() => { drag.current = null }}
                onPointerCancel={() => { drag.current = null }}
                onKeyDown={event => {
                    if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
                        event.preventDefault()
                        resize(actualHeight + (event.key === 'ArrowUp' ? -32 : 32))
                    } else if (event.key === 'Home' || event.key === 'End') {
                        event.preventDefault()
                        if (event.key === 'Home') resize(MIN_HEIGHT)
                        else setHeight(undefined)
                    }
                }}>
                <span aria-hidden="true" />
            </div>}
    </>
}
