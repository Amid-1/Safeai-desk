// frontend/src/features/usage/ui/UsageTableViewport.test.tsx
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { UsageTableViewport } from './UsageTableViewport'

describe('UsageTableViewport', () => {
    afterEach(() => {
        vi.restoreAllMocks()
        vi.unstubAllGlobals()
    })

    it('keeps the table viewport independent of the shared workspace separator', () => {
        vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(300)
        vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(900)
        render(<UsageTableViewport table hasRows loading={false}>
            <table><tbody><tr><td>Row</td></tr></tbody></table>
        </UsageTableViewport>)
        const region = screen.getByRole('region', { name: 'Таблица результатов' })
        expect(screen.queryByRole('separator')).not.toBeInTheDocument()
        expect(region).toHaveClass('usage-data-viewport')
    })

    it('keeps short tables compact without a resize control', () => {
        vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(200)
        vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(200)
        render(<UsageTableViewport table hasRows loading={false}>One row</UsageTableViewport>)
        expect(screen.queryByRole('separator')).not.toBeInTheDocument()
    })

    it('bounds chart selections by the live scrollport and updates on resize', () => {
        let height = 135
        vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(() => height)
        let notify = () => {}
        const observe = vi.fn()
        const disconnect = vi.fn()
        vi.stubGlobal('ResizeObserver', class {
            constructor(callback: () => void) { notify = callback }
            observe = observe
            disconnect = disconnect
        })
        const {rerender, unmount} = render(<UsageTableViewport table={false} hasRows loading={false}>Chart</UsageTableViewport>)
        const region = screen.getByRole('region', {name: 'График результатов'})
        const property = '--usage-chart-viewport-height'
        expect(region.style.getPropertyValue(property)).toBe('135px')
        expect(observe).toHaveBeenCalledWith(region)
        height = 220
        notify()
        expect(region.style.getPropertyValue(property)).toBe('220px')
        height = 0
        notify()
        expect(region.style.getPropertyValue(property)).toBe('')
        height = 170
        window.dispatchEvent(new Event('resize'))
        expect(region.style.getPropertyValue(property)).toBe('170px')
        rerender(<UsageTableViewport table={false} hasRows={false} loading>Chart</UsageTableViewport>)
        expect(disconnect).toHaveBeenCalledOnce()
        expect(region.style.getPropertyValue(property)).toBe('')
        window.dispatchEvent(new Event('resize'))
        expect(region.style.getPropertyValue(property)).toBe('')
        unmount()
    })

    it('uses window resize when ResizeObserver is unavailable and never bounds a table', () => {
        vi.stubGlobal('ResizeObserver', undefined)
        let height = 90
        vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(() => height)
        const {rerender} = render(<UsageTableViewport table={false} hasRows loading={false}>Chart</UsageTableViewport>)
        const region = screen.getByRole('region', {name: 'График результатов'})
        expect(region.style.getPropertyValue('--usage-chart-viewport-height')).toBe('90px')
        height = 160
        window.dispatchEvent(new Event('resize'))
        expect(region.style.getPropertyValue('--usage-chart-viewport-height')).toBe('160px')
        rerender(<UsageTableViewport table hasRows loading={false}>Table</UsageTableViewport>)
        expect(region.style.getPropertyValue('--usage-chart-viewport-height')).toBe('')
    })
})
