// frontend/src/features/audit/ui/AuditDetailsModal.test.tsx
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import type { AuditEvent } from '../api/adminAuditApi'
import AuditDetailsModal from './AuditDetailsModal'
import AuditTable from './AuditTable'

const decisionId = 'e933230e-1ef2-47e5-ab06-4b67adb82393'
const event: AuditEvent = {
    id: '91828372-0b0b-48ad-b64a-517b1a8e1b21',
    targetOrganizationId: '28f404ae-e20b-47e2-a2d1-d7fb57b05aca',
    targetOrganizationName: 'Organization A',
    actorUserId: '0f067094-253c-4cb5-9cc1-0bd594c19cb1',
    actorOrganizationId: '28f404ae-e20b-47e2-a2d1-d7fb57b05aca',
    actorEmail: 'admin-a@example.test', actorDisplayName: 'ADMIN-A',
    eventType: 'MODEL_ROUTE_DENIED',
    details: { reason: 'MODEL_NOT_ALLOWED', outcome: 'DENIED', decisionId, monthlyCommitmentState: 'NOT_EVALUATED' },
    detailsTruncated: false, detailsInvalid: false, createdAt: '2026-11-15T17:39:20Z',
}

describe('Журнал событий — представление и диагностика', () => {
    it('показывает причину до технических деталей и передаёт номер решения, не пользователя', () => {
        render(<AuditDetailsModal event={event} onClose={vi.fn()} />)
        expect(screen.getByRole('heading', { name: 'AI-запрос заблокирован' })).toBeInTheDocument()
        expect(screen.getByText(/Модель не разрешена для вашей организации/)).toBeInTheDocument()
        expect(screen.getByText('Не выполнялась')).toBeInTheDocument()
        expect(screen.getByRole('link', { name: 'Открыть диагностику запроса' }))
            .toHaveAttribute('href', '/admin/models?section=diagnostics&decisionId=' + decisionId)
        expect(screen.getByText('Технические подробности').closest('details')).not.toHaveAttribute('open')
        const dialog = screen.getByRole('dialog')
        expect(dialog.querySelector('.modal-header')).toContainElement(screen.getByRole('button', { name: 'Закрыть окно' }))
        expect(dialog.querySelector('.modal-footer')).toContainElement(screen.getByRole('button', { name: 'Закрыть' }))
    })

    it('не предлагает диагностику без корректного серверного номера решения', () => {
        render(<AuditDetailsModal event={{ ...event, details: { decisionId: event.actorUserId?.slice(0, 8) ?? '' } }} onClose={vi.fn()} />)
        expect(screen.queryByRole('link', { name: 'Открыть диагностику запроса' })).not.toBeInTheDocument()
    })

    it('показывает организацию только SUPER_ADMIN и сохраняет выбор записи', () => {
        const open = vi.fn()
        const { rerender } = render(<AuditTable events={[event]} organizations={[]} superAdmin={false} onOpenDetails={open} />)
        expect(screen.queryByRole('columnheader', { name: 'Организация' })).not.toBeInTheDocument()
        expect(screen.queryByText('MODEL_ROUTE_DENIED')).not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Подробнее' }))
        expect(open).toHaveBeenCalledWith(event)
        rerender(<AuditTable events={[event]} organizations={[]} superAdmin onOpenDetails={open} />)
        expect(screen.getByRole('columnheader', { name: 'Организация' })).toBeInTheDocument()
        expect(screen.getByText('Organization A')).toBeInTheDocument()
    })
})
