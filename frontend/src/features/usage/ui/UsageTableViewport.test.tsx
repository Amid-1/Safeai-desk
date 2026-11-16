// frontend/src/features/usage/ui/UsageTableViewport.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { UsageTableViewport } from './UsageTableViewport'

describe('UsageTableViewport', () => {
    afterEach(() => vi.restoreAllMocks())

    it('only adds a resize control when rows overflow; supports keyboard resize and reset', () => {
        vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(300)
        vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(900)
        render(<UsageTableViewport table hasRows loading={false}>
            <table><tbody><tr><td>Row</td></tr></tbody></table>
        </UsageTableViewport>)
        const region = screen.getByRole('region', { name: 'Таблица результатов' })
        const handle = screen.getByRole('separator', { name: 'Высота таблицы' })
        expect(handle).toHaveAttribute('aria-valuenow', '300')
        fireEvent.keyDown(handle, { key: 'ArrowUp' })
        expect(region.style.getPropertyValue('--usage-table-max-height')).toBe('268px')
        fireEvent.keyDown(handle, { key: 'Home' })
        expect(region.style.getPropertyValue('--usage-table-max-height')).toBe('120px')
        fireEvent.doubleClick(handle)
        expect(region.style.getPropertyValue('--usage-table-max-height')).toBe('')
    })

    it('keeps short tables compact without a resize control', () => {
        vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(200)
        vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(200)
        render(<UsageTableViewport table hasRows loading={false}>One row</UsageTableViewport>)
        expect(screen.queryByRole('separator')).not.toBeInTheDocument()
    })
})
