// frontend/src/features/audit/ui/AuditDetailsModal.test.tsx
import { render, screen, fireEvent, within } from '@testing-library/react'
import { afterEach, describe, it, expect, vi } from 'vitest'
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
    it('открывает точный фильтр связанной операции и копирует серверный идентификатор', async () => {
        const correlationId = 'b8dcc803-c776-42ff-8b2b-1175f4a229fd'
        const writeText = vi.fn().mockResolvedValue(undefined)
        vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
        render(<AuditDetailsModal event={{...event, correlationId}} onClose={vi.fn()} />)
        const link = screen.getByRole('link', { name: 'Найти связанные события' })
        const url = new URL(link.getAttribute('href')!, 'http://localhost')
        expect(url.pathname).toBe('/admin/audit')
        expect(url.searchParams.get('correlationId')).toBe(correlationId)
        expect(url.searchParams.has('dateFrom')).toBe(true)
        fireEvent.click(screen.getByRole('button', {name:'Копировать Correlation ID'}))
        expect(writeText).toHaveBeenCalledWith(correlationId)
        expect(await screen.findByText('Скопировано')).toBeVisible()
    })
    afterEach(() => vi.unstubAllGlobals())
    it.each(['USER_LOGIN_SUCCESS', 'USER_LOGOUT'])('не дублирует email для %s', (eventType) => {
        render(<AuditDetailsModal event={{ ...event, eventType,
            details: { email: '[OMITTED_UNAPPROVED_TEXT]' } }} onClose={vi.fn()} />)
        expect(screen.getByText('Пользователь / система')).toBeVisible()
        expect(screen.getAllByText(event.actorEmail!)).toHaveLength(1)
        expect(screen.queryByText('Email', { exact: true })).not.toBeInTheDocument()
        expect(screen.getByText(/\[OMITTED_UNAPPROVED_TEXT\]/)).not.toBeVisible()
    })

    it.each([
        ['[OMITTED_UNAPPROVED_TEXT]', 'Не сохранено по правилам безопасности'],
        ['[REDACTED]', 'Скрыто в целях безопасности'],
        ['unknown', 'Адрес не сохранён или недоступен'],
        [undefined, 'Адрес не сохранён или недоступен'],
        ['attempt@example.test', 'attempt@example.test'],
    ])('показывает доступные сведения о неудачном входе: %s', (email, expected) => {
        render(<AuditDetailsModal event={{ ...event, eventType: 'USER_LOGIN_FAILED',
            actorUserId: null, actorEmail: null, actorDisplayName: null, actorOrganizationId: null,
            details: email === undefined ? {} : { email } }} onClose={vi.fn()} />)
        expect(screen.getByText('Пользователь не установлен')).toBeVisible()
        expect(screen.getByText('Email при попытке входа')).toBeVisible()
        expect(screen.getByText(expected, { exact: true })).toBeVisible()
        expect(screen.getByText('Вход не выполнен')).toBeVisible()
    })

    it('переводит маркеры во всех обычных полях, оставляя оригинал в отчёте', async () => {
        const writeText = vi.fn().mockResolvedValue(undefined)
        vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
        const details = { title: '[MAX_DEPTH]', documentName: '[OMITTED_UNAPPROVED_TEXT]',
            provider: '[BUDGET_EXCEEDED]', roles: ['USER', '[REDACTED]'],
            fileName: '[UNSUPPORTED_TYPE:java.lang.Object]', resolvedModel: '[NUMBER_TOO_LARGE]',
            name: '[NON_FINITE_NUMBER]' }
        render(<AuditDetailsModal event={{ ...event, eventType: 'AI_RESPONSE_RECEIVED', details }} onClose={vi.fn()} />)
        for (const value of ['Данные ограничены по глубине', 'Не сохранено по правилам безопасности',
            'Данные ограничены по объёму', 'USER, Скрыто в целях безопасности',
            'Неподдерживаемый формат данных', 'Число превышает допустимый размер', 'Некорректное числовое значение']) {
            expect(screen.getByText(value, { exact: true })).toBeVisible()
        }
        expect(screen.getByText(/\[MAX_DEPTH\]/)).not.toBeVisible()
        fireEvent.click(screen.getByText('Сведения для технической поддержки'))
        fireEvent.click(screen.getByRole('button', { name: 'Копировать данные для поддержки' }))
        expect(JSON.parse(writeText.mock.calls[0][0]).details).toEqual(details)
        expect(await screen.findByText('Данные скопированы')).toBeVisible()
    })

    it('не теряет сохранённый email старой записи без снимка пользователя', () => {
        render(<AuditDetailsModal event={{ ...event, eventType: 'USER_LOGIN_SUCCESS', actorEmail: null,
            details: { email: 'historical@example.test' } }} onClose={vi.fn()} />)
        expect(screen.getByText('Email на момент события')).toBeVisible()
        expect(screen.getByText('historical@example.test', { exact: true })).toBeVisible()
    })
    it('показывает причину до технических деталей и передаёт номер решения, не пользователя', () => {
        render(<AuditDetailsModal event={event} onClose={vi.fn()} />)
        expect(screen.getByRole('heading', { name: 'AI-запрос заблокирован' })).toBeInTheDocument()
        expect(screen.getByText(/Модель не разрешена для вашей организации/)).toBeInTheDocument()
        expect(screen.getByText('Не выполнялась')).toBeInTheDocument()
        expect(screen.getByRole('link', { name: 'Открыть диагностику запроса' }))
            .toHaveAttribute('href', '/admin/models?section=diagnostics&decisionId=' + decisionId)
        expect(screen.getByText('Сведения для технической поддержки').closest('details')).not.toHaveAttribute('open')
        const dialog = screen.getByRole('dialog')
        expect(dialog.querySelector('.modal-header')).toContainElement(screen.getByRole('button', { name: 'Закрыть окно' }))
        expect(dialog.querySelector('.modal-footer')).toContainElement(screen.getByRole('button', { name: 'Закрыть' }))
    })

    it('отображает сведения об ответе ИИ и не повторяет пользователя или организацию', () => {
        render(<AuditDetailsModal event={{ ...event, eventType: 'AI_RESPONSE_RECEIVED', details: {
            provider: 'mock', resolvedModel: 'mock-safeai', inputTokens: 9, outputTokens: 12,
            costUsd: 0, usageStatus: 'AVAILABLE', pricingStatus: 'FREE',
        } }} onClose={vi.fn()} />)
        expect(screen.getByRole('heading', { name: 'Получен ответ ИИ' })).toBeVisible()
        expect(screen.getByText('Тестовый провайдер (mock)')).toBeVisible()
        expect(screen.getByText('mock-safeai')).toBeVisible()
        expect(screen.getByText('9', { exact: true })).toBeVisible()
        expect(screen.getByText('12', { exact: true })).toBeVisible()
        expect(screen.getByText('0 USD')).toBeVisible()
        const support = screen.getByText('Сведения для технической поддержки')
        fireEvent.click(support)
        expect(screen.getAllByText('admin-a@example.test')).toHaveLength(1)
        expect(screen.getByText(event.id, { exact: true })).toBeVisible()
        expect(screen.queryByText(event.targetOrganizationId, { exact: true })).not.toBeInTheDocument()
        expect(screen.queryByText('AI_RESPONSE_RECEIVED', { exact: true })).not.toBeInTheDocument()
        expect(within(support.closest('details')!).getAllByRole('button')).toHaveLength(1)
        const json = screen.getByText(/"inputTokens": 9/)
        expect(json).not.toBeVisible()
        fireEvent.click(screen.getByText('Диагностический отчёт (JSON)'))
        expect(json).toBeVisible()
    })

    it('копирует единый JSON с различными организациями и полными идентификаторами', async () => {
        const writeText = vi.fn().mockResolvedValue(undefined)
        vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
        const actorOrganizationId = '12345678-1234-1234-1234-123456789012'
        render(<AuditDetailsModal event={{ ...event, actorOrganizationId, actorEmail: null, actorDisplayName: null }} onClose={vi.fn()} />)
        expect(screen.getByText('Пользователь без имени')).toBeVisible()
        fireEvent.click(screen.getByText('Сведения для технической поддержки'))
        expect(screen.queryByText(actorOrganizationId, { exact: true })).not.toBeInTheDocument()
        const button = screen.getByRole('button', { name: 'Копировать данные для поддержки' })
        fireEvent.click(button)
        expect(writeText).toHaveBeenCalledTimes(1)
        const copied = writeText.mock.calls[0][0] as string
        expect(JSON.parse(copied)).toEqual({
            reportVersion: 1, id: event.id, eventType: event.eventType, createdAt: event.createdAt,
            targetOrganizationId: event.targetOrganizationId, actorOrganizationId, actorUserId: event.actorUserId,
            detailsInvalid: false, detailsTruncated: false, details: event.details,
        })
        expect(await within(button.parentElement!).findByRole('status')).toHaveTextContent('Данные скопированы')
        fireEvent.click(screen.getByText('Диагностический отчёт (JSON)'))
        expect(screen.getByText(/"reportVersion": 1/).textContent).toBe(copied)
        fireEvent.click(screen.getByRole('button', { name: 'Копировать номер решения' }))
        expect(writeText).toHaveBeenCalledWith(decisionId)
    })

    it('сохраняет метаданные при неправильном JSON, не добавляя недоступные данные и личность', async () => {
        const writeText = vi.fn().mockResolvedValue(undefined)
        vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
        render(<AuditDetailsModal event={{ ...event, actorUserId: null, actorOrganizationId: null,
            detailsInvalid: true, detailsTruncated: true }} onClose={vi.fn()} />)
        fireEvent.click(screen.getByText('Сведения для технической поддержки'))
        fireEvent.click(screen.getByRole('button', { name: 'Копировать данные для поддержки' }))
        const copied = writeText.mock.calls[0][0] as string
        expect(JSON.parse(copied)).toMatchObject({ id: event.id, actorUserId: null, actorOrganizationId: null,
            detailsInvalid: true, detailsTruncated: true, details: null })
        expect(copied).not.toContain(event.actorEmail)
        expect(copied).not.toContain(event.actorDisplayName)
        expect(await screen.findByText('Данные скопированы')).toBeVisible()
    })

    it('не теряет доступные поля усечённого отчёта и сбрасывает статус при смене события', async () => {
        const writeText = vi.fn().mockResolvedValue(undefined)
        vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
        const partial = { ...event, detailsTruncated: true, details: { provider: 'mock' } }
        const { rerender } = render(<AuditDetailsModal event={partial} onClose={vi.fn()} />)
        fireEvent.click(screen.getByText('Сведения для технической поддержки'))
        fireEvent.click(screen.getByRole('button', { name: 'Копировать данные для поддержки' }))
        const copied = writeText.mock.calls[0][0] as string
        expect(JSON.parse(copied)).toMatchObject({ detailsTruncated: true, detailsInvalid: false, details: partial.details })
        expect(copied).not.toContain(event.actorEmail)
        expect(await screen.findByText('Данные скопированы')).toBeVisible()
        rerender(<AuditDetailsModal event={{ ...partial, id: decisionId }} onClose={vi.fn()} />)
        expect(screen.queryByText('Данные скопированы')).not.toBeInTheDocument()
        expect(screen.getByText('Сведения для технической поддержки').closest('details')).not.toHaveAttribute('open')
    })

    it('не подменяет отсутствующую стоимость и токены нулями, сообщает об ошибке копирования', async () => {
        vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
        render(<AuditDetailsModal event={{ ...event, eventType: 'AI_RESPONSE_RECEIVED', details: {
            usageStatus: 'MISSING', pricingStatus: 'UNPRICED', inputTokens: 0, costUsd: 0,
        } }} onClose={vi.fn()} />)
        expect(screen.getByText('Нет данных')).toBeVisible()
        expect(screen.getByText('Недоступна')).toBeVisible()
        expect(screen.queryByText('Входные токены')).not.toBeInTheDocument()
        expect(screen.queryByText('0 USD')).not.toBeInTheDocument()
        fireEvent.click(screen.getByText('Сведения для технической поддержки'))
        fireEvent.click(screen.getByRole('button', { name: 'Копировать данные для поддержки' }))
        expect(await screen.findByRole('status')).toHaveTextContent('Не удалось скопировать')
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
