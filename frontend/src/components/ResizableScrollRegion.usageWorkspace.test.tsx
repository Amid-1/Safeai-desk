import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import ResizableScrollRegion from './ResizableScrollRegion'

function rect(height: number): DOMRect {
    return {
        x: 0, y: 0, width: 1100, height,
        left: 0, top: 0, right: 1100, bottom: height,
        toJSON: () => ({}),
    } as DOMRect
}

beforeEach(() => {
    window.localStorage.removeItem('safeai:usage-split-test')
    window.localStorage.removeItem('safeai:usage-split-test:manual')
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect')
        .mockImplementation(function (this: HTMLElement) {
            if (this.hasAttribute('data-resizable-upper-boundary')) {
                return rect(620)
            }
            if (this.classList.contains('resizable-scroll-region')) {
                return rect(900)
            }
            return rect(500)
        })
})

afterEach(() => {
    window.localStorage.removeItem('safeai:usage-split-test')
    window.localStorage.removeItem('safeai:usage-split-test:manual')
    vi.restoreAllMocks()
})

const upper = <div><span>Фильтры</span><span data-resizable-upper-boundary /></div>

function region(showLowerRequest = 0, showUpperRequest = 0, preserveUpperContent = false, viewportResetKey = "summary") {
    return (
        <ResizableScrollRegion
            storageKey="safeai:usage-split-test"
            label="результаты AI"
            upper={upper}
            preserveUpperContent={preserveUpperContent}
            defaultHeight={350}
            minHeight={170}
            maxHeight={4096}
            minUpperHeight={92}
            showLowerRequest={showLowerRequest}
            showUpperRequest={showUpperRequest}
            viewportResetKey={viewportResetKey}
        >
            <div>Таблица и график</div>
        </ResizableScrollRegion>
    )
}

function currentPanelHeight(): number {
    return Number(screen.getByRole('separator').getAttribute('aria-valuenow'))
}

describe('Usage analytics full-height split', () => {
    it('reaches navigation reserve, restores filter area, and supports keyboard/double click', async () => {
        const view = render(region())
        expect(currentPanelHeight()).toBe(350)

        view.rerender(region(1, 0))
        await waitFor(() => expect(currentPanelHeight()).toBe(808))

        view.rerender(region(1, 1))
        await waitFor(() => expect(currentPanelHeight()).toBe(170))

        const separator = screen.getByRole('separator')
        fireEvent.keyDown(separator, { key: 'End' })
        expect(currentPanelHeight()).toBe(808)
        fireEvent.keyDown(separator, { key: 'Home' })
        expect(currentPanelHeight()).toBe(170)
        fireEvent.doubleClick(separator)
        expect(currentPanelHeight()).toBe(350)
        expect(screen.getByText('Таблица и график')).toBeTruthy()
    })

    it('resets report scroll on tab change but preserves it on resize', () => {
        const view = render(region(0, 0, false, 'summary'))
        const viewport = view.container.querySelector<HTMLElement>(
            '.resizable-scroll-region__viewport',
        )
        expect(viewport).not.toBeNull()
        if (!viewport) throw new Error('Viewport not rendered')
        viewport.scrollTop = 280
        view.rerender(region(1, 0, false, 'summary'))
        expect(viewport.scrollTop).toBe(280)
        view.rerender(region(1, 0, false, 'models'))
        expect(viewport.scrollTop).toBe(0)
    })

    it('does not bypass semantic upper boundaries on other workspaces', async () => {
        const view = render(region(0, 0, true))
        // Marker bottom=620 and boundary padding=8 reserve 628px of 900px.
        expect(currentPanelHeight()).toBe(272)
        view.rerender(region(1, 0, true))
        await waitFor(() => expect(currentPanelHeight()).toBe(272))
        expect(screen.getByText('Фильтры')).toBeTruthy()
    })
})
