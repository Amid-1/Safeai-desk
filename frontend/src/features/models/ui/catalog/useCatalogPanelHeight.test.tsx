// frontend/src/features/models/ui/catalog/useCatalogPanelHeight.test.tsx
// Boundaries, persistence, keyboard, pointer handling and viewport changes.
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
    catalogPanelMaxHeight,
    clampCatalogPanelHeight,
    CATALOG_PANEL_MAX_HEIGHT,
    CATALOG_PANEL_MIN_HEIGHT,
    CATALOG_PANEL_STORAGE_KEY,
    readCatalogPanelHeight,
    useCatalogPanelHeight,
} from './useCatalogPanelHeight'

function Harness() {
    const {
        regionRef,
        height,
        onPointerDown,
        onPointerMove,
        onPointerUp,
        onPointerCancel,
        onKeyDown,
        onDoubleClick,
    } = useCatalogPanelHeight()
    return (
        <div>
            <div role="separator" aria-label="Высота каталога" aria-valuenow={height}
                tabIndex={0} onPointerDown={onPointerDown} onPointerMove={onPointerMove}
                onPointerUp={onPointerUp} onPointerCancel={onPointerCancel}
                onKeyDown={onKeyDown} onDoubleClick={onDoubleClick} />
            <div ref={regionRef} data-testid="viewport" style={{height: height}} />
        </div>
    )
}

beforeEach(() => {
    window.localStorage.clear()
    vi.restoreAllMocks()
})

describe('Resizable catalog viewport', () => {
    it('clamps to available viewport height without clipping short layouts', () => {
        expect(catalogPanelMaxHeight(400, 900)).toBe(416)
        expect(catalogPanelMaxHeight(900, 700)).toBe(CATALOG_PANEL_MIN_HEIGHT)
        expect(catalogPanelMaxHeight(0, 3000)).toBe(CATALOG_PANEL_MAX_HEIGHT)
        expect(catalogPanelMaxHeight(Number.NaN, 900)).toBe(CATALOG_PANEL_MIN_HEIGHT)
        expect(clampCatalogPanelHeight(100, 500)).toBe(CATALOG_PANEL_MIN_HEIGHT)
        expect(clampCatalogPanelHeight(700, 500)).toBe(500)
        expect(clampCatalogPanelHeight(Number.NaN, 500)).toBe(CATALOG_PANEL_MIN_HEIGHT)
    })

    it('does not accept corrupted or out-of-bounds local settings', () => {
        const getItem = vi.fn(() => '200')
        expect(readCatalogPanelHeight({getItem})).toBe(200)
        getItem.mockReturnValue('9999999999999999999')
        expect(readCatalogPanelHeight({getItem})).toBeNull()
        getItem.mockReturnValue('javascript:1')
        expect(readCatalogPanelHeight({getItem})).toBeNull()
        getItem.mockImplementation(() => {throw new Error('storage disabled')})
        expect(readCatalogPanelHeight({getItem})).toBeNull()
    })

    it('uses keyboard controls, remembers height and restores auto-fit by double click', () => {
        render(<Harness />)
        const separator = screen.getByRole('separator', {name:'Высота каталога'})
        const initial = Number(separator.getAttribute('aria-valuenow'))
        fireEvent.keyDown(separator, { key:'ArrowDown' })
        const shorter = Number(separator.getAttribute('aria-valuenow'))
        expect(shorter).toBeLessThan(initial)
        expect(window.localStorage.getItem(CATALOG_PANEL_STORAGE_KEY)).toBe(String(shorter))
        fireEvent.keyDown(separator, { key:'Home' })
        expect(separator).toHaveAttribute('aria-valuenow', String(CATALOG_PANEL_MIN_HEIGHT))
        fireEvent.keyDown(separator, { key:'End' })
        expect(Number(separator.getAttribute('aria-valuenow'))).toBeGreaterThanOrEqual(shorter)
        fireEvent.doubleClick(separator)
        expect(window.localStorage.getItem(CATALOG_PANEL_STORAGE_KEY)).toBeNull()
        expect(separator).toHaveAttribute('aria-valuenow', String(initial))
    })

    it('dragging the top handle down makes the viewport smaller; persists only on release', () => {
        render(<Harness />)
        const separator = screen.getByRole('separator', {name:'Высота каталога'})
        const initial = Number(separator.getAttribute('aria-valuenow'))
        fireEvent.pointerDown(separator, { pointerId: 5, clientY: 100, button: 0 })
        fireEvent.pointerMove(separator, { pointerId: 5, clientY: 160 })
        expect(Number(separator.getAttribute('aria-valuenow'))).toBe(initial - 60)
        expect(window.localStorage.getItem(CATALOG_PANEL_STORAGE_KEY)).toBeNull()
        fireEvent.pointerUp(separator, { pointerId: 5, clientY: 160 })
        expect(window.localStorage.getItem(CATALOG_PANEL_STORAGE_KEY)).toBe(String(initial - 60))
    })

    it('pointer cancel restores the previous height without persisting changes', () => {
        render(<Harness />)
        const separator = screen.getByRole('separator', {name:'Высота каталога'})
        const initial = separator.getAttribute('aria-valuenow')
        fireEvent.pointerDown(separator, {pointerId: 2, clientY: 200, button: 0})
        fireEvent.pointerMove(separator, {pointerId: 2, clientY: 250})
        fireEvent.pointerCancel(separator, {pointerId: 2})
        expect(separator).toHaveAttribute('aria-valuenow', initial)
        expect(window.localStorage.getItem(CATALOG_PANEL_STORAGE_KEY)).toBeNull()
    })
})
