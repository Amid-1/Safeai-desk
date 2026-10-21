// ============================================================
// frontend/src/components/admin/audit/AuditFilters.test.tsx
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
        eventTypes={['USER_LOGIN_FAILED', 'KNOWLEDGE_DOCUMENT_DOWNLOADED', 'USER_CREATED']}
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
    it('filters an accessible grouped native selector without requesting events', () => {
        const { onApply } = renderFilters()
        fireEvent.change(screen.getByLabelText('Поиск типа события'), {
            target: { value: 'Скачан документ' },
        })
        const select = screen.getByLabelText('Тип события') as HTMLSelectElement
        expect(select.querySelector('optgroup[label="Базы знаний и документы"]')).not.toBeNull()
        expect(select.querySelector('option[value="KNOWLEDGE_DOCUMENT_DOWNLOADED"]')).not.toBeNull()
        expect(select.querySelector('option[value="USER_LOGIN_FAILED"]')).toBeNull()
        expect(onApply).not.toHaveBeenCalled()
    })

    it('highlights date preset and clears it on manual date changes', () => {
        const { onDatePreset } = renderFilters()
        const sevenDays = screen.getByRole('button', { name: '7 дней' })
        fireEvent.click(sevenDays)
        expect(sevenDays).toHaveAttribute('aria-pressed', 'true')
        expect(onDatePreset).toHaveBeenCalledWith('last7Days')
        fireEvent.change(screen.getByLabelText('Дата с'), {
            target: { value: '2026-10-02' },
        })
        expect(sevenDays).toHaveAttribute('aria-pressed', 'false')
    })

    it('explains draft filters and keeps the email prefix filter visible', () => {
        renderFilters()
        expect(screen.getByText(/результаты еще не обновлены/)).toBeInTheDocument()
        expect(screen.getByLabelText('Email инициатора или префикс')).toBeInTheDocument()
    })
})
