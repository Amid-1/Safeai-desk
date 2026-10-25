// frontend/src/features/admin-organizations/ui/AdminOrganizationDialogs.clarity.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { Organization } from '../../../entities/organization/api/organizationApi'
import { AdminOrganizationDialogs } from './AdminOrganizationDialogs'

vi.mock('../../../shared/ui/Modal', () => ({
    default: ({ title, children }: { title: string, children: ReactNode }) =>
        <section aria-label={title}>{children}</section>,
}))

const customer: Organization = {
    id: 'af08bde0-df58-411d-9c64-35638dbf1c0a', name: 'ООО «Ромашка»',
    enabled: true, type: 'TENANT', protected: false, version: 2,
    createdAt: '2026-08-05T20:46:16Z', updatedAt: '2026-10-20T16:00:00Z',
}
const platform: Organization = {
    ...customer, id: '00000000-0000-0000-0000-000000000001',
    type: 'PLATFORM', protected: true, name: 'SafeAI Platform',
}

function renderDetails(organization: Organization) {
    const onCloseDetails = vi.fn()
    const onDisable = vi.fn(async () => {})
    const onEnable = vi.fn()
    const onRename = vi.fn()
    render(<AdminOrganizationDialogs
        detailsOrganization={organization} detailsError=""
        renameOrganization={null} renameValue="" renameError=""
        disableDialog={null} enableOrganizationTarget={null} hasPendingAction={false}
        impactLoadingId={null}
        onCloseDetails={onCloseDetails} onCloseRename={vi.fn()}
        onRenameValueChange={vi.fn()} onSubmitRename={async () => {}}
        onCloseDisable={vi.fn()} onDisableConfirmationChange={vi.fn()}
        onConfirmDisable={async () => {}}
        onCloseEnable={vi.fn()} onConfirmEnable={async () => {}}
        onDisable={onDisable} onEnable={onEnable} onRename={onRename}
    />)
    return { onCloseDetails, onDisable, onEnable, onRename }
}

describe('Organization details: safe actions and explanations', () => {
    it('explains identifiers and version without enabling manual edits', () => {
        renderDetails(customer)
        expect(screen.getByText('ООО «Ромашка»')).toBeTruthy()
        expect(screen.getByText(/Включённые сотрудники этой организации могут входить/)).toBeTruthy()
        fireEvent.click(screen.getByText('Технические сведения и полный ID'))
        expect(screen.getByText(customer.id)).toBeTruthy()
        fireEvent.click(screen.getByLabelText('Версия организации: 2. Подробнее'))
        expect(screen.getByText(/Номер обновления записи/)).toBeTruthy()
    })

    it('closes details before starting existing disable impact check', () => {
        const actions = renderDetails(customer)
        fireEvent.click(screen.getByRole('button', { name: 'Отключить организацию' }))
        expect(actions.onCloseDetails).toHaveBeenCalledTimes(1)
        expect(actions.onDisable).toHaveBeenCalledTimes(1)
        expect(actions.onDisable).toHaveBeenCalledWith(customer)
    })


    it('opens rename using the existing callback after closing the details modal', () => {
        const actions = renderDetails(customer)
        fireEvent.click(screen.getByRole('button', { name: 'Переименовать' }))
        expect(actions.onCloseDetails).toHaveBeenCalledTimes(1)
        expect(actions.onRename).toHaveBeenCalledWith(customer)
    })

    it('opens enable confirmation for an inactive tenant without changing state directly', () => {
        const inactive: Organization = { ...customer, enabled: false }
        const actions = renderDetails(inactive)
        fireEvent.click(screen.getByRole('button', { name: 'Включить организацию' }))
        expect(actions.onCloseDetails).toHaveBeenCalledTimes(1)
        expect(actions.onEnable).toHaveBeenCalledWith(inactive)
        expect(screen.getByText(/Вход пользователей этой организации в SafeAI Desk заблокирован/)).toBeTruthy()
    })

    it('keeps all mutations unavailable for protected PLATFORM', () => {
        const actions = renderDetails(platform)
        expect(screen.queryByRole('button', { name: 'Отключить организацию' })).toBeNull()
        expect(screen.queryByRole('button', { name: 'Переименовать' })).toBeNull()
        expect(screen.getByText(/необходима для работы SafeAI Desk/)).toBeTruthy()
        expect(actions.onDisable).not.toHaveBeenCalled()
        expect(actions.onRename).not.toHaveBeenCalled()
    })
})

describe('Organization disabling confirmation remains mandatory', () => {
    function disableForm(confirmationName: string) {
        const onConfirmDisable = vi.fn(async () => {})
        const onCloseDisable = vi.fn()
        render(<AdminOrganizationDialogs
            detailsOrganization={null} detailsError=""
            renameOrganization={null} renameValue="" renameError=""
            disableDialog={{
                organization: customer,
                confirmationName,
                impact: {
                    organizationId: customer.id, organizationVersion: customer.version,
                    enabledUsers: 4, administrators: 1,
                    activeRefreshSessions: 2, activeChatOperations: 1,
                    startedPhysicalAttempts: 1, uncertainPhysicalAttempts: 3,
                    advisoryOnly: true,
                },
            }}
            enableOrganizationTarget={null} hasPendingAction={false} impactLoadingId={null}
            onCloseDetails={vi.fn()} onCloseRename={vi.fn()}
            onRenameValueChange={vi.fn()} onSubmitRename={async () => {}}
            onCloseDisable={onCloseDisable}
            onDisableConfirmationChange={vi.fn()}
            onConfirmDisable={onConfirmDisable}
            onCloseEnable={vi.fn()} onConfirmEnable={async () => {}}
            onDisable={async () => {}} onEnable={vi.fn()} onRename={vi.fn()}
        />)
        return { onConfirmDisable, onCloseDisable }
    }

    it('requires matching organization name and shows impact before disabling', () => {
        const actions = disableForm('другая организация')
        expect(screen.getByText('Включённых пользователей')).toBeTruthy()
        expect(screen.getByText('Действующих сеансов продления входа')).toBeTruthy()
        expect(screen.getByText('Незавершённых операций чата')).toBeTruthy()
        expect(screen.getByText('Начатых обращений к AI-провайдеру')).toBeTruthy()
        expect(screen.getByText('Обращений с неопределённым результатом')).toBeTruthy()
        expect(screen.getByText(/рассчитаны сервером автоматически/)).toBeTruthy()
        expect(screen.getByText(/могут быть историческими/)).toBeTruthy()
        const button = screen.getByRole('button', { name: 'Отключить организацию' }) as HTMLButtonElement
        expect(button.disabled).toBe(true)
        expect(actions.onConfirmDisable).not.toHaveBeenCalled()
    })

    it('permits confirmation only when the exact normalized organization name matches', () => {
        const actions = disableForm('ооо ромашка')
        const button = screen.getByRole('button', { name: 'Отключить организацию' }) as HTMLButtonElement
        expect(button.disabled).toBe(false)
        fireEvent.click(button)
        expect(actions.onConfirmDisable).toHaveBeenCalledTimes(1)
    })
})
