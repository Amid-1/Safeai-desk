// frontend/src/entities/organization/ui/OrganizationDirectoryFilter.test.tsx
import { fireEvent, render, screen, waitFor, act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { OrganizationDirectoryItem } from '../api/organizationApi'
import { getOrganizationDetails, searchOrganizationDirectory } from '../api/organizationApi'
import { OrganizationDirectoryFilter } from './OrganizationDirectoryFilter'

vi.mock('../api/organizationApi', () => ({
    getOrganizationDetails: vi.fn(),
    searchOrganizationDirectory: vi.fn(),
}))

const searchMock = vi.mocked(searchOrganizationDirectory)
const detailsMock = vi.mocked(getOrganizationDetails)
const ENABLED: OrganizationDirectoryItem = {
    id: '11111111-1111-4111-8111-111111111111',
    name: 'Активная компания',
    type: 'TENANT',
    protected: false,
    enabled: true,
    version: 2,
}
const DISABLED: OrganizationDirectoryItem = {
    ...ENABLED,
    id: '22222222-2222-4222-8222-222222222222',
    name: 'Отключённая компания',
    enabled: false,
}
const PLATFORM: OrganizationDirectoryItem = {
    ...ENABLED,
    id: '00000000-0000-0000-0000-000000000001',
    name: 'SafeAI Platform',
    protected: true,
    type: 'PLATFORM',
}

function getSelect() {
    return screen.getByRole('combobox', { name: 'Организация' })
}

describe('OrganizationDirectoryFilter: server catalog and tenant selection', () => {
    beforeEach(() => {
        vi.resetAllMocks()
        const original = HTMLElement.prototype.getBoundingClientRect
        vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
            return this.classList.contains('usage-filter-combobox__control')
                ? DOMRect.fromRect({x: 24, y: 120, width: 260, height: 34})
                : original.call(this)
        })
        searchMock.mockResolvedValue([ENABLED, DISABLED, PLATFORM])
        detailsMock.mockResolvedValue({
            ...ENABLED,
            createdAt: '2026-11-01T10:00:00Z',
            updatedAt: '2026-11-01T11:00:00Z',
        })
    })
    afterEach(() => vi.restoreAllMocks())

    it('loads all kinds of organizations from the server and delegates the selection', async () => {
        const onChange = vi.fn()
        render(<OrganizationDirectoryFilter value="" onChange={onChange} disabled={false} />)

        expect(getSelect()).toHaveValue('Все организации')
        fireEvent.focus(getSelect())
        expect(screen.getByRole('status')).toHaveTextContent('Загрузка вариантов…')
        await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(4))
        expect(screen.getByRole('listbox').closest('.usage-filter-combobox__menu')?.parentElement).toBe(document.body)

        expect(screen.getByRole('option', { name: 'Отключённая компания (отключена)' }))
            .toBeInTheDocument()
        expect(screen.getByRole('option', { name: 'SafeAI Platform' })).toBeInTheDocument()
        expect(searchMock).toHaveBeenCalledWith('', 50, { signal: expect.any(AbortSignal) })
        expect(detailsMock).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('option', {name: 'Отключённая компания (отключена)'}))
        expect(onChange).toHaveBeenCalledOnce()
        expect(onChange).toHaveBeenCalledWith(DISABLED.id)
    })

    it('retains the selected tenant outside the current search page without duplicating it', async () => {
        searchMock.mockResolvedValue([])
        detailsMock.mockResolvedValue({
            ...DISABLED,
            createdAt: '2026-11-01T10:00:00Z',
            updatedAt: '2026-11-01T11:00:00Z',
        })
        const onChange = vi.fn()
        render(<OrganizationDirectoryFilter value={DISABLED.id} onChange={onChange} disabled={false} />)

        expect(getSelect()).toHaveValue('Выбранная организация')
        await waitFor(() => expect(getSelect()).toHaveValue('Отключённая компания (отключена)'))
        fireEvent.focus(getSelect())
        expect(screen.queryByRole('option', { name: 'Выбранная организация' })).toBeNull()
        expect(detailsMock).toHaveBeenCalledWith(DISABLED.id, { signal: expect.any(AbortSignal) })

        searchMock.mockResolvedValue([DISABLED])
        fireEvent.change(getSelect(), {
            target: { value: 'Отключённая' },
        })
        await waitFor(() => expect(searchMock).toHaveBeenCalledWith('Отключённая', 50, {
            signal: expect.any(AbortSignal),
        }))
        await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(1))
        expect(screen.getByRole('option', {name: 'Отключённая компания (отключена)'})).toHaveAttribute('aria-selected', 'true')
        expect(onChange).not.toHaveBeenCalled()
    })

    it('shows the server limit notice instead of implying a complete tenant catalog', async () => {
        const rows = Array.from({ length: 50 }, (_, index): OrganizationDirectoryItem => ({
            ...ENABLED,
            id: `11111111-1111-4111-8111-${String(index).padStart(12, '0')}`,
            name: `Компания ${index + 1}`,
        }))
        searchMock.mockResolvedValue(rows)
        render(<OrganizationDirectoryFilter value="" onChange={vi.fn()} disabled={false} />)

        fireEvent.focus(getSelect())
        await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(51))
        expect(screen.getByText('Уточните название для поиска других организаций.')).toBeInTheDocument()
        const helper = screen.getByText('Уточните название для поиска других организаций.')
        searchMock.mockResolvedValue([ENABLED])
        fireEvent.change(getSelect(), {target: {value: 'Активная'}})
        await waitFor(() => expect(screen.getByText('Поиск выполняется на сервере.')).toBe(helper))
        expect(getSelect()).toBeEnabled()
    })

    it('shows a failed directory request and recovers on the next server search', async () => {
        searchMock.mockRejectedValueOnce(new Error('private SQL diagnostic'))
        render(<OrganizationDirectoryFilter value="" onChange={vi.fn()} disabled={false} />)
        await waitFor(() => expect(screen.getByText('Не удалось загрузить каталог. Измените поиск.')).toBeInTheDocument())
        expect(screen.queryByText('private SQL diagnostic')).toBeNull()
        expect(screen.queryAllByRole('option')).toHaveLength(0)

        fireEvent.change(getSelect(), {
            target: { value: '%_!' },
        })
        expect(getSelect()).toHaveValue('%_!')
        await waitFor(() => expect(searchMock).toHaveBeenCalledWith('%_!', 50, {
            signal: expect.any(AbortSignal),
        }))
        await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(3))
        expect(screen.queryByText('Не удалось загрузить каталог. Измените поиск.')).toBeNull()
    })

    it('does not accept stale search results after a new query aborts the previous request', async () => {
        let resolveStale!: (rows: OrganizationDirectoryItem[]) => void
        const pending = new Promise<OrganizationDirectoryItem[]>(resolve => { resolveStale = resolve })
        searchMock.mockReturnValueOnce(pending).mockResolvedValue([PLATFORM])
        const { unmount } = render(
            <OrganizationDirectoryFilter value="" onChange={vi.fn()} disabled={false} />,
        )
        await waitFor(() => expect(searchMock).toHaveBeenCalledTimes(1))
        const signal = searchMock.mock.calls[0]?.[2]?.signal
        expect(signal?.aborted).toBe(false)

        fireEvent.change(getSelect(), {
            target: { value: 'Platform' },
        })
        expect(signal?.aborted).toBe(true)
        await act(async () => { resolveStale([DISABLED]); await pending })
        expect(screen.queryByRole('option', { name: /Отключённая компания/ })).toBeNull()
        await waitFor(() => expect(screen.getByRole('option', { name: 'SafeAI Platform' }))
            .toBeInTheDocument())

        const latestSignal = searchMock.mock.calls[1]?.[2]?.signal
        unmount()
        expect(latestSignal?.aborted).toBe(true)
    })

    it('preserves an unknown selected ID when its details cannot be retrieved', async () => {
        detailsMock.mockRejectedValue(new Error('not available'))
        searchMock.mockResolvedValue([])
        render(<OrganizationDirectoryFilter value={DISABLED.id} onChange={vi.fn()} disabled={false} />)
        await waitFor(() => expect(detailsMock).toHaveBeenCalledOnce())
        await waitFor(() => expect(searchMock).toHaveBeenCalledOnce())
        expect(getSelect()).toHaveValue('Выбранная организация')
        expect(detailsMock).toHaveBeenCalledWith(DISABLED.id, {signal: expect.any(AbortSignal)})
    })

    it('disables searchable input when the caller has no editing permission', () => {
        const onChange = vi.fn()
        const { unmount } = render(
            <OrganizationDirectoryFilter value="" onChange={onChange} disabled />,
        )
        expect(getSelect()).toBeDisabled()
        expect(onChange).not.toHaveBeenCalled()
        unmount()
    })
})
