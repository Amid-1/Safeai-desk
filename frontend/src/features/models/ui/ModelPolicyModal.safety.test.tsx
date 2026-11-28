// frontend/src/features/models/ui/ModelPolicyModal.safety.test.tsx
// Регрессии серверной проверки правил, блокировок и конкурентных запросов.

import {act, fireEvent, render, screen, waitFor} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import type {
    CreateOrganizationModelPolicyVersionRequest,
    ModelPolicyPreview,
} from '../../../entities/model/api/modelApi'
import {ModelPolicyModal} from './ModelPolicyModal'
import {ApiError} from '../../../shared/api/http'
import {
    createModelCatalogEntry,
    createOrganizationModelPolicy,
    createRuntimeModelStatus,
} from '../../../test/fixtures/modelControlPlaneTestFixtures'

const orgId = '00000000-0000-0000-0000-000000000001'
const policy = createOrganizationModelPolicy()
const runtime = createRuntimeModelStatus()
const entry = createModelCatalogEntry()
type Request = CreateOrganizationModelPolicyVersionRequest
const preview = (overrides: Partial<ModelPolicyPreview> = {}): ModelPolicyPreview => ({
    previewConfirmationId: '12345678-1234-4123-8123-123456789abc',
    confirmationExpiresAt: new Date(Date.now() + 300_000).toISOString(),
    organizationId: orgId,
    basePolicyVersion: 0,
    enabled: true,
    evaluatedAt: '2026-10-21T06:00:00Z',
    runtimeProvider: 'mock',
    runtimeModel: 'mock-safeai',
    automaticOutcome: 'ALLOWED',
    automaticReason: 'RUNTIME_ONLY_MATCH',
    automaticModelKey: entry.modelKey,
    executableModelCount: 1,
    wouldLockOutOrganization: false,
    models: [{
        modelKey: entry.modelKey,
        catalogVersion: 1,
        provider: 'mock',
        providerModelId: 'mock-safeai',
        lifecycle: 'ACTIVE',
        outcome: 'ALLOWED',
        reason: null,
        effectiveInputLimit: 64000,
        effectiveOutputLimit: 2048,
        pricingComplete: true,
        estimatedMaxCostUsd: '0',
        monthlyBudgetUsd: null,
        monthlyProjectedUsd: null,
        monthlyCostKnown: true,
        budgetExceeded: false,
    }],
    ...overrides,
})

function setup(opts: {
    pending?: boolean,
    onPreview?: (request: Request) => Promise<ModelPolicyPreview>,
    onSubmit?: (request: Request) => Promise<void>,
    onClose?: () => void,
} = {}) {
    const onPreview = opts.onPreview ?? vi.fn(async (request: Request) => preview({enabled: request.enabled}))
    const onSubmit = opts.onSubmit ?? vi.fn(async () => {})
    const onClose = opts.onClose ?? vi.fn()
    const utils = render(
        <ModelPolicyModal
            policy={policy} catalog={[entry]} effectiveCatalog={[entry]}
            runtime={runtime} organizationId={orgId} organizationName="Корпорация"
            pending={opts.pending ?? false} onClose={onClose}
            onPreview={onPreview} onSubmit={onSubmit}
        />,
    )
    return {onPreview, onSubmit, onClose, ...utils}
}

function enablePolicy() {
    fireEvent.click(screen.getByRole('checkbox', {name: 'Правила выключены'}))
}

function clickPreview() {
    fireEvent.click(screen.getByRole('button', {name: 'Проверить правила'}))
}

function clickSubmit() {
    const save = screen.getByRole('button', {name: 'Сохранить правила'}) as HTMLButtonElement
    // Test the controller guard as well as the disabled UI (direct form submission).
    if (save.disabled) fireEvent.submit(screen.getByRole('dialog').querySelector('form')!)
    else fireEvent.click(save)
}

describe('ModelPolicyModal: безопасность изменения правил', () => {
    it('не сохраняет выключенные правила без preview и отклоняет истёкшее подтверждение', async () => {
        const {onSubmit} = setup({onPreview: vi.fn(async () => preview({enabled: false,
            confirmationExpiresAt: new Date(Date.now() - 1_000).toISOString()}))})
        clickSubmit()
        expect(onSubmit).not.toHaveBeenCalled()
        clickPreview()
        await screen.findByText(/Сервер вернул проверку для другого состояния правил/)
        expect(screen.getByRole('button', {name: 'Сохранить правила'})).toBeDisabled()
    })

    it('после 409 требует новый preview, но сохраняет черновик', async () => {
        const onSubmit = vi.fn(async () => {throw new ApiError('Повторите проверку', {}, 409)})
        setup({onSubmit})
        clickPreview()
        await screen.findByText('Результат проверки правил')
        clickSubmit()
        await waitFor(() => expect(screen.getByRole('button', {name: 'Сохранить правила'})).toBeDisabled())
        clickSubmit()
        expect(onSubmit).toHaveBeenCalledTimes(1)
    })
    it('не сохраняет включённые правила без предварительной серверной проверки', () => {
        const {onSubmit} = setup()
        enablePolicy()
        expect(screen.getByRole('button', {name: 'Сохранить правила'})).toBeDisabled()
        clickSubmit()
        expect(onSubmit).not.toHaveBeenCalled()
        expect(screen.getByText(/Перед сохранением выполните/)).toBeInTheDocument()
    })

    it('разрешает включённую политику после актуальной разрешающей проверки', async () => {
        const {onSubmit, onPreview} = setup()
        enablePolicy()
        clickPreview()
        await screen.findByText('Результат проверки правил')
        clickSubmit()
        await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            enabled: true, expectedPreviousVersion: 0,
            previewConfirmationId: '12345678-1234-4123-8123-123456789abc', acknowledgeLockout: false,
        })))
        expect(onPreview).toHaveBeenCalledTimes(1)
    })

    it('аннулирует проверку при изменении любого поля черновика', async () => {
        const {onSubmit} = setup()
        enablePolicy()
        clickPreview()
        await screen.findByText('Результат проверки правил')
        fireEvent.click(screen.getByRole('tab', {name: /Лимиты/}))
        fireEvent.change(screen.getByLabelText('Максимум входных токенов'), {target: {value: '16384'}})
        fireEvent.click(screen.getByRole('tab', {name: /Проверка/}))
        expect(screen.getByText(/Правила изменены после предыдущей проверки/)).toBeInTheDocument()
        clickSubmit()
        expect(onSubmit).not.toHaveBeenCalled()
    })

    it.each([
        ['чужую организацию', {organizationId: '99999999-9999-9999-9999-999999999999'}],
        ['чужую версию', {basePolicyVersion: 1}],
        ['противоречащее состояние', {enabled: false}],
    ] as const)('отклоняет %s в ответе проверки', async (_label, mismatch) => {
        const {onSubmit} = setup({onPreview: vi.fn(async () => preview(mismatch))})
        enablePolicy()
        clickPreview()
        await screen.findByText('Сервер вернул проверку для другого состояния правил.')
        expect(screen.queryByText('Результат проверки правил')).not.toBeInTheDocument()
        clickSubmit()
        expect(onSubmit).not.toHaveBeenCalled()
    })

    it('отклоняет двойной запрос проверки во время незавершённого Promise', async () => {
        let resolve!: (value: ModelPolicyPreview) => void
        const onPreview = vi.fn((_request: Request) => new Promise<ModelPolicyPreview>(r => {resolve = r}))
        const {onSubmit} = setup({onPreview})
        enablePolicy()
        clickPreview()
        expect(screen.getByRole('button', {name: 'Проверяем...'})).toBeDisabled()
        fireEvent.click(screen.getByRole('button', {name: 'Проверяем...'}))
        expect(onPreview).toHaveBeenCalledTimes(1)
        expect(onSubmit).not.toHaveBeenCalled()
        await act(async () => resolve(preview()))
        await screen.findByText('Результат проверки правил')
    })

    it('отклоняет двойное сохранение до завершения первого Promise', async () => {
        let resolve!: () => void
        const onSubmit = vi.fn((_request: Request) => new Promise<void>(r => {resolve = r}))
        setup({onSubmit})
        clickPreview()
        await screen.findByText('Результат проверки правил')
        clickSubmit()
        clickSubmit()
        expect(onSubmit).toHaveBeenCalledTimes(1)
        await act(async () => resolve())
    })

    it('не запускает preview, сохранение и закрытие при внешнем pending', () => {
        const {onPreview, onSubmit, onClose} = setup({pending: true})
        expect(screen.getByRole('button', {name: 'Проверить правила'})).toBeDisabled()
        expect(screen.getByRole('button', {name: 'Сохраняем...'})).toBeDisabled()
        expect(screen.getByRole('button', {name: /Закрытие недоступно/})).toBeDisabled()
        clickPreview()
        expect(onPreview).not.toHaveBeenCalled()
        expect(onSubmit).not.toHaveBeenCalled()
        expect(onClose).not.toHaveBeenCalled()
    })

    it('не сохраняет lockout без явного подтверждения и принимает подтверждение только для проверенного черновика', async () => {
        const onSubmit = vi.fn(async (_request: Request) => {})
        setup({onPreview: vi.fn(async () => preview({
            automaticOutcome: 'DENIED', automaticReason: 'MODEL_NOT_FOUND',
            automaticModelKey: null, executableModelCount: 0,
            wouldLockOutOrganization: true, models: [],
        })), onSubmit})
        enablePolicy()
        clickPreview()
        await screen.findByText(/Доступных моделей: 0/)
        expect(screen.getByRole('button', {name: 'Сохранить правила'})).toBeDisabled()
        clickSubmit()
        expect(onSubmit).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('checkbox', {name: /Подтверждаю осознанную блокировку/}))
        expect(screen.getByRole('button', {name: 'Сохранить правила'})).toBeEnabled()
        clickSubmit()
        await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
        expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            previewConfirmationId: '12345678-1234-4123-8123-123456789abc', acknowledgeLockout: true,
        }))
    })

    it('не разрешает изменение правил при несовпадении организации и политики', async () => {
        const onPreview = vi.fn(async (_request: Request) => preview())
        const onSubmit = vi.fn(async (_request: Request) => {})
        render(<ModelPolicyModal
            policy={policy} catalog={[entry]} effectiveCatalog={[entry]}
            runtime={runtime} organizationId="ffffffff-ffff-ffff-ffff-ffffffffffff"
            organizationName="Другая компания" pending={false} onClose={vi.fn()}
            onPreview={onPreview} onSubmit={onSubmit}
        />)
        clickPreview()
        expect(screen.getByText('Организация формы не совпадает с загруженными правилами. Обновите данные.')).toBeInTheDocument()
        clickSubmit()
        expect(onPreview).not.toHaveBeenCalled()
        expect(onSubmit).not.toHaveBeenCalled()
    })

    it('не использует результат проверки, полученный после изменения черновика', async () => {
        let resolve!: (value: ModelPolicyPreview) => void
        const onPreview = vi.fn((_request: Request) => new Promise<ModelPolicyPreview>(r => {resolve = r}))
        const {onSubmit} = setup({onPreview})
        enablePolicy()
        clickPreview()
        fireEvent.click(screen.getByRole('tab', {name: /Лимиты/}))
        fireEvent.change(screen.getByLabelText('Максимум входных токенов'), {target: {value: '8096'}})
        await act(async () => resolve(preview()))
        fireEvent.click(screen.getByRole('tab', {name: /Проверка/}))
        expect(screen.getByText(/Правила изменены после предыдущей проверки/)).toBeInTheDocument()
        clickSubmit()
        expect(onSubmit).not.toHaveBeenCalled()
    })

    it('считает прежний preview недействительным после смены версии политики', async () => {
        const onPreview = vi.fn(async (_request: Request) => preview())
        const onSubmit = vi.fn(async (_request: Request) => {})
        const ui = (version: number) => <ModelPolicyModal
            policy={version === 0 ? policy : {
                ...policy, configured: true, version,
                id: '00000000-0000-0000-0000-000000000099',
                createdByUserId: '00000000-0000-0000-0000-000000000012',
                createdAt: '2026-10-21T06:00:00Z',
            }}
            catalog={[entry]} effectiveCatalog={[entry]}
            runtime={runtime} organizationId={orgId} organizationName="Компания"
            pending={false} onClose={vi.fn()} onPreview={onPreview} onSubmit={onSubmit}
        />
        const {rerender} = render(ui(0))
        enablePolicy()
        clickPreview()
        await screen.findByText('Результат проверки правил')
        rerender(ui(1))
        clickSubmit()
        expect(onSubmit).not.toHaveBeenCalled()
        expect(screen.getByText(/На сервере изменилась версия правил/)).toHaveAttribute('role', 'alert')
        expect(screen.getByRole('button', {name: 'Сохранить правила'})).toBeDisabled()
    })

    it('показывает отказ серверной проверки и не разрешает сохранение', async () => {
        const {onSubmit} = setup({onPreview: vi.fn(async () => {throw new Error('SQL internal details: SELECT secret FROM private_table')})})
        enablePolicy()
        clickPreview()
        await screen.findByText('Не удалось проверить правила. Попробуйте повторно.')
        expect(screen.queryByText(/SELECT secret/)).not.toBeInTheDocument()
        clickSubmit()
        expect(onSubmit).not.toHaveBeenCalled()
    })

    it('показывает ошибку сохранения и позволяет повторную попытку без потери черновика', async () => {
        const onSubmit = vi.fn(async () => {throw new Error('SQL internal details: SELECT secret FROM private_table')})
        setup({onSubmit})
        fireEvent.click(screen.getByRole('tab', {name: /Лимиты/}))
        fireEvent.change(screen.getByLabelText('Максимум входных токенов'), {target: {value: '32000'}})
        clickPreview()
        await screen.findByText('Результат проверки правил')
        clickSubmit()
        await screen.findByText('Не удалось сохранить правила. Попробуйте повторно.')
        expect(screen.queryByText(/SELECT secret/)).not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('tab', {name: /Лимиты/}))
        expect(screen.getByLabelText('Максимум входных токенов')).toHaveValue('32000')
        clickSubmit()
        await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2))
    })
})
