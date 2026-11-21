// frontend/src/features/audit/ui/AuditSummaryPanel.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import AuditSummaryPanel from './AuditSummaryPanel'

describe('AuditSummaryPanel', () => {
    it('показывает серверные значения и позволяет открыть даже нулевую карточку', () => {
        const metric = vi.fn(), category = vi.fn()
        render(<AuditSummaryPanel summary={{ total: 159, successful: 145, denied: 0, critical: 5 }}
            loading={false} error="" disabled={false} onMetric={metric} onCategory={category} onRetry={vi.fn()} />)
        expect(screen.getByRole('button', { name: /Всего событий 159/ })).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: /Отказы 0/ }))
        expect(metric).toHaveBeenCalledWith('DENIED')
        fireEvent.click(screen.getByRole('button', { name: 'Безопасность' }))
        expect(category).toHaveBeenCalledWith('SECURITY')
    })

    it('при ошибке показывает неизвестное значение, а не фиктивный ноль', () => {
        const retry = vi.fn()
        render(<AuditSummaryPanel summary={null} loading={false} error="network" disabled={false}
            onMetric={vi.fn()} onCategory={vi.fn()} onRetry={retry} />)
        expect(screen.getByRole('button', { name: /Всего событий/ })).toBeDisabled()
        expect(screen.getByText(/Сводка недоступна/)).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Обновить сводку' }))
        expect(retry).toHaveBeenCalledOnce()
    })
})
