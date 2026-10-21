// frontend/src/features/admin-organizations/ui/AdminOrganizationsView.test.tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import type { Organization } from '../../../entities/organization/api/organizationApi'
import { AdminOrganizationsView } from './AdminOrganizationsView'

vi.mock('../../../shared/ui/Modal', () => ({
    default: ({ title, children }: {title: string, children: ReactNode}) =>
        <section aria-label={title}>{children}</section>,
}))

vi.mock('../../../shared/ui/ResizableScrollRegion', () => ({
    default: ({ upper, children, footer }: {upper: ReactNode, children: ReactNode, footer: ReactNode}) =>
        <div>{upper}{children}{footer}</div>,
}))

const customer: Organization = {
    id: 'af08bde0-df58-411d-9c64-35638dbf1c0a',
    name: 'ООО «Ромашка»', enabled: true, type: 'TENANT', protected: false,
    version: 0, createdAt: '2026-08-05T20:46:16Z', updatedAt: '2026-08-05T20:46:16Z',
}
const platform: Organization = {
    ...customer, id: '00000000-0000-0000-0000-000000000001',
    name: 'SafeAI Platform', type: 'PLATFORM', protected: true,
}

function renderView(options: { rows?: Organization[], page?: number, pages?: number } = {}) {
    const onDisable = vi.fn(async () => {})
    const onEnable = vi.fn()
    const onDetails = vi.fn(async () => {})
    const onRename = vi.fn()
    const onPageChange = vi.fn()
    const onNameChange = vi.fn()
    const onCreate = vi.fn(async () => {})
    render(<AdminOrganizationsView
        organizations={options.rows ?? [customer, platform]}
        loadError="" mutationError="" createError="" success=""
        loading={false} creating={false} hasPendingAction={false}
        page={options.page ?? 0} totalPages={options.pages ?? 1}
        name="" detailsLoadingId={null} impactLoadingId={null}
        onNameChange={onNameChange} onCreate={onCreate}
        onReload={vi.fn()} onDetails={onDetails} onRename={onRename}
        onDisable={onDisable} onEnable={onEnable} onPageChange={onPageChange}
    />)
    return { onDisable, onEnable, onDetails, onRename, onPageChange, onCreate, onNameChange }
}

beforeEach(() => { vi.restoreAllMocks() })

describe('AdminOrganizationsView: readable UI and protected actions', () => {
    it('explains creation defaults without offering editable system type or initial status', () => {
        renderView({ rows: [customer] })
        expect(screen.getByText('Тип:')).toBeTruthy()
        expect(screen.getByText('Статус после создания:')).toBeTruthy()
        expect(screen.getByText(/только SUPER_ADMIN/)).toBeTruthy()
        expect(screen.queryByRole('combobox', { name: /тип организации/i })).toBeNull()
        expect(screen.getByText(/постоянное удаление организаций здесь не предусмотрено/)).toBeTruthy()
    })

    it.each([
        ['ID', 'ID организации', /поиска событий аудита/],
        ['Тип', 'Тип организации', /Тип устанавливает сервер/],
        ['Статус', 'Статус организации', /не означает, что пользователь сейчас в сети/],
        ['Версия', 'Версия записи', /не версия программы/],
    ])('opens real help dialog for column %s', (column, title, explanation) => {
        renderView({ rows: [customer] })
        fireEvent.click(screen.getByRole('button', { name: `Справка: ${column}` }))
        expect(screen.getByRole('region', { name: title })).toBeTruthy()
        expect(screen.getByText(explanation)).toBeTruthy()
    })

    it('uses understandable terms without exposing implementation jargon', () => {
        renderView()
        expect(screen.getByRole('heading', { name: 'Создать организацию' })).toBeTruthy()
        expect(screen.getByText('УПРАВЛЕНИЕ ОРГАНИЗАЦИЯМИ')).toBeTruthy()
        expect(screen.queryByText('Создать tenant')).toBeNull()
        expect(screen.getByText('Показано в списке')).toBeTruthy()
        expect(screen.getByText('Всего организаций: 2')).toBeTruthy()
    })

    it('shows compact UUID and provides exact clipboard copy', async () => {
        const writeText = vi.fn(async () => {})
        Object.defineProperty(navigator, 'clipboard', {
            configurable: true, value: { writeText },
        })
        renderView({ rows: [customer] })
        expect(screen.getByText('af08bde0…')).toBeTruthy()
        expect(screen.queryByText(customer.id)).toBeNull()
        fireEvent.click(screen.getByRole('button', { name: `Скопировать ID организации ${customer.id}` }))
        await waitFor(() => expect(writeText).toHaveBeenCalledWith(customer.id))
        expect(screen.getByText('Скопировано')).toBeTruthy()
    })

    it('routes a tenant status click through the existing confirmation handler', () => {
        const actions = renderView({ rows: [customer] })
        expect(screen.getByText('Отключить')).toBeTruthy()
        fireEvent.click(screen.getByRole('button', { name: /Отключить организацию ООО «Ромашка»/ }))
        expect(actions.onDisable).toHaveBeenCalledTimes(1)
        expect(actions.onDisable).toHaveBeenCalledWith(customer)
        expect(actions.onEnable).not.toHaveBeenCalled()
    })

    it('routes an inactive tenant through enable confirmation; never auto-mutates', () => {
        const inactive = { ...customer, enabled: false }
        const actions = renderView({ rows: [inactive] })
        expect(screen.getByText('Включить')).toBeTruthy()
        fireEvent.click(screen.getByRole('button', { name: /Включить организацию ООО «Ромашка»/ }))
        expect(actions.onEnable).toHaveBeenCalledTimes(1)
        expect(actions.onEnable).toHaveBeenCalledWith(inactive)
        expect(actions.onDisable).not.toHaveBeenCalled()
    })

    it('does not expose status or rename actions for protected PLATFORM', () => {
        const actions = renderView({ rows: [platform] })
        expect(screen.queryByRole('button', { name: /изменить статус/i })).toBeNull()
        expect(screen.queryByRole('button', { name: 'Переименовать' })).toBeNull()
        expect(screen.getByText('Защищённая системная организация')).toBeTruthy()
        expect(actions.onDisable).not.toHaveBeenCalled()
        expect(actions.onEnable).not.toHaveBeenCalled()
    })

    it('explains type and optimistic version without pretending they are editable', () => {
        renderView({ rows: [customer] })
        fireEvent.click(screen.getByLabelText('Тип организации: клиентская. Подробнее'))
        expect(screen.getByText(/Тип определяется системой при создании/)).toBeTruthy()
        fireEvent.click(screen.getByLabelText('Версия организации: 0. Подробнее'))
        expect(screen.getByText(/Номер обновления записи/)).toBeTruthy()
    })


    it('keeps creating a client organization tied to the existing submit callback', () => {
        const actions = renderView()
        const input = screen.getByPlaceholderText('Например, ООО «Ромашка»')
        fireEvent.change(input, { target: { value: 'ООО «Новая»' } })
        expect(actions.onNameChange).toHaveBeenCalledWith('ООО «Новая»')
        // The controlled form remains disabled until its owner hook supplies nonempty name.
        const createButton = screen.getByRole('button', { name: 'Создать организацию' }) as HTMLButtonElement
        expect(createButton.disabled).toBe(true)
        expect(actions.onCreate).not.toHaveBeenCalled()
    })

    it('does not offer disabled pagination on a single page', () => {
        renderView({ rows: [customer], pages: 1 })
        expect(screen.queryByRole('button', { name: 'Предыдущая' })).toBeNull()
        expect(screen.queryByRole('button', { name: 'Следующая' })).toBeNull()
    })

    it('offers pagination only when multiple pages exist, respecting boundaries', () => {
        const actions = renderView({ rows: [customer], pages: 3, page: 0 })
        const previous = screen.getByRole('button', { name: 'Предыдущая' }) as HTMLButtonElement
        expect(previous.disabled).toBe(true)
        fireEvent.click(screen.getByRole('button', { name: 'Следующая' }))
        expect(actions.onPageChange).toHaveBeenCalledTimes(1)
        expect(actions.onPageChange).toHaveBeenCalledWith(1)
    })
})
