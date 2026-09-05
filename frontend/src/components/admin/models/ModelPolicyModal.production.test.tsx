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
    ModelCatalogEntry,
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
                onSubmit={vi.fn()}
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
                onSubmit={vi.fn()}
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
        const onSubmit = vi.fn().mockResolvedValue(undefined)

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
})
