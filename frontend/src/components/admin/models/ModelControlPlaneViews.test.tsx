/* ============================================================
   frontend/src/components/admin/models/ModelControlPlaneViews.test.tsx
   ============================================================ */
import {
    render,
    screen,
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
    CatalogTable,
    PolicyCard,
    RuntimeCard,
} from './ModelControlPlaneViews'

const runtime: RuntimeModelStatus = {
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

const baseEntry: ModelCatalogEntry = {
    id: '00000000-0000-0000-0000-000000000101',
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
    cachedInputUsdPer1mTokens: null,
    cacheWriteInputUsdPer1mTokens: null,
    outputUsdPer1mTokens: '0',
    extraPricingJson: '{}',
    pricingVersion: 'mock-2026-01',
    effectiveFrom: '2026-09-05T17:45:24Z',
    source: 'RUNTIME_IMPORT',
    createdByUserId: '00000000-0000-0000-0000-000000000001',
    createdAt: '2026-09-05T17:45:24Z',
}

const policy: OrganizationModelPolicy = {
    configured: false,
    id: null,
    organizationId: '00000000-0000-0000-0000-000000000001',
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

describe('ModelControlPlaneViews semantics', () => {
    it('separates runtime configuration, health and executable capabilities', () => {
        render(
            <RuntimeCard
                runtime={runtime}
                effectiveCatalog={[baseEntry]}
                probe={null}
                probePending={false}
                canProbe
                onProbe={vi.fn()}
            />,
        )

        expect(
            screen.getByText('Режим Runtime'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Один фиксированный провайдер и модель'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Исполняемые возможности'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Не выполнялась'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Не заявлено'),
        ).toBeInTheDocument()

        expect(
            screen.queryByText('Состояние конфигурации'),
        ).not.toBeInTheDocument()
    })

    it('explains empty allow-list and unconfigured policy without implying deny-all', () => {
        render(
            <PolicyCard
                policy={policy}
                catalog={[baseEntry]}
                runtime={runtime}
                organizationId={policy.organizationId}
                organizationName="SafeAI Demo Organization"
                isSuperAdmin={false}
                organizationQuery=""
                organizationResults={[]}
                organizationSearchPending={false}
                onOrganizationQueryChange={vi.fn()}
                onOrganizationSearch={vi.fn()}
                onOrganizationSelect={vi.fn()}
                onEdit={vi.fn()}
            />,
        )

        expect(
            screen.getByText('SafeAI Demo Organization'),
        ).toBeInTheDocument()

        expect(
            screen.getByText(
                'Разрешённые: все допустимые · Запрещённые: 0',
            ),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Без дополнительных требований'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Не ограничены правилами'),
        ).toBeInTheDocument()
    })

    it('distinguishes latest, effective and lifecycle semantics in catalog', () => {
        render(
            <CatalogTable
                entries={[baseEntry]}
                effectiveEntries={[baseEntry]}
                runtime={runtime}
                canEdit
                onCreateVersion={vi.fn()}
                onOpenHistory={vi.fn()}
            />,
        )

        expect(
            screen.getByRole('columnheader', {
                name: 'Статус каталога',
            }),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Последняя версия'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Действующая версия'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Действует сейчас'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Активна'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Хранение: Не заявлено'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Обучение: Не заявлено'),
        ).toBeInTheDocument()
    })

    it('marks a latest non-effective snapshot as scheduled without browser-clock routing logic', () => {
        const scheduled = {
            ...baseEntry,
            id: '00000000-0000-0000-0000-000000000102',
            version: 2,
            effectiveFrom: '2099-01-01T00:00:00Z',
        }

        render(
            <CatalogTable
                entries={[scheduled]}
                effectiveEntries={[baseEntry]}
                runtime={runtime}
                canEdit={false}
                onCreateVersion={vi.fn()}
                onOpenHistory={vi.fn()}
            />,
        )

        expect(
            screen.getByText('Запланирована'),
        ).toBeInTheDocument()

        expect(
            screen.getByText('Сейчас действует версия 1'),
        ).toBeInTheDocument()
    })

    it('shows version history to read-only admin while keeping new-version mutation hidden', () => {
        render(
            <CatalogTable
                entries={[baseEntry]}
                effectiveEntries={[baseEntry]}
                runtime={runtime}
                canEdit={false}
                onCreateVersion={vi.fn()}
                onOpenHistory={vi.fn()}
            />,
        )

        expect(
            screen.getByRole('button', {
                name: 'История версий',
            }),
        ).toBeInTheDocument()

        expect(
            screen.queryByRole('button', {
                name: 'Новая версия',
            }),
        ).not.toBeInTheDocument()
    })

})
