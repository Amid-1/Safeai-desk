// frontend/src/features/audit/ui/AuditIntegrityPanel.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getAuditIntegrity, parseIntegrityStatus } from '../api/auditIntegrityApi'
import AuditIntegrityPanel from './AuditIntegrityPanel'

vi.mock('../api/auditIntegrityApi', async importOriginal => ({
    ...await importOriginal<typeof import('../api/auditIntegrityApi')>(), getAuditIntegrity: vi.fn(),
}))

const api = vi.mocked(getAuditIntegrity)
const verified = () => {
    const now = Date.now()
    return parseIntegrityStatus({ state: 'VERIFIED', checkedEvents: 165, violations: 0, unanchoredEvents: 0,
        checkedAt: new Date(now).toISOString(), freshUntil: new Date(now + 900_000).toISOString(), archiveStatus: 'AVAILABLE' })
}

beforeEach(() => api.mockReset())

describe('Независимая целостность аудита', () => {
    it('отображает подтверждённые данные и убирает их после ошибки обновления', async () => {
        api.mockResolvedValueOnce(verified()).mockRejectedValueOnce(new Error('network'))
        render(<AuditIntegrityPanel />)
        expect(await screen.findByText('Целостность подтверждена')).toBeVisible()
        fireEvent.click(screen.getByText('Показать параметры независимой проверки'))
        expect(screen.getByText('165')).toBeVisible()
        expect(screen.getByText('WORM COMPLIANCE')).toBeVisible()
        fireEvent.click(screen.getByRole('button', { name: 'Обновить состояние' }))
        expect(await screen.findByText('Не удалось получить состояние проверки')).toBeVisible()
        expect(screen.queryByText('165')).not.toBeInTheDocument()
        expect(screen.queryByText('WORM COMPLIANCE')).not.toBeInTheDocument()
    })

    it('не показывает нули при отсутствии настроенного проверяющего сервиса', async () => {
        api.mockResolvedValue(parseIntegrityStatus({ state: 'NOT_CONFIGURED', archiveStatus: 'UNKNOWN' }))
        render(<AuditIntegrityPanel />)
        expect(await screen.findByText('Независимая проверка не настроена')).toBeVisible()
        expect(screen.queryByText('0')).not.toBeInTheDocument()
        expect(screen.queryByText('Целостность подтверждена')).not.toBeInTheDocument()
    })

    it('не скрывает нарушения при компактном режиме', async () => {
        api.mockResolvedValue({ ...verified(), state: 'VIOLATION', violations: 1 })
        render(<AuditIntegrityPanel compact />)
        expect(await screen.findByText('Обнаружены нарушения целостности')).toBeVisible()
        expect(screen.getByText(/Сохраните доказательства/)).toBeVisible()
        expect(screen.getByText('Параметры проверки')).toBeInTheDocument()
    })

    it('не признаёт просроченный отчёт актуальным', async () => {
        const now = Date.now()
        api.mockResolvedValue({ ...verified(), checkedAt: new Date(now - 900_001).toISOString(), freshUntil: new Date(now - 1).toISOString() })
        render(<AuditIntegrityPanel />)
        expect(await screen.findByText('Последняя проверка устарела')).toBeVisible()
        expect(screen.queryByText('165')).not.toBeInTheDocument()
    })

    it('отбрасывает противоречивые, неизвестные и повреждённые ответы', () => {
        const good = verified()
        for (const bad of [null, { ...good, violations: 1 }, { ...good, unanchoredEvents: 1 },
            { ...good, checkedEvents: -1 }, { ...good, checkedAt: 'invalid' }, { ...good, archiveStatus: 'UNKNOWN' },
            { ...good, state: 'FAKE' }, { ...good, state: 'UNAVAILABLE' }]) {
            expect(() => parseIntegrityStatus(bad)).toThrow()
        }
    })
})
