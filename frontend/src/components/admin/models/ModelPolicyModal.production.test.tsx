/* ============================================================
   frontend/src/components/admin/models/ModelPolicyModal.production.test.tsx
   ============================================================ */
import {
    fireEvent,
    render,
    screen,
    waitFor,
} from '@testing-library/react'
import {
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import type {
    CreateOrganizationModelPolicyVersionRequest,
    ModelCatalogEntry,
    ModelPolicyPreview,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../api/modelApi'
import {
    ModelPolicyModal,
} from './ModelPolicyModal'

const ORGANIZATION_ID =
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

const RUNTIME: RuntimeModelStatus = {
    provider: 'mock',
    model: 'mock-safeai',
    enabled: true,
    routingMode: 'SINGLE_PROVIDER_STATIC',
    maxInputTokens: 64_000,
    maxOutputTokens: 2_048,
    toolsSupported: false,
    visionSupported: false,
    structuredOutputSupported: false,
    dataRetentionStatus: 'NOT_DECLARED',
    healthStatus: 'NOT_PROBED',
    pricingStatus: 'FREE',
    inputUsdPer1mTokens: '0',
    outputUsdPer1mTokens: '0',
    pricingVersion: 'mock-2026-01',
}

const ENTRY: ModelCatalogEntry = {
    id: '11111111-1111-4111-8111-111111111111',
    modelKey: 'mock:mock-safeai',
    version: 1,
    provider: 'mock',
    providerModelId: 'mock-safeai',
    displayName: 'mock-safeai',
    lifecycle: 'ACTIVE',
    maxInputTokens: 64_000,
    maxOutputTokens: 2_048,
    capabilities: [],
    inputModalities: ['TEXT'],
    outputModalities: ['TEXT'],
    retentionStatus: 'NOT_DECLARED',
    retentionDays: null,
    trainingUseStatus: 'NOT_DECLARED',
    pricingStatus: 'FREE',
    pricingComplete: true,
    inputUsdPer1mTokens: '0',
    cachedInputUsdPer1mTokens: '0',
    cacheWriteInputUsdPer1mTokens: '0',
    outputUsdPer1mTokens: '0',
    extraPricingJson: '{}',
    pricingVersion: 'mock-2026-01',
    effectiveFrom: '2026-09-05T10:00:00Z',
    source: 'RUNTIME_IMPORT',
    createdByUserId:
        '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-09-05T10:00:00Z',
}

type PolicySubmit = (
    request: CreateOrganizationModelPolicyVersionRequest,
) => Promise<void>

type PolicyPreviewFn = (
    request: CreateOrganizationModelPolicyVersionRequest,
) => Promise<ModelPolicyPreview>

const unusedPreview = vi.fn<PolicyPreviewFn>()
    .mockRejectedValue(new Error('Preview не ожидался в этом тесте'))

const UNCONFIGURED: OrganizationModelPolicy = {
    configured: false,
    id: null,
    organizationId: ORGANIZATION_ID,
    version: 0,
    enabled: false,
    allowModelKeys: [],
    denyModelKeys: [],
    defaultModelKey: null,
    maxInputTokens: null,
    maxOutputTokens: null,
    maxRequestCostUsd: null,
    monthlyBudgetUsd: null,
    budgetEnforcement: 'SOFT',
    requireCompletePricing: false,
    requireNoTraining: false,
    requireZeroDataRetention: false,
    createdByUserId: null,
    createdAt: null,
}

describe('ModelPolicyModal production safety', () => {
    it('explains NOT_DECLARED and displays the server training rejection without changing the draft', async () => {
        const onPreview = vi.fn<PolicyPreviewFn>().mockResolvedValue({
            organizationId: ORGANIZATION_ID,
            basePolicyVersion: 0,
            enabled: true,
            evaluatedAt: '2026-09-27T12:00:00Z',
            runtimeProvider: 'mock',
            runtimeModel: 'mock-safeai',
            automaticOutcome: 'DENIED',
            automaticReason: 'TRAINING_POLICY_UNSATISFIED',
            automaticModelKey: ENTRY.modelKey,
            executableModelCount: 0,
            wouldLockOutOrganization: true,
            models: [{
                modelKey: ENTRY.modelKey,
                catalogVersion: 1,
                provider: ENTRY.provider,
                providerModelId: ENTRY.providerModelId,
                lifecycle: ENTRY.lifecycle,
                effectiveInputLimit: 64000,
                effectiveOutputLimit: 2048,
                pricingComplete: true,
                estimatedMaxCostUsd: '0',
                monthlyBudgetUsd: null,
                monthlyProjectedUsd: null,
                monthlyCostKnown: true,
                budgetExceeded: false,
                outcome: 'DENIED',
                reason: 'TRAINING_POLICY_UNSATISFIED',
            }],
        })
        render(<ModelPolicyModal
            policy={{...UNCONFIGURED, enabled: true, requireNoTraining: true}}
            catalog={[{...ENTRY, version: 2, trainingUseStatus: 'NOT_USED'}]}
            effectiveCatalog={[ENTRY]}
            runtime={RUNTIME}
            organizationId={ORGANIZATION_ID}
            organizationName="Demo Company"
            pending={false}
            onClose={vi.fn()}
            onPreview={onPreview}
            onSubmit={vi.fn<PolicySubmit>()}
        />)
        const localTrainingWarning = screen.getByRole('status')
        expect(localTrainingWarning).toHaveTextContent(
            'Несовместимость с требованием запрета обучения',
        )
        expect(localTrainingWarning).toHaveTextContent(
            'Проверьте черновик на сервере',
        )
        expect(localTrainingWarning)
            .not.toHaveClass('models-policy-form__warning--danger')

        fireEvent.click(screen.getByRole('button', {name: 'Проверить правила'}))

        expect(await screen.findByText(/Исполняемых моделей: 0/)).toBeInTheDocument()
        expect(screen.getAllByText(/Запрет обучения не подтверждён/)).toHaveLength(2)

        const confirmedTrainingLockout = screen.getByRole('alert')
        expect(confirmedTrainingLockout).toHaveClass(
            'models-policy-form__warning--danger',
        )
        expect(confirmedTrainingLockout).toHaveTextContent(
            'Правила блокируют доступные модели',
        )
        expect(confirmedTrainingLockout).toHaveTextContent(
            'Серверная проверка подтвердила блокировку',
        )
        expect(confirmedTrainingLockout).toHaveTextContent(ENTRY.modelKey)
        expect(confirmedTrainingLockout).toHaveTextContent(
            'Исполняемых моделей: 0',
        )
        expect(confirmedTrainingLockout).not.toHaveTextContent(
            'Проверьте черновик на сервере',
        )

        expect(onPreview).toHaveBeenCalledWith(expect.objectContaining({
            enabled: true, requireNoTraining: true, budgetEnforcement: 'SOFT',
        }))
        fireEvent.click(screen.getByRole('checkbox', {name: 'Правила включены'}))
        expect(screen.getByText('При включении правил возникнет несовместимость'))
            .toBeInTheDocument()
        expect(screen.queryByText(/Исполняемых моделей: 0/)).not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('checkbox', {name: /Не использовать данные для обучения/}))
        expect(screen.queryByText('При включении правил возникнет несовместимость'))
            .not.toBeInTheDocument()
    })

    it.each(['NOT_USED', 'CONTRACTUAL_NO_TRAINING'] as const)(
        'uses effective %s metadata instead of the latest catalog version', (trainingUseStatus) => {
            render(<ModelPolicyModal
                policy={{...UNCONFIGURED, enabled: true, requireNoTraining: true}}
                catalog={[ENTRY]}
                effectiveCatalog={[{...ENTRY, trainingUseStatus}]}
                runtime={RUNTIME}
                organizationId={ORGANIZATION_ID}
                organizationName="Demo Company"
                pending={false}
                onClose={vi.fn()}
                onPreview={unusedPreview}
                onSubmit={vi.fn<PolicySubmit>()}
            />)
            expect(screen.queryByText('Несовместимость с требованием запрета обучения'))
                .not.toBeInTheDocument()
        },
    )

    it('first policy opens disabled and shows organization name first', () => {
        render(
            <ModelPolicyModal
                policy={UNCONFIGURED}
                catalog={[ENTRY]}
                effectiveCatalog={[ENTRY]}
                runtime={RUNTIME}
                organizationId={ORGANIZATION_ID}
                organizationName="Demo Company"
                pending={false}
                onClose={vi.fn()}
                onPreview={unusedPreview}
                onSubmit={vi.fn<PolicySubmit>()}
            />,
        )

        expect(
            screen.getByText('Правила выключены'),
        ).toBeInTheDocument()
        expect(
            screen.getByText('Demo Company'),
        ).toBeInTheDocument()
    })

    it('warns before intentionally enabling fail-closed policy without runtime catalog match', () => {
        render(
            <ModelPolicyModal
                policy={UNCONFIGURED}
                catalog={[]}
                effectiveCatalog={[]}
                runtime={RUNTIME}
                organizationId={ORGANIZATION_ID}
                organizationName="Demo Company"
                pending={false}
                onClose={vi.fn()}
                onPreview={unusedPreview}
                onSubmit={vi.fn<PolicySubmit>()}
            />,
        )

        fireEvent.click(
            screen.getByRole(
                'checkbox',
                {name: 'Правила выключены'},
            ),
        )

        expect(
            screen.getByRole('alert'),
        ).toHaveTextContent(
            'нет действующей записи каталога',
        )
    })

    it('first save uses expectedPreviousVersion 0', async () => {
        const onSubmit = vi.fn<PolicySubmit>().mockResolvedValue(undefined)

        render(
            <ModelPolicyModal
                policy={UNCONFIGURED}
                catalog={[ENTRY]}
                effectiveCatalog={[ENTRY]}
                runtime={RUNTIME}
                organizationId={ORGANIZATION_ID}
                organizationName="Demo Company"
                pending={false}
                onClose={vi.fn()}
                onPreview={unusedPreview}
                onSubmit={onSubmit}
            />,
        )

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Сохранить правила'},
            ),
        )

        await waitFor(() => {
            expect(onSubmit)
                .toHaveBeenCalledWith(
                    expect.objectContaining({
                        expectedPreviousVersion: 0,
                        enabled: false,
                    }),
                )
        })
    })

    it('requires a current server preview and explicit acknowledgement before lockout save', async () => {
        const onSubmit = vi.fn<PolicySubmit>()
            .mockResolvedValue(undefined)
        const onPreview = vi.fn<PolicyPreviewFn>()
            .mockResolvedValue({
                organizationId: ORGANIZATION_ID,
                basePolicyVersion: 0,
                enabled: true,
                evaluatedAt: '2026-09-09T17:00:00Z',
                runtimeProvider: 'mock',
                runtimeModel: 'mock-safeai',
                automaticOutcome: 'DENIED',
                automaticReason: 'MODEL_NOT_FOUND',
                automaticModelKey: null,
                executableModelCount: 0,
                wouldLockOutOrganization: true,
                models: [],
            })

        render(
            <ModelPolicyModal
                policy={UNCONFIGURED}
                catalog={[]}
                effectiveCatalog={[]}
                runtime={RUNTIME}
                organizationId={ORGANIZATION_ID}
                organizationName="Demo Company"
                pending={false}
                onClose={vi.fn()}
                onPreview={onPreview}
                onSubmit={onSubmit}
            />,
        )

        fireEvent.click(
            screen.getByRole(
                'checkbox',
                {name: 'Правила выключены'},
            ),
        )

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Сохранить правила'},
            ),
        )
        expect(onSubmit).not.toHaveBeenCalled()
        expect(
            await screen.findByText(/выполните «Проверить правила»/),
        ).toBeInTheDocument()

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Проверить правила'},
            ),
        )

        await waitFor(() => {
            expect(onPreview).toHaveBeenCalledTimes(1)
        })
        expect(
            await screen.findByText(/Исполняемых моделей: 0/),
        ).toBeInTheDocument()

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Сохранить правила'},
            ),
        )
        expect(onSubmit).not.toHaveBeenCalled()
        expect(
            await screen.findByText(/Подтвердите осознанную блокировку/),
        ).toBeInTheDocument()

        fireEvent.click(
            screen.getByRole(
                'checkbox',
                {name: /Подтверждаю осознанную блокировку/},
            ),
        )

        fireEvent.click(
            screen.getByRole(
                'button',
                {name: 'Сохранить правила'},
            ),
        )

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalledTimes(1)
        })
    })

})

