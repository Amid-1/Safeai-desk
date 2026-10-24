// frontend/src/features/models/ui/catalog/useCatalogPanelHeight.ts
// Геометрия только прокручиваемой таблицы каталога. Не затрагивает запросы, RBAC или другие страницы.
import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent, PointerEvent, RefObject } from 'react'

export const CATALOG_PANEL_MIN_HEIGHT = 180
export const CATALOG_PANEL_MAX_HEIGHT = 840
export const CATALOG_PANEL_STORAGE_KEY = 'safeai:models:catalog-panel-height:v1'
const VIEWPORT_BOTTOM_GAP = 84
const KEYBOARD_STEP = 32

type DragState = {
    pointerId: number
    startY: number
    startHeight: number
    startPreference: number | null
    currentHeight: number
}

/** Keeps the table within the visible viewport where possible. On short screens,
 * natural page scrolling remains available rather than clipping controls. */
export function catalogPanelMaxHeight(top: number, viewportHeight: number): number {
    if (!Number.isFinite(top) || !Number.isFinite(viewportHeight)) {
        return CATALOG_PANEL_MIN_HEIGHT
    }
    return Math.max(
        CATALOG_PANEL_MIN_HEIGHT,
        Math.min(CATALOG_PANEL_MAX_HEIGHT, Math.floor(viewportHeight - top - VIEWPORT_BOTTOM_GAP)),
    )
}

export function clampCatalogPanelHeight(value: number, max: number): number {
    const upperBound = Number.isFinite(max) ? Math.max(CATALOG_PANEL_MIN_HEIGHT, max) : CATALOG_PANEL_MIN_HEIGHT
    const candidate = Number.isFinite(value) ? Math.round(value) : CATALOG_PANEL_MIN_HEIGHT
    return Math.min(upperBound, Math.max(CATALOG_PANEL_MIN_HEIGHT, candidate))
}

export function readCatalogPanelHeight(storage: Pick<Storage, 'getItem'>): number | null {
    try {
        const stored = storage.getItem(CATALOG_PANEL_STORAGE_KEY)
        if (!stored || !/^\d+$/.test(stored)) return null
        const parsed = Number(stored)
        return Number.isSafeInteger(parsed)
            && parsed >= CATALOG_PANEL_MIN_HEIGHT
            && parsed <= CATALOG_PANEL_MAX_HEIGHT ? parsed : null
    } catch {
        return null // Storage can be denied by browser policy; resizing still works.
    }
}

export function useCatalogPanelHeight(): {
    regionRef: RefObject<HTMLDivElement | null>
    height: number
    maxHeight: number
    onPointerDown: (event: PointerEvent<HTMLDivElement>) => void
    onPointerMove: (event: PointerEvent<HTMLDivElement>) => void
    onPointerUp: (event: PointerEvent<HTMLDivElement>) => void
    onPointerCancel: (event: PointerEvent<HTMLDivElement>) => void
    onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => void
    onDoubleClick: () => void
} {
    const regionRef = useRef<HTMLDivElement | null>(null)
    const dragRef = useRef<DragState | null>(null)
    const [preferredHeight, setPreferredHeight] = useState<number | null>(() => {
        try {
            return typeof window === 'undefined' ? null : readCatalogPanelHeight(window.localStorage)
        } catch {
            return null
        }
    })
    const [maxHeight, setMaxHeight] = useState(CATALOG_PANEL_MAX_HEIGHT)
    const height = clampCatalogPanelHeight(preferredHeight ?? maxHeight, maxHeight)

    const measure = useCallback(() => {
        const region = regionRef.current
        if (!region || typeof window === 'undefined') return
        const next = catalogPanelMaxHeight(region.getBoundingClientRect().top, window.innerHeight)
        setMaxHeight((old) => old === next ? old : next)
    }, [])

    useLayoutEffect(() => {
        measure()
        window.addEventListener('resize', measure)
        const observer = typeof ResizeObserver === 'undefined'
            ? null : new ResizeObserver(measure)
        // Changes to the heading or an error message can move the table down.
        if (regionRef.current?.parentElement) observer?.observe(regionRef.current.parentElement)
        return () => {
            window.removeEventListener('resize', measure)
            observer?.disconnect()
        }
    }, [measure])

    function persist(value: number) {
        const next = clampCatalogPanelHeight(value, maxHeight)
        setPreferredHeight(next)
        try {
            window.localStorage.setItem(CATALOG_PANEL_STORAGE_KEY, String(next))
        } catch {
            // A blocked Storage API must not disable the UI control.
        }
    }

    function onPointerDown(event: PointerEvent<HTMLDivElement>) {
        if (event.button !== 0 || dragRef.current) return
        dragRef.current = { pointerId: event.pointerId, startY: event.clientY,
            startHeight: height, startPreference: preferredHeight, currentHeight: height }
        event.currentTarget.setPointerCapture?.(event.pointerId)
        event.preventDefault()
    }

    function onPointerMove(event: PointerEvent<HTMLDivElement>) {
        const drag = dragRef.current
        if (!drag || drag.pointerId !== event.pointerId) return
        // This is a handle ABOVE the table: dragging upward expands it.
        const next = clampCatalogPanelHeight(drag.startHeight + drag.startY - event.clientY, maxHeight)
        drag.currentHeight = next
        setPreferredHeight(next)
    }

    function finishDrag(event: PointerEvent<HTMLDivElement>, save: boolean) {
        const drag = dragRef.current
        if (!drag || drag.pointerId !== event.pointerId) return
        dragRef.current = null
        if (event.currentTarget.hasPointerCapture?.(event.pointerId)) {
            event.currentTarget.releasePointerCapture(event.pointerId)
        }
        if (save) persist(drag.currentHeight)
        else setPreferredHeight(drag.startPreference)
    }

    function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
        const next = event.key === 'ArrowUp' ? height + KEYBOARD_STEP
            : event.key === 'ArrowDown' ? height - KEYBOARD_STEP
                : event.key === 'Home' ? CATALOG_PANEL_MIN_HEIGHT
                    : event.key === 'End' ? maxHeight : null
        if (next === null) return
        event.preventDefault()
        persist(next)
    }

    return {
        regionRef, height, maxHeight,
        onPointerDown, onPointerMove,
        onPointerUp: (event) => finishDrag(event, true),
        onPointerCancel: (event) => finishDrag(event, false),
        onKeyDown,
        onDoubleClick: () => {
            setPreferredHeight(null)
            try { window.localStorage.removeItem(CATALOG_PANEL_STORAGE_KEY) } catch { /* storage unavailable */ }
            measure()
        },
    }
}
