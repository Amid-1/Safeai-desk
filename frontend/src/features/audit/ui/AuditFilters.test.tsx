// frontend/src/features/audit/ui/AuditFilters.test.tsx
// ============================================================
// frontend/src/features/audit/ui/AuditFilters.test.tsx
// ============================================================
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import AuditFilters from './AuditFilters'
import type { AuditDraftFilter } from './types'

const filter: AuditDraftFilter = {
    eventType: '', actorUserId: '', actorEmail: '',
    dateFrom: '2026-10-01', dateTo: '2026-10-19', targetOrganizationId: '',
}

function renderFilters() {
    const onFilterChange = vi.fn()
    const onDatePreset = vi.fn()
    const onApply = vi.fn()
    const onReset = vi.fn()
    render(<AuditFilters
        draftFilter={filter}
        eventTypes={['USER_LOGIN_FAILED', 'KNOWLEDGE_DOCUMENT_DOWNLOADED', 'USER_CREATED', 'MODEL_ROUTE_DENIED']}
        organizations={[]}
        actors={[]}
        superAdmin={false}
        loading={false}
        directoriesLoading={false}
        eventTypesError=""
        organizationsError=""
        actorsError=""
        filterError=""
        filtersDirty={true}
        onFilterChange={onFilterChange}
        onActorSearch={vi.fn().mockResolvedValue(undefined)}
        onOrganizationSearch={vi.fn().mockResolvedValue(undefined)}
        onDatePreset={onDatePreset}
        onApply={onApply}
        onReset={onReset}
    />)
    return { onFilterChange, onDatePreset, onApply, onReset }
}

describe('AuditFilters production UX', () => {
    it('filters accessible autocomplete suggestions without requesting events', () => {
        const { onApply } = renderFilters()
        fireEvent.change(screen.getByLabelText('Событие'), {
            target: { value: 'Скачан документ' },
        })
        expect(screen.getByRole('option', { name: /Базы знаний и документы.*Скачан документ/i })).toBeInTheDocument()
        expect(screen.queryByRole('option', { name: /Неудачная попытка входа/i })).not.toBeInTheDocument()
        expect(onApply).not.toHaveBeenCalled()
    })

    it('finds blocked AI requests using the old title without ё and selects by keyboard', () => {
        const { onFilterChange, onApply } = renderFilters()
        const input = screen.getByRole('combobox', { name: 'Событие' })
        fireEvent.change(input, { target: { value: 'маршрут модели отклонен' } })
        expect(screen.getByRole('option', { name: /AI-запрос заблокирован/ })).toBeInTheDocument()
        fireEvent.keyDown(input, { key: 'ArrowDown' })
        fireEvent.keyDown(input, { key: 'ArrowDown' })
        fireEvent.keyDown(input, { key: 'Enter' })
        expect(onFilterChange.mock.calls[0][0](filter)).toMatchObject({ eventType: 'MODEL_ROUTE_DENIED' })
        expect(onApply).not.toHaveBeenCalled()
    })

    it('highlights date preset and clears it on manual date changes', () => {
        const { onDatePreset } = renderFilters()
        const sevenDays = screen.getByRole('button', { name: '7 дней' })
        fireEvent.click(sevenDays)
        expect(sevenDays).toHaveAttribute('aria-pressed', 'true')
        expect(onDatePreset).toHaveBeenCalledWith('last7Days')
        fireEvent.change(screen.getByLabelText('Начало периода'), {
            target: { value: '2026-10-02' },
        })
        expect(sevenDays).toHaveAttribute('aria-pressed', 'false')
    })

    it('explains draft filters and preserves historical email search in advanced filters', () => {
        renderFilters()
        expect(screen.getByText(/результаты еще не обновлены/)).toBeInTheDocument()
        fireEvent.click(screen.getByText('Дополнительные фильтры'))
        expect(screen.getByLabelText(/Поиск по email в истории/)).toBeInTheDocument()
    })
})
