// frontend/src/features/models/ui/ModelPolicyModal.previewHardening.test.tsx
// Regression coverage for irreversible preview staleness and human-readable results.
import {act, fireEvent, render, screen, waitFor} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {ModelPolicyModal} from './ModelPolicyModal'
import {POLICY_PREVIEW_LABEL, policyPreviewState} from './modelPolicyModal/policyPreviewStatus'
import type {ModelPolicyPreview, CreateOrganizationModelPolicyVersionRequest} from '../../../entities/model/api/modelApi'
import {
    createModelCatalogEntry,
    createOrganizationModelPolicy,
    createRuntimeModelStatus,
} from '../../../test/fixtures/modelControlPlaneTestFixtures'

const orgId = '00000000-0000-0000-0000-000000000001'
const entry = createModelCatalogEntry()
const runtime = createRuntimeModelStatus()
const policy = createOrganizationModelPolicy({organizationId: orgId})
const evaluation = (overrides: Partial<ModelPolicyPreview> = {}): ModelPolicyPreview => ({
    organizationId: orgId,
    basePolicyVersion: policy.version,
    enabled: true,
    evaluatedAt: '2026-10-24T14:00:00Z',
    runtimeProvider: runtime.provider,
    runtimeModel: runtime.model,
    automaticOutcome: 'ALLOWED',
    automaticReason: 'RUNTIME_ONLY_MATCH',
    automaticModelKey: entry.modelKey,
    executableModelCount: 1,
    wouldLockOutOrganization: false,
    models: [{
        modelKey: entry.modelKey,
        catalogVersion: entry.version,
        provider: entry.provider,
        providerModelId: entry.providerModelId,
        lifecycle: 'ACTIVE',
        outcome: 'ALLOWED', reason: null,
        effectiveInputLimit: 64000, effectiveOutputLimit: 2048,
        pricingComplete: true,
        estimatedMaxCostUsd: '0',
        monthlyBudgetUsd: null, monthlyProjectedUsd: null,
        monthlyCostKnown: true, budgetExceeded: false,
    }],
    ...overrides,
})

type Submit = (value: CreateOrganizationModelPolicyVersionRequest) => Promise<void>
function setup(options: {
    preview?: (value: CreateOrganizationModelPolicyVersionRequest) => Promise<ModelPolicyPreview>
    submit?: Submit
} = {}) {
    const onSubmit = options.submit ?? vi.fn(async () => {})
    const onPreview = options.preview ?? vi.fn(async () => evaluation())
    const view = (model = runtime.model, version = entry.version) => (
        <ModelPolicyModal
            policy={policy} runtime={{...runtime, model}}
            catalog={[entry]} effectiveCatalog={[{...entry, version}]}
            organizationId={orgId} organizationName="Организация"
            pending={false} onClose={vi.fn()}
            onPreview={onPreview} onSubmit={onSubmit} />
    )
    const result = render(view())
    fireEvent.click(screen.getByRole('checkbox', {name: 'Правила выключены'}))
    return {...result, view, onSubmit, onPreview}
}

async function check() {
    fireEvent.click(screen.getByRole('button', {name: 'Проверить правила'}))
    await screen.findByText('Серверная проверка черновика')
}

function save() {
    fireEvent.click(screen.getByRole('button', {name: 'Сохранить правила'}))
}

describe('ModelPolicyModal: production preview hardening', () => {
    it('A → B → A of retention settings does not revive an obsolete preview', async () => {
        const {onSubmit} = setup()
        await check()
        fireEvent.click(screen.getByRole('tab', {name: /Требования к данным/}))
        const retention = screen.getByRole('checkbox', {name: 'Не хранить данные после запроса'})
        const initial = (retention as HTMLInputElement).checked
        fireEvent.click(retention)
        fireEvent.click(retention)
        expect((retention as HTMLInputElement).checked).toBe(initial)
        fireEvent.click(screen.getByRole('tab', {name: /Проверка/}))
        expect(screen.getByText(/Правила изменены после предыдущей проверки/)).toBeInTheDocument()
        save()
        expect(onSubmit).not.toHaveBeenCalled()
    })

    it('A → B → A of runtime props permanently invalidates previous preview', async () => {
        const {rerender, view, onSubmit} = setup()
        await check()
        rerender(view('other-model'))
        rerender(view(runtime.model))
        expect(screen.queryByText('Серверная проверка черновика')).not.toBeInTheDocument()
        save()
        expect(onSubmit).not.toHaveBeenCalled()
    })

    it('rejects a response obtained across runtime A → B → A while request is pending', async () => {
        let complete!: (value: ModelPolicyPreview) => void
        const onPreview = vi.fn(() => new Promise<ModelPolicyPreview>(resolve => {complete = resolve}))
        const {rerender, view, onSubmit} = setup({preview: onPreview})
        fireEvent.click(screen.getByRole('button', {name: 'Проверить правила'}))
        rerender(view('other-model'))
        rerender(view(runtime.model))
        await act(async () => complete(evaluation()))
        expect(screen.queryByText('Серверная проверка черновика')).not.toBeInTheDocument()
        save()
        expect(onSubmit).not.toHaveBeenCalled()
    })

    it('uses human-facing reason, separate facts and explicit model/catalog version', async () => {
        setup({preview: vi.fn(async () => evaluation({
            automaticOutcome: 'DENIED', automaticReason: 'TRAINING_POLICY_UNSATISFIED',
            executableModelCount: 0, wouldLockOutOrganization: true,
            models: [{...evaluation().models[0]!, outcome: 'DENIED', reason: 'TRAINING_POLICY_UNSATISFIED'}],
        }))})
        await check()
        expect(screen.getByText('Доступ заблокирован')).toBeInTheDocument()
        expect(screen.getByText(`Версия модели в каталоге: ${entry.version}`)).toBeInTheDocument()
        expect(screen.getByText('Доступ отклонён')).toBeInTheDocument()
        // The reason is shown in both the policy summary and the selected model.
        expect(screen.getAllByText(/Не подтверждено, что данные не используются для обучения/).length)
            .toBeGreaterThan(0)
        expect(screen.getByText('Данные о стоимости полные')).toBeInTheDocument()
        expect(screen.getByText('Лимиты токенов · вход / выход')).toBeInTheDocument()
        expect(screen.queryByText(/CONTRACTUAL_NO_TRAINING|NOT_DECLARED|NOT_USED/)).not.toBeInTheDocument()
    })

    it('distinguishes a warning-level partial denial from a successful check', () => {
        const restricted = evaluation({automaticOutcome: 'DENIED', wouldLockOutOrganization: false})
        const status = policyPreviewState({
            currentPreview: restricted, policyPreview: restricted,
            previewPending: false, previewError: '',
        })
        expect(status).toBe('restricted')
        expect(POLICY_PREVIEW_LABEL[status]).toBe('Есть ограничения')
    })

    it('retention and training are independent request fields', async () => {
        const onSubmit = vi.fn<Submit>().mockResolvedValue(undefined)
        setup({submit: onSubmit})
        fireEvent.click(screen.getByRole('tab', {name: /Требования к данным/}))
        const training = screen.getByRole('checkbox', {name: 'Не использовать данные для обучения'})
        const retention = screen.getByRole('checkbox', {name: 'Не хранить данные после запроса'})
        fireEvent.click(training)
        fireEvent.click(retention)
        fireEvent.click(retention)
        await check()
        save()
        await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            requireNoTraining: true, requireZeroDataRetention: false,
        })))
    })
})
