// frontend/src/features/usage/ui/AdminUsageControls.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AdminUsageControls } from './AdminUsageControls'
import type { ComponentProps } from 'react'

const noop = () => undefined
const props: ComponentProps<typeof AdminUsageControls> = {
    tab: 'daily', isSuperAdmin: false, scopeLabel: 'Моя организация',
    draftDateFrom: '2026-11-01', draftDateTo: '2026-11-16',
    draftModel: '', draftOrganizationId: '', draftOrganizationName: '',
    organizationOptions: [], organizationSearchPending: false, organizationSearchError: '',
    modelPickerOptions: [], modelOptionsLoading: false, modelOptionsError: '',
    filterError: '', filtersDirty: false, appliedModel: '',
    appliedDateFrom: '2026-11-01', appliedDateTo: '2026-11-16',
    loading: false, filtersOpen: false, onToggleFilters: noop,
    onDateFromChange: noop, onDateToChange: noop, onModelChange: noop,
    onOrganizationQueryChange: noop, onOrganizationChange: noop,
    onApplyFilters: noop, onResetFilters: noop, onSelectTab: noop,
}

describe('Usage controls layout', () => {
    it('keeps applied filters and tabs visible while editing is collapsed', () => {
        const toggle = vi.fn()
        const view = render(<AdminUsageControls {...props} onToggleFilters={toggle} />)
        expect(view.container.querySelector('#usage-filter-editor')).toHaveAttribute('hidden')
        expect(screen.getByText('01.11.2026 — 16.11.2026')).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'Изменить фильтры'}))
        expect(toggle).toHaveBeenCalledOnce()
        view.rerender(<AdminUsageControls {...props} filtersOpen onToggleFilters={toggle} />)
        expect(view.container.querySelector('#usage-filter-editor')).not.toHaveAttribute('hidden')
        expect(screen.getByLabelText('Начало периода')).toHaveValue('2026-11-01')
        expect(screen.getByRole('tab', {name: 'По дням'})).toHaveAttribute('aria-selected', 'true')
        fireEvent.click(screen.getByRole('button', {name: 'Свернуть фильтры'}))
        expect(toggle).toHaveBeenCalledTimes(2)
    })
    it('warns about unapplied drafts even with collapsed filters', () => {
        render(<AdminUsageControls {...props} filtersDirty draftDateFrom="2026-11-03" />)
        expect(screen.getByRole('status')).toHaveTextContent('последний применённый отчёт')
        expect(screen.getByText('01.11.2026 — 16.11.2026')).toBeVisible()
    })
})
